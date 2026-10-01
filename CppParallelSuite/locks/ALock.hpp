
#ifndef ALOCK_HPP
#define ALOCK_HPP

#include "../Types.hpp"
#include "LockTraits.hpp"
#include <array>
#include <atomic>
#include <thread>

namespace parallel_suite::locks {
    template <usize ThreadCount>
    class ALock {
    private:
        std::atomic<usize> tail;
        std::array<std::atomic<bool>, ThreadCount> flags;
        // Slot of the thread that holds the lock. Only the holder touches it:
        // written after acquiring, read in unlock() before the release. The
        // flag hand-off (seq_cst store, then load by the next holder) orders
        // each holder's accesses before the next's, so a plain member is
        // race-free. A ThreadLocal<usize, ThreadCount> indexed by
        // ThreadId % ThreadCount could let two threads share an entry.
        usize heldSlot;

    public:
        ALock() : tail(0), flags(), heldSlot(0) {
            static_assert(ThreadCount > 0, "ThreadCount must be positive");

            for (int i = 0; i < ThreadCount; ++i) {
                flags[i] = (i == 0);
            }
        }

        void lock() {
            auto slot = tail.fetch_add(1) % ThreadCount;
            while (!flags[slot]) {
                std::this_thread::yield();
            }
            flags[slot] = false;
            heldSlot = slot;
        }

        void unlock() {
            auto next = (heldSlot + 1) % ThreadCount;
            flags[next] = true;
        }
    };

    template <usize ThreadCount>
    struct LockTraits<ALock<ThreadCount>> {
        constexpr static char const* name = "ALock";
    };
} // namespace parallel_suite::locks

#endif //ALOCK_HPP
