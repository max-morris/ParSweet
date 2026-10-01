
#ifndef OPTIMIZED_ALOCK_HPP
#define OPTIMIZED_ALOCK_HPP

#include "../Types.hpp"
#include "../threadlocal/ThreadLocal.hpp"
#include "LockTraits.hpp"
#include <array>
#include <atomic>
#include <thread>

namespace parallel_suite::locks {
    using namespace threadlocal;

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
        ThreadLocal<usize, ThreadCount> mySlotIndex;
        std::atomic<usize> tail;
        std::array<Cell<std::atomic<bool>>, ThreadCount> flags;

    public:
        OptimizedALock() : mySlotIndex(), tail(0), flags() {
            static_assert(ThreadCount > 0, "ThreadCount must be positive");

            for (int i = 0; i < ThreadCount; ++i) {
                *flags[i] = (i == 0);
            }
        }

        void lock() {
            auto slot = tail.fetch_add(1) % ThreadCount;
            mySlotIndex.set(slot);
            while (!*flags[slot]) {
                std::this_thread::yield();
            }
            *flags[slot] = false;
        }

        void unlock() {
            auto slot = mySlotIndex.get();
            auto next = (slot + 1) % ThreadCount;
            *flags[next] = true;
        }
    };

    template <usize ThreadCount>
    struct LockTraits<OptimizedALock<ThreadCount>> {
        constexpr static char const* name = "OptimizedALock";
    };
} // namespace parallel_suite::locks

#endif //OPTIMIZED_ALOCK_HPP
