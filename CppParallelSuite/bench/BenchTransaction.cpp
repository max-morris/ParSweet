#include "Bench.hpp"

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
#include "../threadlocal/ThreadLocalRand.hpp"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <memory>
#include <mutex>
#include <numeric>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

// N integers start at 0. An attempt reads 3, 4, or 5 of them with no locks
// held, then locks every location from first to last. If any of those values
// changed, it unlocks from last back to first and tries again. Otherwise it
// increments the smallest, breaking a tie toward the lowest index, and
// unlocks the same way. Each thread stops after a fixed number of commits.
// The sum of the integers is that many commits times the number of threads.

namespace parallel_bench::transaction {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    constexpr int MIN_SUBSET = 3;
    constexpr int MAX_SUBSET = 5;

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

    // True when this attempt committed. The caller retries until it has
    // enough commits. order is scratch storage of size N.
    template <MutexType Mutex>
    bool commit(std::vector<std::unique_ptr<Mutex>> const& locks, std::vector<std::atomic<int>>& values, std::vector<int>& order) {
        int n = static_cast<int>(values.size());
        std::iota(order.begin(), order.end(), 0);
        int k = threadlocal::ThreadLocalRand::intRand.getRand(MIN_SUBSET, MAX_SUBSET);
        for (int i = 0; i < k; ++i) {
            int j = threadlocal::ThreadLocalRand::intRand.getRand(i, n - 1);
            std::swap(order[i], order[j]);
        }

        // Unlocked read. The atomics keep this defined while another thread
        // stores under the locks. Relaxed is enough: the locked re-read below
        // is what decides the commit, and the mutex orders that re-read.
        int snap[MAX_SUBSET];
        for (int i = 0; i < k; ++i) {
            snap[i] = values[static_cast<usize>(order[i])].load(std::memory_order_relaxed);
        }

        int held = 0;
        bool ok = false;
        try {
            for (int i = 0; i < n; ++i) {
                locks[static_cast<usize>(i)]->lock();
                held++;
            }
            ok = true;
            for (int i = 0; i < k; ++i) {
                int now = values[static_cast<usize>(order[i])].load(std::memory_order_relaxed);
                if (now != snap[i]) {
                    ok = false;
                }
            }
            if (ok) {
                int best = 0;
                for (int i = 1; i < k; ++i) {
                    if (snap[i] < snap[best] || (snap[i] == snap[best] && order[i] < order[best])) {
                        best = i;
                    }
                }
                values[static_cast<usize>(order[best])].store(snap[best] + 1, std::memory_order_relaxed);
            }
        } catch (...) {
            for (int i = held - 1; i >= 0; --i) {
                locks[static_cast<usize>(i)]->unlock();
            }
            throw;
        }
        for (int i = n - 1; i >= 0; --i) {
            locks[static_cast<usize>(i)]->unlock();
        }
        return ok;
    }

    template <MutexType Mutex>
    void bench(usize nThreads, usize work, int nValues) {
        std::vector<std::unique_ptr<Mutex>> locks;
        locks.reserve(static_cast<usize>(nValues));
        for (int i = 0; i < nValues; ++i) {
            locks.push_back(std::make_unique<Mutex>());
        }
        std::vector<std::atomic<int>> values(static_cast<usize>(nValues));

        std::vector<std::thread> workers;
        workers.reserve(nThreads);
        for (usize t = 0; t < nThreads; ++t) {
            workers.emplace_back([&locks, &values, work]() {
                try {
                    std::vector<int> order(values.size());
                    for (usize done = 0; done < work; ) {
                        if (commit(locks, values, order)) {
                            done++;
                        }
                    }
                } catch (std::exception const& ex) {
                    std::cerr << "transaction worker: " << ex.what() << std::endl;
                    std::abort();
                }
            });
        }
        for (auto& worker : workers) {
            worker.join();
        }

        long long sum = 0;
        for (auto& value : values) {
            sum += value.load(std::memory_order_relaxed);
        }
        long long expect = static_cast<long long>(nThreads) * static_cast<long long>(work);
        if (sum != expect) {
            throw std::runtime_error("transaction sum " + std::to_string(sum) + " expected " + std::to_string(expect));
        }
    }

    // True if this lock, or a later one in the pack, was the selected run.
    template <MutexType Mutex, typename... Rest>
    bool benchLocks(BenchParameters const& params, int nValues) {
        char const* lockName = locks::LockTraits<Mutex>::name;
        auto which = params.getWhich();
        bool ran = !which || *which == lockName;
        if (ran) {
            std::cout << "Bench: " << lockName << std::endl;
            writeBenchResult(params, lockName, measure([&params, nValues]() {
                bench<Mutex>(params.getNThreads(), params.getWorkPerThread(), nValues);
            }));
            params.coolOff();
        }
        if constexpr (sizeof...(Rest) > 0) {
            ran = benchLocks<Rest...>(params, nValues) || ran;
        }
        return ran;
    }
} // namespace parallel_bench::transaction

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
    using namespace parallel_bench::transaction;

    try {
        BenchParameters params("c++", "transaction");
        int nValues = envInt("PSWEET_WATCHES", 32);
        // Anderson locks and MCS ThreadLocal are sized N_THREADS_ALLOC. More
        // workers than that alias a slot and corrupt the handshake.
        if (params.getNThreads() == 0 || params.getNThreads() > N_THREADS_ALLOC) {
            throw std::runtime_error("PSWEET_NTHREADS must be in 1..N_THREADS_ALLOC");
        }
        if (params.getWorkPerThread() == 0 || nValues < MAX_SUBSET) {
            throw std::runtime_error("PSWEET_WORK_PER_THREAD must be positive and PSWEET_WATCHES must be at least 5");
        }
        // One cell can receive every commit. Keep that increment inside a signed int.
        if (params.getWorkPerThread() > static_cast<usize>(std::numeric_limits<int>::max()) / params.getNThreads()) {
            throw std::runtime_error("PSWEET_NTHREADS * PSWEET_WORK_PER_THREAD must fit in a signed int");
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
                              locks::TwoCounterLock>(params, nValues);
        if (!ran) {
            throw std::runtime_error("PSWEET_WHICH does not name a lock: " + *params.getWhich());
        }
        return 0;
    } catch (std::exception const& ex) {
        std::cerr << ex.what() << std::endl;
        return 0xBAD;
    }
}
