#ifndef TEST_ENV_HPP
#define TEST_ENV_HPP

#include <cstdlib>
#include <string>

namespace parallel_test {

    inline int testNThreads(int defaultN = 12) {
        if (auto* env = std::getenv("PSWEET_NTHREADS")) {
            auto n = std::atoi(env);
            if (n > 0) {
                return n;
            }
        }
        return defaultN;
    }

} // namespace parallel_test

#endif // TEST_ENV_HPP
