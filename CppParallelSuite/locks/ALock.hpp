
#ifndef ALOCK_HPP
#define ALOCK_HPP

#include "../Types.hpp"
#include "../threadlocal/ThreadLocal.hpp"
#include "LockTraits.hpp"
#include <array>
#include <atomic>
#include <thread>

namespace parallel_suite::locks {
    using namespace threadlocal;

    template <usize ThreadCount>
    class ALock {
    private:
        ThreadLocal<usize, ThreadCount> mySlotIndex;
        std::atomic<usize> tail;
        std::array<std::atomic<bool>, ThreadCount> flags;

    public:
        ALock() : mySlotIndex(), tail(0), flags() {
            static_assert(ThreadCount > 0, "ThreadCount must be positive");

            for (int i = 0; i < ThreadCount; ++i) {
                flags[i] = (i == 0);
            }
        }

        void lock() {
            auto slot = tail.fetch_add(1) % ThreadCount;
            mySlotIndex.set(slot);
            while (!flags[slot]) {
                std::this_thread::yield();
            }
            flags[slot] = false;
        }

        void unlock() {
            auto slot = mySlotIndex.get();
            auto next = (slot + 1) % ThreadCount;
            flags[next] = true;
        }
    };

    template <usize ThreadCount>
    struct LockTraits<ALock<ThreadCount>> {
        constexpr static char const* name = "ALock";
    };
} // namespace parallel_suite::locks

#endif //ALOCK_HPP
