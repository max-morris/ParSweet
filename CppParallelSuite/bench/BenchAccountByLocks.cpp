#include "../accounts/AccountLedger.hpp"
#include "../locks/ALock.hpp"
#include "../locks/BackoffLock.hpp"
#include "../locks/CLHLock.hpp"
#include "../locks/IdLock.hpp"
#include "../locks/LockTraits.hpp"
#include "../locks/MCSLock.hpp"
#include "../locks/OptimizedALock.hpp"
#include "../locks/TASLock.hpp"
#include "../locks/TIdLock.hpp"
#include "../locks/TTASLock.hpp"
#include "../locks/TwoCounterLock.hpp"
#include "Bench.hpp"
#include "BlackBox.hpp"
#include <mutex>
#include <random>
#include <thread>
#include <vector>

namespace parallel_suite::locks {
    template <>
    struct LockTraits<std::mutex> {
        constexpr static char const* name = "std::mutex";
    };

    template <>
    struct LockTraits<std::recursive_mutex> {
        constexpr static char const* name = "std::recursive_mutex";
    };
} // namespace parallel_suite::locks

template <parallel_suite::MutexType Mutex>
void benchAccounts(parallel_bench::BenchParameters const& params) {
    using parallel_suite::accounts::AccountLedger;
    auto nThreads = static_cast<int>(params.getNThreads());
    auto workSize = static_cast<int>(params.getWorkPerThread());
    AccountLedger<Mutex> ledger(16, 100);
    std::vector<std::thread> workers;
    for (int t = 0; t < nThreads; ++t) {
        workers.emplace_back([&, t]() {
            std::mt19937 rng(static_cast<unsigned>(t + 1));
            std::uniform_int_distribution<int> acc(0, 15);
            std::uniform_int_distribution<int> amt(1, 25);
            for (int i = 0; i < workSize; ++i) {
                ledger.transfer(acc(rng), acc(rng), amt(rng));
            }
        });
    }
    for (auto&& w : workers) {
        w.join();
    }
    parallel_bench::blackBox(ledger.total());
}

template <parallel_suite::MutexType Mutex, typename... Rest>
void benchAll(parallel_bench::BenchParameters const& params) {
    auto name = parallel_suite::locks::LockTraits<Mutex>::name;
    std::string specific = std::string("AccountLedger@") + name;
    if (!params.getWhich() || *params.getWhich() == name) {
        std::cout << "Bench: " << specific << std::endl;
        parallel_bench::writeBenchResult(params, specific, parallel_bench::measure([&]() {
            benchAccounts<Mutex>(params);
        }));
        params.coolOff();
    }
    if constexpr (sizeof...(Rest) > 0) {
        benchAll<Rest...>(params);
    }
}

int main() {
    using namespace parallel_suite::locks;
    parallel_bench::BenchParameters params("c++", "accountByLocks");
    benchAll<std::mutex,
             std::recursive_mutex,
             ALock<N_THREADS_ALLOC>,
             BackoffLock<1, 17>,
             CLHLock,
             IdLock,
             MCSLock,
             OptimizedALock<N_THREADS_ALLOC>,
             TASLock,
             TIdLock,
             TTASLock,
             TwoCounterLock>(params);
    return 0;
}
