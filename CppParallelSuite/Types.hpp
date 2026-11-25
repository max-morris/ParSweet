
#ifndef TYPES_HPP
#define TYPES_HPP

#include <cstddef>
#include <type_traits>

using usize = std::size_t;
// Signed counterpart of usize, e.g. for wrap-safe distances between counters.
using isize = std::make_signed_t<usize>;

#endif // TYPES_HPP
