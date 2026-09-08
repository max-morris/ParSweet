#include "../buffers/BoundedBuffer.hpp"
#include "Bench.hpp"
#include "BlackBox.hpp"
#include <thread>
#include <vector>

int main() {
    using parallel_suite::buffers::BoundedBuffer;
    parallel_bench::BenchParameters params("c++", "buffer");
    if (params.getWhich() && *params.getWhich() != "BoundedBuffer") {
        return 0;
    }

    auto ms = parallel_bench::measure([&params]() {
        BoundedBuffer<int> buf(32);
        auto nThreads = static_cast<int>(params.getNThreads());
        auto workSize = static_cast<int>(params.getWorkPerThread());
        std::vector<std::thread> producers;
        std::vector<std::thread> consumers;
        for (int t = 0; t < nThreads; ++t) {
            producers.emplace_back([&, t]() {
                for (int i = 0; i < workSize; ++i) {
                    buf.put(t * workSize + i);
                }
            });
            consumers.emplace_back([&]() {
                for (int i = 0; i < workSize; ++i) {
                    parallel_bench::blackBox(buf.take());
                }
            });
        }
        for (auto&& p : producers) {
            p.join();
        }
        for (auto&& c : consumers) {
            c.join();
        }
    });
    std::cout << "Bench: BoundedBuffer" << std::endl;
    parallel_bench::writeBenchResult(params, "BoundedBuffer", ms);
    return 0;
}
