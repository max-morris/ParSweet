#include "Bench.hpp"

#include <functional>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#if HAVE_HPX
#include <hpx/hpx.hpp>
#include <hpx/hpx_init.hpp>
#endif
#include "../MutexType.hpp"
#include "../locks/ALock.hpp"
#include "../locks/BackoffLock.hpp"
#include "../locks/CLHLock.hpp"
#include "../locks/IdLock.hpp"
#include "../locks/MCSLock.hpp"
#include "../locks/OptimizedALock.hpp"
#include "../locks/TASLock.hpp"
#include "../locks/TIdLock.hpp"
#include "../locks/TTASLock.hpp"
#include "../locks/TwoCounterLock.hpp"
#include "../locks/TwoCounterSemaphore.hpp"
#include "BlackBox.hpp"

namespace parallel_bench::locks {
    using namespace parallel_suite;
    using namespace parallel_suite::locks;

    template <MutexType M>
    void work(usize const& countTo, usize& counter, M& theMutex) {
        for (int c = 0; c < countTo; ++c) {
            std::lock_guard lock(theMutex);
            ++counter;
        }
    }

    template <MutexType M>
    void benchLock(usize const& nThreads, usize const& countTo) {
        std::vector<std::thread> workers;

        usize counter = 0;
        M theMutex;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            workers.emplace_back(work<M>, std::cref(countTo), std::ref(counter), std::ref(theMutex));
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        blackBox(counter);
    }

#if HAVE_HPX
    template <MutexType M>
    void benchHpxLock(usize const& nThreads, usize const& countTo) {
        std::vector<hpx::thread> workers;

        usize counter = 0;
        M theMutex;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            workers.emplace_back(work<M>, std::cref(countTo), std::ref(counter), std::ref(theMutex));
        }

        for (auto&& worker : workers) {
            worker.join();
        }

        blackBox(counter);
    }
#endif

} // namespace parallel_bench::locks


using namespace parallel_bench;
using namespace parallel_bench::locks;

template <typename L>
constexpr auto benchLockWrapper() {
    return [](BenchParameters const& params) {
        benchLock<L>(params.getNThreads(), params.getWorkPerThread());
    };
}

#if HAVE_HPX
template <typename L>
constexpr auto benchHpxLockWrapper() {
    return [](BenchParameters const& params) {
        benchHpxLock<L>(params.getNThreads(), params.getWorkPerThread());
    };
}
#endif

std::unordered_map<std::string, std::function<void(BenchParameters const&)>> tests{
        {"ALock", benchLockWrapper<ALock<N_THREADS_ALLOC>>()},
        {"OptimizedALock", benchLockWrapper<OptimizedALock<N_THREADS_ALLOC>>()},
        {"BackoffLock<1:17>", benchLockWrapper<BackoffLock<1, 17>>()},
        {"IdLock", benchLockWrapper<IdLock>()},
        {"TIdLock", benchLockWrapper<TIdLock>()},
        {"CLHLock", benchLockWrapper<CLHLock>()},
        {"MCSLock", benchLockWrapper<MCSLock>()},
        {"TASLock", benchLockWrapper<TASLock>()},
        {"TTASLock", benchLockWrapper<TTASLock>()},
        {"TwoCounterLock", benchLockWrapper<TwoCounterLock>()},
        {"TwoCounterSemaphore<1>", benchLockWrapper<TwoCounterSemaphore<1>>()},
        {"std::mutex", benchLockWrapper<std::mutex>()},
        {"std::recursive_mutex", benchLockWrapper<std::recursive_mutex>()}};

#if HAVE_HPX
// The locks behind HAVE_HPX_TWO_COUNTER are added with emplace() rather than
// in the initializer list, where #if lines would confuse clang-format.
std::unordered_map<std::string, std::function<void(BenchParameters const&)>> makeHpxTests() {
    std::unordered_map<std::string, std::function<void(BenchParameters const&)>> hpxTests{
            {"hpx::mutex", benchHpxLockWrapper<hpx::mutex>()},
            {"hpx::spinlock", benchHpxLockWrapper<hpx::spinlock>()}};
#if HAVE_HPX_TWO_COUNTER
    hpxTests.emplace("hpx::two_counter_lock", benchHpxLockWrapper<hpx::two_counter_lock>());
    hpxTests.emplace("hpx::two_counter_semaphore<1>", benchHpxLockWrapper<hpx::two_counter_semaphore<1>>());
#endif
    return hpxTests;
}

std::unordered_map<std::string, std::function<void(BenchParameters const&)>> hpxTests = makeHpxTests();

int hpx_main() {
    BenchParameters params("c++", "locks");
    params.setHpxThreads(hpx::get_os_thread_count());

    // main() starts HPX only if PSWEET_WHICH is absent or names an HPX lock.
    auto which = params.getWhich();
    if (which) {
        std::cout << "(HPX) Bench: " << *which << std::endl;
        writeBenchResult(params, *which, measure(hpxTests.at(*which), params));
        params.coolOff();
    } else {
        for (auto& pair : hpxTests) {
            std::cout << "(HPX) Bench: " << pair.first << std::endl;
            writeBenchResult(params, pair.first, measure(pair.second, params));
            params.coolOff();
        }
    }

    return hpx::finalize();
}
#endif

int main() {
    BenchParameters params("c++", "locks");

    // The names of a table's tests, which checkWhich() accepts.
    auto keys = [](auto const& table) {
        std::vector<std::string> names;
        for (auto const& pair : table) {
            names.push_back(pair.first);
        }
        return names;
    };
#if HAVE_HPX
    bool const startHpx = checkWhich(params, "BenchLocks", keys(tests), keys(hpxTests));
#else
    checkWhich(params, "BenchLocks", keys(tests), {});
#endif

    auto which = params.getWhich();
    if (which && tests.contains(*which)) {
        std::cout << "Bench: " << *which << std::endl;
        writeBenchResult(params, *which, measure(tests[*which], params));
        params.coolOff();
    } else if (!which) {
        for (auto& pair : tests) {
            std::cout << "Bench: " << pair.first << std::endl;
            writeBenchResult(params, pair.first, measure(pair.second, params));
            params.coolOff();
        }
    }

#if HAVE_HPX
    if (startHpx) {
        return hpx::init();
    }
#endif

    return 0;
}
