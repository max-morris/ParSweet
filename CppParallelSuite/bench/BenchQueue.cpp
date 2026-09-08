#include "../queues/TwoLockQueue.hpp"
#include "Bench.hpp"
#include "BlackBox.hpp"
#include <atomic>
#include <thread>
#include <vector>

int main() {
    using parallel_suite::queues::TwoLockQueue;
    parallel_bench::BenchParameters params("c++", "queue");
    if (params.getWhich() && *params.getWhich() != "TwoLockQueue") {
        return 0;
    }

    auto ms = parallel_bench::measure([&params]() {
        TwoLockQueue<int> q;
        auto nThreads = static_cast<int>(params.getNThreads());
        auto workSize = static_cast<int>(params.getWorkPerThread());
        std::atomic<bool> producing{true};
        std::vector<std::thread> producers;
        std::vector<std::thread> consumers;
        for (int t = 0; t < nThreads; ++t) {
            producers.emplace_back([&, t]() {
                for (int i = 0; i < workSize; ++i) {
                    q.enqueue(t * workSize + i);
                }
            });
            consumers.emplace_back([&]() {
                for (;;) {
                    auto v = q.tryDequeue();
                    if (v) {
                        parallel_bench::blackBox(*v);
                    } else if (!producing.load()) {
                        v = q.tryDequeue();
                        if (!v) {
                            break;
                        }
                        parallel_bench::blackBox(*v);
                    } else {
                        std::this_thread::yield();
                    }
                }
            });
        }
        for (auto&& p : producers) {
            p.join();
        }
        producing.store(false);
        for (auto&& c : consumers) {
            c.join();
        }
    });
    std::cout << "Bench: TwoLockQueue" << std::endl;
    parallel_bench::writeBenchResult(params, "TwoLockQueue", ms);
    return 0;
}
