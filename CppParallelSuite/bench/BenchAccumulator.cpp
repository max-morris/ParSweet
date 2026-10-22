#include "Bench.hpp"
#include "BlackBox.hpp"

#include "../locks/ALock.hpp"
#include "../locks/BackoffLock.hpp"
#include "../locks/CLHLock.hpp"
#include "../locks/IdLock.hpp"
#include "../locks/LockTraits.hpp"
#include "../locks/MCSLock.hpp"
#include "../locks/OptimizedALock.hpp"
#include "../locks/TASLock.hpp"
#include "../locks/TIdLock.hpp"
#include "../locks/TTASLock.hpp"
#include "../locks/TwoCounterLock.hpp"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

// Accumulator benchmark. Each flag has its own lock. A short job locks one
// flag, does busy work, and increments that flag. A long job locks every
// flag from first to last, does busy work while holding them, increments
// each, and unlocks from last back to first. That order keeps the long jobs
// from deadlocking with each other or with a short job.
// When PSWEET_OPS is positive and even and more than one thread is running,
// the higher ids, half of the threads rounded down, only run short jobs.
// They do a fixed number of short jobs and then finish. One thread, an odd
// count, or zero means every thread runs the long jobs.

namespace parallel_bench::accumulator {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    // Busy work whose length is the argument. Unsigned wrap is intentional:
    // signed overflow is undefined, and blackBox below keeps the loop alive.
    unsigned busy(int steps) {
        unsigned mix = 1;
        for (int i = 0; i < steps; ++i) {
            mix = mix * 33u + 1u;
        }
        return mix;
    }

    // Which flag a short job locks. The same formula builds the expected counts.
    int flagFor(long long round, int step, long long threadId, int nShort, int nFlags) {
        long long index = round * nShort + step + threadId;
        return static_cast<int>(index % nFlags);
    }

    // How many threads run long jobs. The rest only run short jobs, and only
    // when the short-job count is positive and even. A single thread has to
    // run the long jobs itself. An odd count, or zero, means every thread
    // runs them. Half, rounded down, are the higher ids.
    int longWorkers(usize nThreads, int nShort) {
        if (nShort <= 0 || nShort % 2 != 0 || nThreads <= 1) {
            return static_cast<int>(nThreads);
        }
        return static_cast<int>(nThreads - nThreads / 2);
    }

    // Padded so two flags' counters do not share a cache line. The trial is
    // measuring the locks, not bouncing this array.
    struct alignas(64) Hit {
        long long value = 0;
    };

    template <MutexType Mutex>
    void shortJob(Mutex& lock, Hit& hit) {
        std::lock_guard<Mutex> guard(lock);
        unsigned mix = busy(1);
        hit.value += 1;
        blackBox(mix);
    }

    template <MutexType Mutex>
    void longJob(std::vector<std::unique_ptr<Mutex>> const& locks, std::vector<Hit>& hits) {
        int nFlags = static_cast<int>(locks.size());
        int held = 0;
        try {
            for (int i = 0; i < nFlags; ++i) {
                locks[i]->lock();
                held++;
            }
            unsigned mix = busy(nFlags);
            for (int i = 0; i < nFlags; ++i) {
                hits[i].value += 1;
            }
            blackBox(mix);
        } catch (...) {
            for (int i = held - 1; i >= 0; --i) {
                locks[i]->unlock();
            }
            throw;
        }
        for (int i = nFlags - 1; i >= 0; --i) {
            locks[i]->unlock();
        }
    }

    template <MutexType Mutex>
    void bench(usize nThreads, usize work, int nFlags, int nShort) {
        std::vector<std::unique_ptr<Mutex>> locks;
        locks.reserve(static_cast<usize>(nFlags));
        for (int i = 0; i < nFlags; ++i) {
            locks.push_back(std::make_unique<Mutex>());
        }
        std::vector<Hit> hits(static_cast<usize>(nFlags));

        int nLong = longWorkers(nThreads, nShort);
        std::vector<std::thread> workers;
        workers.reserve(nThreads);
        for (usize t = 0; t < nThreads; ++t) {
            workers.emplace_back([&locks, &hits, t, work, nFlags, nShort, nLong]() {
                try {
                    bool shortsOnly = static_cast<long long>(t) >= nLong;
                    for (usize round = 0; round < work; ++round) {
                        for (int step = 0; step < nShort; ++step) {
                            int flag = flagFor(static_cast<long long>(round), step, static_cast<long long>(t), nShort, nFlags);
                            shortJob(*locks[flag], hits[flag]);
                        }
                        if (!shortsOnly) {
                            longJob(locks, hits);
                        }
                    }
                } catch (std::exception const& ex) {
                    std::cerr << "accumulator worker: " << ex.what() << std::endl;
                    std::abort();
                }
            });
        }

        for (auto& worker : workers) {
            worker.join();
        }

        // Each long job adds one to every flag. Short jobs are counted below.
        std::vector<long long> expect(static_cast<usize>(nFlags), static_cast<long long>(nLong) * static_cast<long long>(work));
        for (usize t = 0; t < nThreads; ++t) {
            for (usize round = 0; round < work; ++round) {
                for (int step = 0; step < nShort; ++step) {
                    int flag = flagFor(static_cast<long long>(round), step, static_cast<long long>(t), nShort, nFlags);
                    expect[flag] += 1;
                }
            }
        }
        for (int flag = 0; flag < nFlags; ++flag) {
            if (hits[flag].value != expect[flag]) {
                throw std::runtime_error("accumulator flag " + std::to_string(flag) + " count " + std::to_string(hits[flag].value) +
                                         " expected " + std::to_string(expect[flag]));
            }
        }
    }

    int envInt(char const* name, int fallback) {
        char const* raw = std::getenv(name);
        if (raw == nullptr || raw[0] == '\0') {
            return fallback;
        }
        char* end = nullptr;
        long value = std::strtol(raw, &end, 10);
        if (end == raw || *end != '\0' || value < 0 || value > std::numeric_limits<int>::max()) {
            throw std::runtime_error(std::string("bad ") + name);
        }
        return static_cast<int>(value);
    }

    // True if this lock, or a later one in the pack, was the selected run.
    template <MutexType Mutex, typename... Rest>
    bool benchLocks(BenchParameters const& params, int nFlags, int nShort) {
        char const* lockName = locks::LockTraits<Mutex>::name;
        auto which = params.getWhich();
        bool ran = !which || *which == lockName;
        if (ran) {
            std::cout << "Bench: " << lockName << std::endl;
            writeBenchResult(params, lockName, measure([&params, nFlags, nShort]() {
                bench<Mutex>(params.getNThreads(), params.getWorkPerThread(), nFlags, nShort);
            }));
            params.coolOff();
        }

        if constexpr (sizeof...(Rest) > 0) {
            ran = benchLocks<Rest...>(params, nFlags, nShort) || ran;
        }
        return ran;
    }
} // namespace parallel_bench::accumulator

namespace parallel_suite::locks {
    template <>
    struct LockTraits<std::mutex> {
        constexpr static char const* name = "std::mutex";
    };

    template <>
    struct LockTraits<std::recursive_mutex> {
        constexpr static char const* name = "std::recursive_mutex";
    };
} // namespace parallel_suite::locks

int main() {
    using namespace parallel_bench;
    using namespace parallel_bench::accumulator;

    try {
        BenchParameters params("c++", "accumulator");
        int nFlags = envInt("PSWEET_WATCHES", 32);
        int nShort = envInt("PSWEET_OPS", 7);
        // Anderson locks and MCS ThreadLocal are sized N_THREADS_ALLOC. More
        // workers than that alias a slot and corrupt the handshake.
        if (params.getNThreads() == 0 || params.getNThreads() > N_THREADS_ALLOC) {
            throw std::runtime_error("PSWEET_NTHREADS must be in 1..N_THREADS_ALLOC");
        }
        if (params.getWorkPerThread() == 0 || nFlags <= 0 || nShort < 0) {
            throw std::runtime_error("PSWEET_WORK_PER_THREAD and PSWEET_WATCHES must be positive");
        }

        bool ran = benchLocks<std::mutex,
                              std::recursive_mutex,
                              locks::ALock<N_THREADS_ALLOC>,
                              locks::BackoffLock<1, 17>,
                              locks::CLHLock,
                              locks::IdLock,
                              locks::MCSLock,
                              locks::OptimizedALock<N_THREADS_ALLOC>,
                              locks::TASLock,
                              locks::TIdLock,
                              locks::TTASLock,
                              locks::TwoCounterLock>(params, nFlags, nShort);
        if (!ran) {
            throw std::runtime_error("PSWEET_WHICH does not name a lock: " + *params.getWhich());
        }
        return 0;
    } catch (std::exception const& ex) {
        std::cerr << ex.what() << std::endl;
        return 0xBAD;
    }
}
