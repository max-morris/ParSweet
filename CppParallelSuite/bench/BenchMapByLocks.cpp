#include "Bench.hpp"

#include <future>
#include <vector>

#include "../MutexType.hpp"
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
#include "../maps/LockHashMap.hpp"
#include "../maps/MapTypes.hpp"
#if HAVE_HPX
#include <hpx/hpx.hpp>
#include <hpx/hpx_init.hpp>
#endif

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


namespace parallel_bench::maps {
    using namespace parallel_suite;
    using namespace parallel_suite::maps;

    template <MapType<int, int> IntMap>
    void benchMap(usize const& nThreads, usize const& workSize) {
        std::vector<std::future<void>> futures;

        IntMap theMap;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, &theMap, &nThreads, &workSize]() {
                int out;

                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.put(token, token));
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                    blackBox(theMap.del(token));
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.put(token, token));
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.del(token));
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }
            }));
        }

        for (auto&& worker : futures) {
            worker.get();
        }
    }

#if HAVE_HPX
    template <MapType<int, int> IntMap>
    void benchMapHpx(usize const& nThreads, usize const& workSize) {
        std::vector<hpx::future<void>> futures;

        IntMap theMap;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(hpx::async(hpx::launch::async, [threadId, &theMap, &nThreads, &workSize]() {
                int out;

                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.put(token, token));
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                    blackBox(theMap.del(token));
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.put(token, token));
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.del(token));
                }

                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    blackBox(theMap.get(token, out));
                    blackBox(out);
                }
            }));
        }

        for (auto&& worker : futures) {
            worker.get();
        }
    }
#endif
} // namespace parallel_bench::maps

using namespace parallel_bench;
using namespace parallel_bench::maps;

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

template <template <typename, typename, auto, typename> class Map, usize Buckets, MutexType Mutex, typename... Rest>
void benchMaps(BenchParameters const& params, std::string const& mapName, std::optional<std::string> const& which) {
    auto lockName = locks::LockTraits<Mutex>::name;
    std::string specific(mapName);
    specific += "@";
    specific += lockName;

    if (!which || *which == lockName) {
        std::cout << "Bench map: " << specific << std::endl;
        writeBenchResult(params, specific, measure([&params]() {
            benchMap<Map<int, int, Buckets, Mutex>>(params.getNThreads(), params.getWorkPerThread());
        }));
        params.coolOff();
    }

    if constexpr (sizeof...(Rest) > 0) {
        benchMaps<Map, Buckets, Rest...>(params, mapName, which);
    }
}

// Runs benchMaps() over the locks of a LockList, with the PSWEET_WHICH selection.
template <template <typename, typename, auto, typename> class Map, usize Buckets, MutexType... Locks>
void benchMapsOf(locks::LockList<Locks...>, BenchParameters const& params, std::string const& mapName) {
    benchMaps<Map, Buckets, Locks...>(params, mapName, params.getWhich());
}

#if HAVE_HPX
template <template <typename, typename, auto, typename> class Map, usize Buckets, MutexType Mutex, typename... Rest>
void benchMapsHpx(BenchParameters const& params, std::string const& mapName, std::optional<std::string> const& which) {
    auto lockName = locks::LockTraits<Mutex>::name;
    std::string specific(mapName);
    specific += "@";
    specific += lockName;

    if (!which || *which == lockName) {
        std::cout << "(HPX) Bench map: " << specific << std::endl;
        writeBenchResult(params, specific, measure([&params]() {
            benchMapHpx<Map<int, int, Buckets, Mutex>>(params.getNThreads(), params.getWorkPerThread());
        }));
        params.coolOff();
    }

    if constexpr (sizeof...(Rest) > 0) {
        benchMapsHpx<Map, Buckets, Rest...>(params, mapName, which);
    }
}

// Runs benchMapsHpx() over the locks of a LockList, with the PSWEET_WHICH selection.
template <template <typename, typename, auto, typename> class Map, usize Buckets, MutexType... Locks>
void benchMapsHpxOf(locks::LockList<Locks...>, BenchParameters const& params, std::string const& mapName) {
    benchMapsHpx<Map, Buckets, Locks...>(params, mapName, params.getWhich());
}

int hpx_main() {
    BenchParameters params("c++", "mapByLocks");
    params.setHpxThreads(hpx::get_os_thread_count());

    benchMapsHpxOf<LockHashMap, 16>(HpxLocks{}, params, "LockHashMap");

    return hpx::finalize();
}
#endif

int main() {
    BenchParameters params("c++", "mapByLocks");

#if HAVE_HPX
    bool const startHpx = checkWhich(params, "BenchMapByLocks", StdLocks::names(), HpxLocks::names());
#else
    checkWhich(params, "BenchMapByLocks", StdLocks::names(), {});
#endif

    // With PSWEET_WHICH naming an HPX lock, this runs nothing.
    benchMapsOf<LockHashMap, 16>(StdLocks{}, params, "LockHashMap");

#if HAVE_HPX
    if (startHpx) {
        return hpx::init();
    }
#endif

    return 0;
}
