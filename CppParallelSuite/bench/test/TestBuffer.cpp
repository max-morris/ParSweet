#include "../../buffers/BoundedBuffer.hpp"
#include "TestEnv.hpp"
#include <algorithm>
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

int main() {
    using parallel_suite::buffers::BoundedBuffer;
    int nThreads = parallel_test::testNThreads(12);
    int workSize = 200;
    BoundedBuffer<int> buf(8);

    std::vector<int> got;
    std::mutex gotMutex;
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
                auto v = buf.take();
                std::lock_guard lock(gotMutex);
                got.push_back(v);
            }
        });
    }

    for (auto&& p : producers) {
        p.join();
    }
    for (auto&& c : consumers) {
        c.join();
    }

    std::sort(got.begin(), got.end());
    bool ok = static_cast<int>(got.size()) == nThreads * workSize;
    for (int i = 0; ok && i < nThreads * workSize; ++i) {
        ok = got[i] == i;
    }

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
