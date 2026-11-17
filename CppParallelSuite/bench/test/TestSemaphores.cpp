#include "../../MutexType.hpp"
#include "../../Types.hpp"
#include "../../locks/TwoCounterSemaphore.hpp"
#include <algorithm>
#include <atomic>
#include <iostream>
#include <limits>
#include <mutex>
#include <thread>
#include <vector>

namespace parallel_test::semaphores {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    // Largest permit count tested, in main().
    constexpr static int MaxPermits = 8;

    // Use more threads than the largest permit count, even on small
    // machines; otherwise the holder bound could never be exceeded and its
    // test would pass vacuously.
    constexpr static int Threads = std::max(N_LOGICAL_CORES, 2 * MaxPermits);
    constexpr static int CountTo = 20000;

    // Each test runs from ticket 0 and from just below the point where the
    // ticket counters wrap to 0. Counters that wrap must be compared by
    // signed distance; comparing raw values (e.g.
    // ticket >= currentTicket + Permits) misbehaves across the wrap. In the
    // stress test it admits too many holders (caught as overflow) and then
    // hangs once currentTicket + Permits wraps but ticket has not; the
    // admits test hangs on its first lock(). The CTest timeout turns a hang
    // into a failure.
    constexpr static usize MaxTicket = std::numeric_limits<usize>::max();

    // Starting the stress test this far below the wrap point puts the wrap
    // early in the run. The workers' start gate makes all threads contend
    // by then.
    constexpr static usize StressWrapLead = 1000;

    // One thread takes all Permits permits before releasing any. A
    // semaphore that admits fewer than Permits holders hangs here instead of
    // returning. Starting at MaxTicket - Permits / 2 makes
    // currentTicket + Permits wrap on the first lock(), and the tickets
    // themselves wrap partway through (for Permits >= 3).
    template <template <usize> typename S, usize Permits>
    bool testAdmitsPermits(usize startTicket) {
        S<Permits> theSemaphore(startTicket);

        for (usize p = 0; p < Permits; ++p) {
            theSemaphore.lock();
        }

        for (usize p = 0; p < Permits; ++p) {
            theSemaphore.unlock();
        }

        return true;
    }

    // Each holder counts itself in and out of `inside` and flags `overflow`
    // if it ever sees more than Permits holders at once. The yield widens the
    // window in which holders overlap. Each worker first waits at a start
    // gate until all Threads workers have started, so the run (including a
    // wrap early in it) is contended from the first lock().
    template <MutexType S, usize Permits>
    void work(std::atomic<int>& ready, std::atomic<usize>& inside, std::atomic<bool>& overflow, S& theSemaphore) {
        ready.fetch_add(1);
        while (ready.load() < Threads) {
            std::this_thread::yield();
        }

        for (int c = 0; c < CountTo; ++c) {
            std::lock_guard lock(theSemaphore);

            if (inside.fetch_add(1) + 1 > Permits) {
                overflow.store(true);
            }

            std::this_thread::yield();
            inside.fetch_sub(1);
        }
    }

    template <template <usize> typename S, usize Permits>
    bool testBoundsHolders(usize startTicket) {
        std::vector<std::thread> workers;

        std::atomic<int> ready(0);
        std::atomic<usize> inside(0);
        std::atomic<bool> overflow(false);
        S<Permits> theSemaphore(startTicket);

        for (int threadId = 0; threadId < Threads; ++threadId) {
            workers.emplace_back(work<S<Permits>, Permits>, std::ref(ready), std::ref(inside), std::ref(overflow), std::ref(theSemaphore));
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        return !overflow.load();
    }

    template <template <usize> typename S, usize Permits>
    bool testSemaphore() {
        return testAdmitsPermits<S, Permits>(0) &&
               testAdmitsPermits<S, Permits>(MaxTicket - Permits / 2) &&
               testBoundsHolders<S, Permits>(0) &&
               testBoundsHolders<S, Permits>(MaxTicket - StressWrapLead);
    }
} // namespace parallel_test::semaphores

using namespace parallel_test::semaphores;

template <template <usize> typename S, usize... Permits>
bool testSemaphoresRepeatedly() {
    constexpr static int Tries = 10;
    bool ok = true;

    for (int t = 0; t < Tries; ++t) {
        ok = ok && (... && testSemaphore<S, Permits>());
    }

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }

    return ok;
}

int main() {
    bool ok = testSemaphoresRepeatedly<TwoCounterSemaphore, 1, 2, 3, MaxPermits>();

    return ok ? 0
              : 0xBAD;
}
