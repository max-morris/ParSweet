
#ifndef OPTIMIZED_ALOCK_HPP
#define OPTIMIZED_ALOCK_HPP

#include "../Types.hpp"
#include "LockTraits.hpp"
#include <array>
#include <atomic>
#include <thread>

namespace parallel_suite::locks {
    template <usize ThreadCount>
    class OptimizedALock {
        template <typename T>
        struct alignas(std::hardware_destructive_interference_size) Cell {
            T data;

            Cell() : data() {}

            T& operator*() {
                return data;
            }
        };

    private:
        std::atomic<usize> tail;
        std::array<Cell<std::atomic<bool>>, ThreadCount> flags;
        // Slot of the thread that holds the lock, on its own line because it
        // is written on every acquisition. Only the holder touches it:
        // written after acquiring, read in unlock() before the release. The
        // flag hand-off (seq_cst store, then load by the next holder) orders
        // each holder's accesses before the next's, so a plain member is
        // race-free. A ThreadLocal<usize, ThreadCount> indexed by
        // ThreadId % ThreadCount could let two threads share an entry.
        Cell<usize> heldSlot;

    public:
        OptimizedALock() : tail(0), flags(), heldSlot() {
            static_assert(ThreadCount > 0, "ThreadCount must be positive");

            for (int i = 0; i < ThreadCount; ++i) {
                *flags[i] = (i == 0);
            }
        }

        void lock() {
            auto slot = tail.fetch_add(1) % ThreadCount;
            while (!*flags[slot]) {
                std::this_thread::yield();
            }
            *flags[slot] = false;
            *heldSlot = slot;
        }

        void unlock() {
            auto next = (*heldSlot + 1) % ThreadCount;
            *flags[next] = true;
        }
    };

    template <usize ThreadCount>
    struct LockTraits<OptimizedALock<ThreadCount>> {
        constexpr static char const* name = "OptimizedALock";
    };
} // namespace parallel_suite::locks

#endif //OPTIMIZED_ALOCK_HPP
