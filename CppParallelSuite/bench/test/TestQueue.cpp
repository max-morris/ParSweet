#include "../../queues/TwoLockQueue.hpp"
#include "TestEnv.hpp"
#include <algorithm>
#include <atomic>
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

int main() {
    using parallel_suite::queues::TwoLockQueue;
    int nThreads = parallel_test::testNThreads(12);
    int workSize = 200;
    TwoLockQueue<int> q;
    std::atomic<bool> producing{true};
    std::vector<int> got;
    std::mutex gotMutex;

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
                    std::lock_guard lock(gotMutex);
                    got.push_back(*v);
                } else if (!producing.load()) {
                    v = q.tryDequeue();
                    if (!v) {
                        break;
                    }
                    std::lock_guard lock(gotMutex);
                    got.push_back(*v);
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
