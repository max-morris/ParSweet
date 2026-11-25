#include "Bench.hpp"

#include <future>
#include <vector>
#if HAVE_HPX
#include <hpx/hpx.hpp>
#include <hpx/hpx_init.hpp>
#endif
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
#include "../locks/TwoCounterSemaphore.hpp"
#include "../sets/FineGrainedSet.hpp"

#include "BlackBox.hpp"

namespace parallel_suite::locks {
    template <>
    struct LockTraits<std::mutex> {
        constexpr static char const* name = "std::mutex";
    };

    template <>
    struct LockTraits<std::recursive_mutex> {
        constexpr static char const* name = "std::recursive_mutex";
    };
#if HAVE_HPX
    template <>
    struct LockTraits<hpx::mutex> {
        constexpr static char const* name = "hpx::mutex";
    };

    template <>
    struct LockTraits<hpx::spinlock> {
        constexpr static char const* name = "hpx::spinlock";
    };
#if HAVE_HPX_TWO_COUNTER
    template <>
    struct LockTraits<hpx::two_counter_lock> {
        constexpr static char const* name = "hpx::two_counter_lock";
    };

    template <>
    struct LockTraits<hpx::two_counter_semaphore<1>> {
        constexpr static char const* name = "hpx::two_counter_semaphore<1>";
    };
#endif
#endif
} // namespace parallel_suite::locks

namespace parallel_bench::sets {
    using namespace parallel_suite;
    using namespace parallel_suite::sets;

    template <SetType<int> IntSet>
    void benchSet(usize const& nThreads, usize const& workSize) {
        std::vector<std::future<void>> futures;

        IntSet theSet;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, &theSet, &workSize, &nThreads]() {
                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;

                    blackBox(theSet.add(token));
                    blackBox(theSet.contains(token));
                    blackBox(theSet.remove(token));
                    blackBox(theSet.contains(token));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.add(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.contains(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.remove(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.contains(i * nThreads + threadId));
                }
            }));
        }

        for (auto&& worker : futures) {
            worker.get();
        }
    }

#if HAVE_HPX
    template <SetType<int> IntSet>
    void benchSetHpx(usize const& nThreads, usize const& workSize) {
        std::vector<hpx::future<void>> futures;

        IntSet theSet;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(hpx::async(hpx::launch::async, [threadId, &theSet, &workSize, &nThreads]() {
                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;

                    blackBox(theSet.add(token));
                    blackBox(theSet.contains(token));
                    blackBox(theSet.remove(token));
                    blackBox(theSet.contains(token));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.add(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.contains(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.remove(i * nThreads + threadId));
                }

                for (int i = 0; i < workSize; i++) {
                    blackBox(theSet.contains(i * nThreads + threadId));
                }
            }));
        }

        for (auto&& worker : futures) {
            worker.get();
        }
    }
#endif

} // namespace parallel_bench::sets

using namespace parallel_bench;
using namespace parallel_bench::sets;

// The locks the benchmark loops over, outside and inside HPX.
using StdLocks = locks::LockList<std::mutex,
                                 std::recursive_mutex,
                                 locks::ALock<N_THREADS_ALLOC>,
                                 locks::BackoffLock<1, 17>,
                                 locks::CLHLock,
                                 locks::IdLock,
                                 locks::MCSLock,
                                 locks::OptimizedALock<N_THREADS_ALLOC>,
                                 locks::TASLock,
                                 locks::TIdLock,
                                 locks::TTASLock,
                                 locks::TwoCounterLock,
                                 locks::TwoCounterSemaphore<1>>;
#if HAVE_HPX
#if HAVE_HPX_TWO_COUNTER
using HpxLocks = locks::LockList<hpx::mutex, hpx::spinlock, hpx::two_counter_lock, hpx::two_counter_semaphore<1>>;
#else
using HpxLocks = locks::LockList<hpx::mutex, hpx::spinlock>;
#endif
#endif

template <template <typename, typename> class Set, MutexType Mutex, typename... Rest>
void benchSets(BenchParameters const& params, std::string const& setName, std::optional<std::string> const& which) {
    auto lockName = locks::LockTraits<Mutex>::name;
    std::string specific(setName);
    specific += "@";
    specific += lockName;

    if (!which || *which == lockName) {
        std::cout << "Bench set: " << specific << std::endl;
        writeBenchResult(params, specific, measure([&params]() {
            benchSet<Set<int, Mutex>>(params.getNThreads(), params.getWorkPerThread());
        }));

        params.coolOff();
    }

    if constexpr (sizeof...(Rest) > 0) {
        benchSets<Set, Rest...>(params, setName, which);
    }
}

// Runs benchSets() over the locks of a LockList, with the PSWEET_WHICH selection.
template <template <typename, typename> class Set, MutexType... Locks>
void benchSetsOf(locks::LockList<Locks...>, BenchParameters const& params, std::string const& setName) {
    benchSets<Set, Locks...>(params, setName, params.getWhich());
}

#if HAVE_HPX
template <template <typename, typename> class Set, MutexType Mutex, typename... Rest>
void benchSetsHpx(BenchParameters const& params, std::string const& setName, std::optional<std::string> const& which) {
    auto lockName = locks::LockTraits<Mutex>::name;
    std::string specific(setName);
    specific += "@";
    specific += lockName;

    if (!which || *which == lockName) {
        std::cout << "(HPX) Bench set: " << specific << std::endl;
        writeBenchResult(params, specific, measure([&params]() {
            benchSetHpx<Set<int, Mutex>>(params.getNThreads(), params.getWorkPerThread());
        }));

        params.coolOff();
    }

    if constexpr (sizeof...(Rest) > 0) {
        benchSetsHpx<Set, Rest...>(params, setName, which);
    }
}

// Runs benchSetsHpx() over the locks of a LockList, with the PSWEET_WHICH selection.
template <template <typename, typename> class Set, MutexType... Locks>
void benchSetsHpxOf(locks::LockList<Locks...>, BenchParameters const& params, std::string const& setName) {
    benchSetsHpx<Set, Locks...>(params, setName, params.getWhich());
}

int hpx_main() {
    BenchParameters params("c++", "setByLocks");
    params.setHpxThreads(hpx::get_os_thread_count());

    benchSetsHpxOf<FineGrainedSet>(HpxLocks{}, params, "FineGrainedSet");
    return hpx::finalize();
}
#endif

int main() {
    BenchParameters params("c++", "setByLocks");

#if HAVE_HPX
    bool const startHpx = checkWhich(params, "BenchSetByLocks", StdLocks::names(), HpxLocks::names());
#else
    checkWhich(params, "BenchSetByLocks", StdLocks::names(), {});
#endif

    // With PSWEET_WHICH naming an HPX lock, this runs nothing.
    benchSetsOf<FineGrainedSet>(StdLocks{}, params, "FineGrainedSet");

#if HAVE_HPX
    if (startHpx) {
        return hpx::init();
    }
#endif

    return 0;
}
