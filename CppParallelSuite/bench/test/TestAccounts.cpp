#include "../../accounts/AccountLedger.hpp"
#include "../../locks/TASLock.hpp"
#include "../../locks/TwoCounterLock.hpp"
#include "TestEnv.hpp"
#include <iostream>
#include <mutex>
#include <random>
#include <thread>
#include <vector>

template <parallel_suite::MutexType Mutex>
bool runLedger(int nThreads, int workSize) {
    constexpr int nAccounts = 16;
    constexpr long initial = 100;
    parallel_suite::accounts::AccountLedger<Mutex> ledger(nAccounts, initial);

    std::vector<std::thread> workers;
    for (int t = 0; t < nThreads; ++t) {
        workers.emplace_back([&, t]() {
            std::mt19937 rng(static_cast<unsigned>(t + 1) * 2654435761u);
            std::uniform_int_distribution<int> acc(0, nAccounts - 1);
            std::uniform_int_distribution<int> amt(1, 25);
            for (int i = 0; i < workSize; ++i) {
                ledger.transfer(acc(rng), acc(rng), amt(rng));
            }
        });
    }
    for (auto&& w : workers) {
        w.join();
    }

    return ledger.total() == nAccounts * initial && ledger.noNegatives();
}

int main() {
    int nThreads = parallel_test::testNThreads(12);
    int workSize = 500;
    bool ok = runLedger<std::mutex>(nThreads, workSize);
    ok = runLedger<parallel_suite::locks::TASLock>(nThreads, workSize) && ok;
    ok = runLedger<parallel_suite::locks::TwoCounterLock>(nThreads, workSize) && ok;

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
