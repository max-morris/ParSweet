
#ifndef ANDERSON_LOCK_HPP
#define ANDERSON_LOCK_HPP

#include "../Types.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <thread>

namespace parallel_suite::locks::detail {
    // Anderson array lock shared by ALock and OptimizedALock, matching Java
    // AndersonLock. The two differ only in CellAlign, the alignment of each
    // slot flag: alignof(std::atomic<bool>) packs the flags densely, so
    // neighboring slots false-share; hardware_destructive_interference_size
    // gives each slot its own cache line.
    template <usize ThreadCount, usize CellAlign>
    class AndersonLock {
        // A T aligned to at least CellAlign. alignas may not weaken T's own
        // alignment, hence the max.
        template <typename T>
        struct alignas(std::max(CellAlign, alignof(T))) Cell {
            T data;

            Cell() : data() {}

            T& operator*() {
                return data;
            }
        };

    private:
        std::atomic<usize> tail;
        std::array<Cell<std::atomic<bool>>, ThreadCount> flags;
        // Slot of the thread that holds the lock; when padded, on its own
        // line because it is written on every acquisition. Only the holder
        // touches it: written after acquiring, read in unlock() before the
        // release. The flag hand-off (seq_cst store, then load by the next
        // holder) orders each holder's accesses before the next's, so a
        // plain member is race-free. A ThreadLocal<usize, ThreadCount>
        // indexed by ThreadId % ThreadCount could let two threads share an
        // entry.
        Cell<usize> heldSlot;

    public:
        AndersonLock() : tail(0), flags(), heldSlot() {
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
} // namespace parallel_suite::locks::detail

#endif //ANDERSON_LOCK_HPP
