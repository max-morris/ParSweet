// Lock fairness benchmark: how fairly each lock hands itself out, and at what
// cost.
//
// PSWEET_NTHREADS threads each take the lock PSWEET_WORK_PER_THREAD times.
// Holding the lock, a thread does PSWEET_CS_WORK units of busy work; after
// releasing it, PSWEET_NONCS_WORK units (defaults 50 and 0; a unit is a few
// nanoseconds of dependent arithmetic).
//
// Fairness is measured two ways:
//  * Overtaking. Just before calling lock(), a thread takes an arrival number
//    from a shared counter. Holding the lock, it checks whether a thread that
//    arrived later already got the lock first. A first-come, first-served
//    lock (e.g. TwoCounterLock) is never overtaken, except within the short
//    window between taking the number and entering lock().
//  * Wait times. Each thread times every lock() call. The benchmark reports
//    the median, 99th and 99.9th percentiles and the maximum over all of
//    them. An unfair lock can leave one thread waiting far longer than the
//    rest.
// Taking the arrival number and the timestamps adds the same small cost to
// every lock.
//
// As in every benchmark, the time of the whole run goes to PSWEET_OUT_PATH.
// The fairness measures go to PSWEET_FAIRNESS_OUT_PATH (default
// psweet_fairness.csv).
//
// Besides the locks, the benchmark times TicketCvLock: a first-come,
// first-served lock built from a mutex and a condition variable, the usual
// way to get a fair lock from a library that has none.

#include "Bench.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <latch>
#include <mutex>
#include <thread>
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
#include "BlackBox.hpp"

namespace parallel_bench::fairness {
    using namespace parallel_suite;

    // A first-come, first-served lock built from a mutex and a condition
    // variable: lock() draws a ticket and sleeps until unlock() has served
    // every earlier ticket. The ticket is drawn before taking the mutex,
    // which is itself unfair, so that the order of lock() calls decides.
    template <typename Mutex, typename CondVar>
    class TicketCvLock {
    private:
        Mutex mutex;
        CondVar turn;
        std::atomic<std::uint64_t> nextTicket{0};
        std::uint64_t nowServing = 0; // protected by mutex

    public:
        void lock() {
            std::uint64_t const ticket = nextTicket.fetch_add(1);
            // Waiting and serving both happen under the mutex, so no wakeup
            // is lost between the check and the wait.
            std::unique_lock<Mutex> guard(mutex);
            turn.wait(guard, [this, ticket]() {
                return nowServing == ticket;
            });
        }

        void unlock() {
            {
                std::lock_guard<Mutex> guard(mutex);
                ++nowServing;
            }
            // Every waiter wakes and checks whether its ticket is the one now
            // served.
            turn.notify_all();
        }
    };

    // About units * 1 ns of work the compiler cannot remove: a chain of
    // dependent multiply-adds.
    inline void busyWork(usize units) {
        std::uint64_t x = units;
        for (usize i = 0; i < units; ++i) {
            x = x * 6364136223846793005ULL + 1442695040888963407ULL;
        }
        blackBox(x);
    }

    // State shared by the threads of one run. Only the arrival counter is
    // touched outside the lock.
    struct Shared {
        std::atomic<std::uint64_t> arrivals{0};
        std::uint64_t nextUnserved = 0; // one past the highest arrival served
        std::uint64_t overtaken = 0;    // acquisitions a later arrival beat
        std::uint64_t acquisitions = 0; // to check mutual exclusion
    };

    // One thread's share of a run: take the lock waits.size() times, storing
    // how long each lock() call took, in nanoseconds, in waits.
    template <MutexType Lock>
    void takeTurns(Lock& lock, Shared& shared, usize csWork, usize nonCsWork, std::vector<std::uint64_t>& waits) {
        for (auto& wait : waits) {
            auto const t0 = std::chrono::steady_clock::now();
            std::uint64_t const arrival = shared.arrivals.fetch_add(1, std::memory_order_relaxed);
            lock.lock();
            auto const t1 = std::chrono::steady_clock::now();

            // An arrival number below nextUnserved means that some later
            // arrival has already been served: this acquisition was
            // overtaken.
            if (arrival < shared.nextUnserved) {
                ++shared.overtaken;
            } else {
                shared.nextUnserved = arrival + 1;
            }
            ++shared.acquisitions;
            busyWork(csWork);
            lock.unlock();

            wait = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
            busyWork(nonCsWork);
        }
    }

    struct FairnessResult {
        std::uint64_t overtaken;
        double overtakenPct;
        double p50WaitUs, p99WaitUs, p999WaitUs, maxWaitUs;
    };

    // Merges the threads' wait times and computes the fairness measures.
    inline FairnessResult summarize(Shared const& shared, std::vector<std::vector<std::uint64_t>> const& waits) {
        std::vector<std::uint64_t> all;
        for (auto const& w : waits) {
            all.insert(all.end(), w.begin(), w.end());
        }
        if (shared.acquisitions != all.size()) {
            throw std::runtime_error("the lock did not provide mutual exclusion");
        }
        if (all.empty()) {
            return {0, 0, 0, 0, 0, 0};
        }

        // The smallest wait that at least a fraction q of all waits do not
        // exceed.
        auto quantileUs = [&all](double q) {
            auto rank = static_cast<usize>(std::ceil(q * static_cast<double>(all.size())));
            auto nth = all.begin() + static_cast<isize>(std::max<usize>(rank, 1) - 1);
            std::nth_element(all.begin(), nth, all.end());
            return static_cast<double>(*nth) / 1e3;
        };
        return {shared.overtaken, 100.0 * static_cast<double>(shared.overtaken) / static_cast<double>(all.size()),
                quantileUs(0.5), quantileUs(0.99), quantileUs(0.999),
                static_cast<double>(*std::max_element(all.begin(), all.end())) / 1e3};
    }

    constexpr static char const* FairnessResultHeader =
            "lang,category,specific,machine,nThreads,workPerThread,hpxThreads,csWork,nonCsWork,ms,overtaken,overtakenPct,"
            "p50WaitUs,p99WaitUs,p999WaitUs,maxWaitUs";

    // Appends one row of fairness measures, writing the header first if the
    // file is new. ms repeats the run's time from PSWEET_OUT_PATH, so that
    // this file stands on its own.
    inline void writeFairnessResult(BenchParameters const& params, std::string const& outPath,
                                    std::string const& specific, usize csWork, usize nonCsWork, Duration_t duration,
                                    FairnessResult const& r) {
        bool const header = needsResultHeader(outPath);
        std::ofstream f(outPath, std::ios::app);
        if (header) {
            f << FairnessResultHeader << std::endl;
        }
        f << params.getLang() << "," << params.getCategory() << "," << specific << "," << params.getMachine() << ","
          << params.getNThreads() << "," << params.getWorkPerThread() << "," << params.getHpxThreads() << ","
          << csWork << "," << nonCsWork << "," << countDuration<std::chrono::milliseconds>(duration) << ","
          << r.overtaken << ","
          // The same precision as Java's (which may round a last digit
          // differently; overtaken is exact).
          << std::fixed << std::setprecision(4) << r.overtakenPct << std::setprecision(3) << "," << r.p50WaitUs << ","
          << r.p99WaitUs << "," << r.p999WaitUs << "," << r.maxWaitUs << std::endl;
        if (!f) {
            throw std::runtime_error("cannot write PSWEET_FAIRNESS_OUT_PATH " + outPath);
        }
    }

    // Runs one lock with std::threads, or with HPX threads if Thread is
    // hpx::thread (Latch is then hpx::latch), and writes both result rows.
    template <MutexType Lock, typename Thread, typename Latch>
    void benchLock(BenchParameters const& params, std::string const& fairnessOutPath, std::string const& name,
                   usize csWork, usize nonCsWork) {
        Lock lock;
        Shared shared;
        // Allocated before timing starts.
        std::vector<std::vector<std::uint64_t>> waits(params.getNThreads(),
                                                      std::vector<std::uint64_t>(params.getWorkPerThread()));

        auto const duration = measure([&]() {
            // All threads start taking turns together.
            Latch start(static_cast<std::ptrdiff_t>(waits.size()));
            std::vector<Thread> workers;
            for (auto& w : waits) {
                auto* mine = &w;
                workers.emplace_back([&lock, &shared, &start, mine, csWork, nonCsWork]() {
                    start.arrive_and_wait();
                    takeTurns(lock, shared, csWork, nonCsWork, *mine);
                });
            }
            for (auto& worker : workers) {
                worker.join();
            }
        });

        auto const result = summarize(shared, waits);
        writeBenchResult(params, name, duration);
        writeFairnessResult(params, fairnessOutPath, name, csWork, nonCsWork, duration, result);
    }

    // Runs every lock in the list that PSWEET_WHICH selects (all of them if
    // it is absent). logPrefix starts each lock's progress line.
    template <typename Thread, typename Latch, MutexType... Locks>
    void benchLocks(locks::LockList<Locks...>, char const* logPrefix, BenchParameters const& params,
                    std::string const& fairnessOutPath, usize csWork, usize nonCsWork) {
        auto const& which = params.getWhich();
        auto benchOne = [&]<MutexType Lock>(Lock*) {
            std::string const name = locks::LockTraits<Lock>::name;
            if (!which || *which == name) {
                std::cout << logPrefix << name << std::endl;
                benchLock<Lock, Thread, Latch>(params, fairnessOutPath, name, csWork, nonCsWork);
                params.coolOff();
            }
        };
        (benchOne(static_cast<Locks*>(nullptr)), ...);
    }

    // A non-negative integer from the environment, or fallback if unset.
    inline usize envUsize(char const* name, usize fallback) {
        char const* raw = std::getenv(name);
        if (raw == nullptr || raw[0] == '\0') {
            return fallback;
        }
        char* end = nullptr;
        unsigned long long value = std::strtoull(raw, &end, 10);
        if (end == raw || *end != '\0' || raw[0] == '-') {
            throw std::runtime_error(std::string("bad ") + name);
        }
        return static_cast<usize>(value);
    }

    // A string from the environment, or fallback if unset or empty.
    inline std::string envString(char const* name, char const* fallback) {
        char const* raw = std::getenv(name);
        return raw == nullptr || raw[0] == '\0' ? fallback : raw;
    }

    // The settings beyond BenchParameters'.
    struct Settings {
        usize csWork = envUsize("PSWEET_CS_WORK", 50);
        usize nonCsWork = envUsize("PSWEET_NONCS_WORK", 0);
        std::string outPath = envString("PSWEET_FAIRNESS_OUT_PATH", "psweet_fairness.csv");
    };
} // namespace parallel_bench::fairness

namespace parallel_suite::locks {
    template <>
    struct LockTraits<std::mutex> {
        constexpr static char const* name = "std::mutex";
    };

    template <>
    struct LockTraits<std::recursive_mutex> {
        constexpr static char const* name = "std::recursive_mutex";
    };

    template <>
    struct LockTraits<parallel_bench::fairness::TicketCvLock<std::mutex, std::condition_variable>> {
        constexpr static char const* name = "TicketCvLock<std::mutex>";
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

    template <>
    struct LockTraits<parallel_bench::fairness::TicketCvLock<hpx::mutex, hpx::condition_variable>> {
        constexpr static char const* name = "TicketCvLock<hpx::mutex>";
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

using namespace parallel_bench;
using namespace parallel_bench::fairness;

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
                                 locks::TwoCounterSemaphore<1>,
                                 TicketCvLock<std::mutex, std::condition_variable>>;
#if HAVE_HPX
#if HAVE_HPX_TWO_COUNTER
using HpxLocks = locks::LockList<hpx::mutex,
                                 hpx::spinlock,
                                 hpx::two_counter_lock,
                                 hpx::two_counter_semaphore<1>,
                                 TicketCvLock<hpx::mutex, hpx::condition_variable>>;
#else
using HpxLocks = locks::LockList<hpx::mutex, hpx::spinlock, TicketCvLock<hpx::mutex, hpx::condition_variable>>;
#endif

int hpx_main() {
    try {
        BenchParameters params("c++", "lockFairness");
        params.setHpxThreads(hpx::get_os_thread_count());
        Settings const settings;
        benchLocks<hpx::thread, hpx::latch>(HpxLocks{}, "(HPX) Bench: ", params, settings.outPath, settings.csWork,
                                            settings.nonCsWork);
    } catch (std::exception const& ex) {
        std::cerr << ex.what() << std::endl;
        hpx::finalize();
        return 0xBAD;
    }
    return hpx::finalize();
}
#endif

int main() {
    try {
        BenchParameters params("c++", "lockFairness");
        Settings const settings;
        checkResultHeader(settings.outPath, FairnessResultHeader, "PSWEET_FAIRNESS_OUT_PATH");

#if HAVE_HPX
        bool const startHpx = checkWhich(params, "BenchLockFairness", StdLocks::names(), HpxLocks::names());
#else
        bool const startHpx = checkWhich(params, "BenchLockFairness", StdLocks::names(), {});
#endif
        bool const runStd = !params.getWhich() || !startHpx;
        if (params.getNThreads() == 0) {
            throw std::runtime_error("PSWEET_NTHREADS must be positive");
        }
        // The Anderson locks and the thread-local nodes of MCSLock and
        // CLHLock are sized N_THREADS_ALLOC; more threads than that would
        // share slots.
        if (runStd && params.getNThreads() > N_THREADS_ALLOC) {
            throw std::runtime_error("PSWEET_NTHREADS must be at most N_THREADS_ALLOC (" +
                                     std::to_string(N_THREADS_ALLOC) + ") for the non-HPX locks");
        }

        if (runStd) {
            benchLocks<std::thread, std::latch>(StdLocks{}, "Bench: ", params, settings.outPath, settings.csWork,
                                                settings.nonCsWork);
        }
#if HAVE_HPX
        if (startHpx) {
            return hpx::init();
        }
#endif
        return 0;
    } catch (std::exception const& ex) {
        std::cerr << ex.what() << std::endl;
        return 0xBAD;
    }
}
