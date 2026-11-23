
#ifndef BACKOFF_LOCK_HPP
#define BACKOFF_LOCK_HPP

#include "../threadlocal/ThreadLocalRand.hpp"
#include "LockTraits.hpp"
#include <atomic>
#include <chrono>
#include <thread>

namespace parallel_suite::locks {

    template <int MinDelay = 1, int MaxDelay = 17>
    class BackoffLock {
    private:
        std::atomic<bool> aBool;

    public:
        BackoffLock() : aBool(false) {}

        void lock() {
            int delayLimit = MinDelay;

            for (;;) {
                while (aBool.load()) {
                    std::this_thread::yield();
                }

                if (!aBool.exchange(true)) {
                    return;
                } else {
                    auto delay = std::chrono::milliseconds(parallel_suite::threadlocal::ThreadLocalRand::intRand.getRand(0, delayLimit));
                    delayLimit = std::min(MaxDelay, 2 * delayLimit);
                    std::this_thread::sleep_for(delay);
                }
            }
        }

        void unlock() {
            aBool.store(false);
        }
    };

    template <int MinDelay, int MaxDelay>
    struct LockTraits<locks::BackoffLock<MinDelay, MaxDelay>> {
        constexpr static char const* name = TemplatedLockName<"BackoffLock", MinDelay, MaxDelay>::value;
    };
} // namespace parallel_suite::locks

#endif //BACKOFF_LOCK_HPP
