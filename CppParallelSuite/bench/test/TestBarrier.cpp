#include "../../barrier/Barrier.hpp"
#include "TestEnv.hpp"
#include <atomic>
#include <chrono>
#include <iostream>
#include <random>
#include <thread>
#include <vector>

namespace parallel_test::barrier {
    using namespace parallel_suite::barrier;

    constexpr int Phases = 10;

    bool runBarrierTest(int nThreads) {
        Barrier barrier(nThreads);
        std::vector<std::atomic<int>> observed(nThreads);
        for (auto& o : observed) {
            o.store(-1);
        }
        std::atomic<bool> failed{false};
        std::vector<std::thread> workers;

        for (int i = 0; i < nThreads; ++i) {
            workers.emplace_back([&, i]() {
                std::mt19937 rng(static_cast<unsigned>(i + 1));
                std::uniform_int_distribution<int> dist(0, 5);

                for (int t = 0; t < Phases; ++t) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(dist(rng)));
                    observed[i].store(t);
                    barrier.sync();
                    for (int j = 0; j < nThreads; ++j) {
                        if (observed[j].load() != t) {
                            failed.store(true);
                        }
                    }
                    barrier.sync();
                }
            });
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        if (failed.load()) {
            return false;
        }
        for (int i = 0; i < nThreads; ++i) {
            if (observed[i].load() != Phases - 1) {
                return false;
            }
        }
        return true;
    }
} // namespace parallel_test::barrier

using namespace parallel_test::barrier;

int main() {
    int nThreads = parallel_test::testNThreads(12);
    bool ok = runBarrierTest(nThreads);
    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
