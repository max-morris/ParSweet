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

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

// Wide-watch completion (benchmarks.md candidate 11), blocking-lock policy.
// Each delayed operation watches every key and scans all of them under its own
// lock. That scan is the critical section. tryLock-helping is omitted: the
// suite's spin locks are not TryLockMutexType, so one sweep could not use them.
// Java's JDK lock is a single ReentrantLock; this sweep also times std::mutex
// and std::recursive_mutex.

namespace parallel_bench::wide_watch {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    template <MutexType Mutex>
    struct Op {
        Mutex mutex;
        // Relaxed atomics are not the synchronization (the mutex is). They keep
        // the compiler from turning a saturated scan into a constant countdown.
        std::unique_ptr<std::atomic<char>[]> seen;
        int watches;

        explicit Op(int watches) : seen(new std::atomic<char>[watches]()), watches(watches) {}
    };

    template <MutexType Mutex>
    void onKey(Op<Mutex>& op, int key) {
        unsigned mix;
        {
            std::lock_guard<Mutex> guard(op.mutex);
            if (op.seen[key].load(std::memory_order_relaxed) == 0) {
                op.seen[key].store(1, std::memory_order_relaxed);
            }
            mix = 1;
            for (int k = 0; k < op.watches; ++k) {
                unsigned bit = op.seen[k].load(std::memory_order_relaxed) == 0 ? 0u : 1u;
                // Unsigned wrap is intentional. Signed overflow is undefined, and
                // the multiply keeps every key in the result.
                mix = mix * 33u + bit;
            }
        }
        blackBox(mix);
    }

    template <MutexType Mutex>
    void bench(usize nThreads, usize work, int watches, int nOps) {
        std::vector<std::unique_ptr<Op<Mutex>>> ops;
        ops.reserve(static_cast<usize>(nOps));
        for (int i = 0; i < nOps; ++i) {
            ops.push_back(std::make_unique<Op<Mutex>>(watches));
        }

        std::vector<std::thread> workers;
        workers.reserve(nThreads);
        for (usize t = 0; t < nThreads; ++t) {
            workers.emplace_back([&ops, work, watches]() {
                try {
                    for (usize round = 0; round < work; ++round) {
                        for (int key = 0; key < watches; ++key) {
                            // Op index order is the only lock order, so two handlers cannot deadlock.
                            for (auto& op : ops) {
                                onKey(*op, key);
                            }
                        }
                    }
                } catch (std::exception const& ex) {
                    std::cerr << "wideWatch worker: " << ex.what() << std::endl;
                    std::abort();
                }
            });
        }

        for (auto& worker : workers) {
            worker.join();
        }

        for (auto& op : ops) {
            for (int key = 0; key < watches; ++key) {
                if (op->seen[key].load(std::memory_order_relaxed) != 1) {
                    throw std::runtime_error("wideWatch missed a completion");
                }
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
    bool benchLocks(BenchParameters const& params, int watches, int nOps) {
        char const* lockName = locks::LockTraits<Mutex>::name;
        auto which = params.getWhich();
        bool ran = !which || *which == lockName;
        if (ran) {
            std::cout << "Bench: " << lockName << std::endl;
            writeBenchResult(params, lockName, measure([&params, watches, nOps]() {
                bench<Mutex>(params.getNThreads(), params.getWorkPerThread(), watches, nOps);
            }));
            params.coolOff();
        }

        if constexpr (sizeof...(Rest) > 0) {
            ran = benchLocks<Rest...>(params, watches, nOps) || ran;
        }
        return ran;
    }
} // namespace parallel_bench::wide_watch

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
    using namespace parallel_bench::wide_watch;

    try {
        BenchParameters params("c++", "wideWatch");
        int watches = envInt("PSWEET_WATCHES", 32);
        int nOps = envInt("PSWEET_OPS", 8);
        // Anderson locks and MCS ThreadLocal are sized N_THREADS_ALLOC. More
        // workers than that alias a slot and corrupt the handshake.
        if (params.getNThreads() == 0 || params.getNThreads() > N_THREADS_ALLOC) {
            throw std::runtime_error("PSWEET_NTHREADS must be in 1..N_THREADS_ALLOC");
        }
        if (params.getWorkPerThread() == 0 || watches <= 0 || nOps <= 0) {
            throw std::runtime_error("PSWEET_WORK_PER_THREAD, PSWEET_WATCHES, and PSWEET_OPS must be positive");
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
                              locks::TwoCounterLock>(params, watches, nOps);
        if (!ran) {
            throw std::runtime_error("PSWEET_WHICH does not name a lock: " + *params.getWhich());
        }
        return 0;
    } catch (std::exception const& ex) {
        std::cerr << ex.what() << std::endl;
        return 0xBAD;
    }
}
