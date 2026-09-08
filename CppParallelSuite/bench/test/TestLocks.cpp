#include "../../MutexType.hpp"
#include "../../locks/ALock.hpp"
#include "../../locks/BackoffLock.hpp"
#include "../../locks/CLHLock.hpp"
#include "../../locks/IdLock.hpp"
#include "../../locks/MCSLock.hpp"
#include "../../locks/OptimizedALock.hpp"
#include "../../locks/TASLock.hpp"
#include "../../locks/TIdLock.hpp"
#include "../../locks/TTASLock.hpp"
#include "../../locks/TwoCounterLock.hpp"
#include "TestEnv.hpp"
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

namespace parallel_test::locks {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    constexpr static int CountTo = 20000;
    constexpr static int RecursiveCountTo = 2000;

    template <MutexType M>
    bool testLock(int nThreads) {
        std::vector<std::thread> workers;

        usize counter = 0;
        M theMutex;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            workers.emplace_back([&]() {
                for (int c = 0; c < CountTo; ++c) {
                    std::lock_guard lock(theMutex);
                    ++counter;
                }
            });
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        return static_cast<usize>(nThreads) * CountTo == counter;
    }

    bool testRecursiveLock(int nThreads) {
        std::recursive_mutex theMutex;
        usize counter = 0;
        std::vector<std::thread> workers;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            workers.emplace_back([&]() {
                for (int c = 0; c < RecursiveCountTo; ++c) {
                    theMutex.lock();
                    theMutex.lock();
                    ++counter;
                    theMutex.unlock();
                    theMutex.unlock();
                }
            });
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        return static_cast<usize>(nThreads) * RecursiveCountTo == counter;
    }

    template <MutexType... Mutices>
    bool testLocksRepeatedly(int nThreads) {
        constexpr static int Tries = 30;
        bool ok = true;

        for (int t = 0; t < Tries; ++t) {
            ok = ok && (... && testLock<Mutices>(nThreads));
            if (!ok) {
                break;
            }
        }

        return ok;
    }
} // namespace parallel_test::locks

using namespace parallel_test::locks;

int main() {
    int nThreads = parallel_test::testNThreads(12);
    if (nThreads > static_cast<int>(N_THREADS_ALLOC)) {
        nThreads = static_cast<int>(N_THREADS_ALLOC);
    }

    bool ok = testLocksRepeatedly<
            std::mutex,
            std::recursive_mutex,
            TASLock,
            TTASLock,
            ALock<N_THREADS_ALLOC>,
            OptimizedALock<N_THREADS_ALLOC>,
            BackoffLock<>,
            CLHLock,
            MCSLock,
            IdLock,
            TIdLock,
            TwoCounterLock>(nThreads);

    ok = testRecursiveLock(nThreads) && ok;

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }

    return ok ? 0
              : 0xBAD;
}
