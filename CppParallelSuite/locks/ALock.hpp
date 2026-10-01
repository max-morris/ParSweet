
#ifndef ALOCK_HPP
#define ALOCK_HPP

#include "../Types.hpp"
#include "AndersonLock.hpp"
#include "LockTraits.hpp"
#include <atomic>

namespace parallel_suite::locks {
    // Unpadded Anderson lock matching Java ALock; see detail::AndersonLock.
    // Flags are packed one std::atomic<bool> apart, so neighboring slots
    // share cache lines.
    template <usize ThreadCount>
    class ALock : public detail::AndersonLock<ThreadCount, alignof(std::atomic<bool>)> {};

    template <usize ThreadCount>
    struct LockTraits<ALock<ThreadCount>> {
        constexpr static char const* name = "ALock";
    };
} // namespace parallel_suite::locks

#endif //ALOCK_HPP
