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
#include "../../locks/TwoCounterSemaphore.hpp"
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

namespace parallel_test::locks {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    constexpr static int Threads = N_LOGICAL_CORES;
    constexpr static int CountTo = 20000;

    template <MutexType M>
    void work(int threadId, usize& counter, M& theMutex) {
        for (int c = 0; c < CountTo; ++c) {
            std::lock_guard lock(theMutex);
            ++counter;
        }
    }

    template <MutexType M>
    bool testLock() {
        std::vector<std::thread> workers;

        usize counter = 0;
        M theMutex;

        for (int threadId = 0; threadId < Threads; ++threadId) {
            workers.emplace_back(work<M>, threadId, std::ref(counter), std::ref(theMutex));
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        auto target = Threads * CountTo;

        return target == counter;
    }
} // namespace parallel_test::locks

using namespace parallel_test::locks;

template <MutexType... Mutices>
bool testLocksRepeatedly() {
    constexpr static int Tries = 30;
    bool ok = true;

    for (int t = 0; t < Tries; ++t) {
        ok = ok && (... && testLock<Mutices>());
    }

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }

    return ok;
}

int main() {
    bool ok = testLocksRepeatedly<
            std::mutex,
            std::recursive_mutex,
            TASLock,
            TTASLock,
            ALock<Threads>,
            OptimizedALock<Threads>,
            BackoffLock<>,
            CLHLock,
            MCSLock,
            IdLock,
            TIdLock,
            TwoCounterLock,
            TwoCounterSemaphore<1>>();

    return ok ? 0
              : 0xBAD;
}
