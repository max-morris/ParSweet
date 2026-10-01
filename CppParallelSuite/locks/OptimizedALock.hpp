
#ifndef OPTIMIZED_ALOCK_HPP
#define OPTIMIZED_ALOCK_HPP

#include "../Types.hpp"
#include "AndersonLock.hpp"
#include "LockTraits.hpp"
#include <new>

namespace parallel_suite::locks {
    // Padded Anderson lock matching Java OptimizedALock; see
    // detail::AndersonLock. Each flag is aligned to
    // hardware_destructive_interference_size, giving every slot its own
    // cache line.
    template <usize ThreadCount>
    class OptimizedALock : public detail::AndersonLock<ThreadCount, std::hardware_destructive_interference_size> {};

    template <usize ThreadCount>
    struct LockTraits<OptimizedALock<ThreadCount>> {
        constexpr static char const* name = "OptimizedALock";
    };
} // namespace parallel_suite::locks

#endif //OPTIMIZED_ALOCK_HPP
