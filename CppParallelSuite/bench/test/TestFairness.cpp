#include "../../MutexType.hpp"
#include "../../locks/ALock.hpp"
#include "../../locks/CLHLock.hpp"
#include "../../locks/MCSLock.hpp"
#include "../../locks/OptimizedALock.hpp"
#include "../../locks/TASLock.hpp"
#include "../../locks/TwoCounterLock.hpp"
#include "TestEnv.hpp"
#include <algorithm>
#include <iostream>
#include <thread>
#include <vector>

namespace {
    constexpr int Work = 1000;

    template <parallel_suite::MutexType M>
    std::vector<int> counts(int nThreads) {
        M mutex;
        std::vector<int> c(nThreads, 0);
        std::vector<std::thread> workers;
        for (int t = 0; t < nThreads; ++t) {
            workers.emplace_back([&, t]() {
                for (int i = 0; i < Work; ++i) {
                    std::lock_guard lock(mutex);
                    ++c[t];
                }
            });
        }
        for (auto&& w : workers) {
            w.join();
        }
        return c;
    }

    bool checkFair(std::vector<int> const& c, char const* name, bool requireFair) {
        auto mn = *std::min_element(c.begin(), c.end());
        auto mx = *std::max_element(c.begin(), c.end());
        std::cout << name << " min=" << mn << " max=" << mx << std::endl;
        if (mn == 0) {
            return !requireFair;
        }
        if (requireFair && mx > 4 * mn) {
            std::cout << name << " failed fairness bound" << std::endl;
            return false;
        }
        return true;
    }
} // namespace

int main() {
    using namespace parallel_suite::locks;
    int nThreads = parallel_test::testNThreads(12);
    if (nThreads > static_cast<int>(N_THREADS_ALLOC)) {
        nThreads = static_cast<int>(N_THREADS_ALLOC);
    }

    bool ok = true;
    ok = checkFair(counts<ALock<N_THREADS_ALLOC>>(nThreads), "ALock", true) && ok;
    ok = checkFair(counts<OptimizedALock<N_THREADS_ALLOC>>(nThreads), "OptimizedALock", true) && ok;
    ok = checkFair(counts<CLHLock>(nThreads), "CLHLock", true) && ok;
    ok = checkFair(counts<MCSLock>(nThreads), "MCSLock", true) && ok;
    ok = checkFair(counts<TwoCounterLock>(nThreads), "TwoCounterLock", true) && ok;
    checkFair(counts<TASLock>(nThreads), "TASLock", false);

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
