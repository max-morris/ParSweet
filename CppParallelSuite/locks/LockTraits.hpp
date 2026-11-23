
#ifndef LOCK_TRAITS_HPP
#define LOCK_TRAITS_HPP

#include "../MutexType.hpp"
#include <string>
#include <vector>

namespace parallel_suite::locks {
    template <MutexType L>
    struct LockTraits {
        constexpr static char const* name = nullptr;
    };

    // A list of lock types, so that one list can drive both a benchmark's
    // loop over its locks and the lock names it accepts in PSWEET_WHICH.
    template <MutexType... Locks>
    struct LockList {
        // The LockTraits names of the locks, in order.
        static std::vector<std::string> names() {
            return {LockTraits<Locks>::name...};
        }
    };
} // namespace parallel_suite::locks

#endif //LOCK_TRAITS_HPP
