
#ifndef LOCK_TRAITS_HPP
#define LOCK_TRAITS_HPP

#include "../MutexType.hpp"
#include "../Types.hpp"
#include <array>
#include <string>
#include <vector>

namespace parallel_suite::locks {
    template <MutexType L>
    struct LockTraits {
        constexpr static char const* name = nullptr;
    };

    // A string literal that can be passed as a template argument.
    template <usize N>
    struct NameLiteral {
        char text[N]{};

        constexpr NameLiteral(char const (&literal)[N]) {
            for (usize i = 0; i < N; ++i) {
                text[i] = literal[i];
            }
        }
    };

    // The name of a lock whose template arguments set it apart, built at
    // compile time: Prefix<A> or Prefix<A:B> (e.g. "BackoffLock<1:17>"), the
    // form BenchLocks uses. The arguments must be non-negative integers.
    template <NameLiteral Prefix, auto... Args>
    struct TemplatedLockName {
    private:
        static constexpr usize digits(usize n) {
            usize count = 1;
            for (; n >= 10; n /= 10) {
                ++count;
            }
            return count;
        }

        // The prefix without its null terminator, '<', the arguments with ':'
        // between them, '>', and a null terminator.
        static constexpr usize length = (sizeof(Prefix.text) - 1) + 1 + (digits(static_cast<usize>(Args)) + ... + 0) +
                                        (sizeof...(Args) - 1) + 1 + 1;

        static constexpr std::array<char, length> build() {
            static_assert(sizeof...(Args) > 0, "a templated lock name needs at least one argument");
            static_assert(((Args >= 0) && ...), "lock name arguments must be non-negative");
            std::array<char, length> out{};
            usize at = 0;
            for (usize i = 0; i + 1 < sizeof(Prefix.text); ++i) {
                out[at++] = Prefix.text[i];
            }
            out[at++] = '<';
            bool first = true;
            for (usize arg : {static_cast<usize>(Args)...}) {
                if (!first) {
                    out[at++] = ':';
                }
                first = false;
                usize const n = digits(arg);
                for (usize i = 0; i < n; ++i) {
                    out[at + n - 1 - i] = static_cast<char>('0' + arg % 10);
                    arg /= 10;
                }
                at += n;
            }
            out[at++] = '>';
            out[at] = '\0';
            return out;
        }

        static constexpr std::array<char, length> text = build();

    public:
        static constexpr char const* value = text.data();
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
