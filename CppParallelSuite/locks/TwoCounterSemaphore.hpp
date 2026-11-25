#ifndef TWO_COUNTER_SEMAPHORE_HPP
#define TWO_COUNTER_SEMAPHORE_HPP

#include "../Types.hpp"
#include "LockTraits.hpp"
#include <atomic>
#include <limits>
#include <thread>

namespace parallel_suite::locks {
    // Counting semaphore generalizing TwoCounterLock; matches Java
    // TwoCounterSemaphore.
    //
    // Each acquirer draws a ticket, as in TwoCounterLock, but may enter as
    // soon as its ticket is fewer than Permits ahead of currentTicket
    // (currentTicket counts releases). With Permits = 1 this is equivalent
    // to TwoCounterLock's `currentTicket == ticket` condition: no one can
    // enter ahead of a waiting ticket, so currentTicket never passes it.
    //
    // A similar semaphore appears in D. P. Reed and R. K. Kanodia,
    // "Synchronization with Eventcounts and Sequencers", CACM 22(2), 1979,
    // p. 119, built from a sequencer and an eventcount that play the roles of
    // ticketCounter and currentTicket. As printed there, P waits for
    // await(S.E, t - S.I), which admits S.I + 1 holders; await(S.E,
    // t - S.I + 1), the form of their bounded buffer on p. 118, admits S.I
    // and matches the test below.
    //
    // Why at most Permits permits are held at once (one thread may hold
    // several; count tickets and releases from the starting ticket): every
    // ticket that has entered satisfied
    // ticket < currentTicket + Permits for some earlier, and so no larger,
    // value of currentTicket. Admitted tickets are distinct, so at most
    // currentTicket + Permits of them have entered, and currentTicket of
    // those have left.
    //
    // With Permits > 1, releases may come in any order, so currentTicket
    // can pass a waiter's ticket before the waiter next checks; the `<`
    // test (rather than `==`) still admits it.
    //
    // The counters can wrap (usize is only 32 bits on 32-bit targets), so
    // the test compares the signed difference ticket - currentTicket rather
    // than the raw values. A raw compare would admit tickets drawn just
    // after the wrap point too early (so more than Permits can be held)
    // and strand those drawn just before it (at least the last one; every
    // thread, for Permits = 1). The difference is exact while it stays
    // within the signed range:
    // - Positive side: tickets ahead of currentTicket, at most the number
    //   of outstanding lock() calls (waiting plus held).
    // - Negative side (Permits > 1 only): once currentTicket passes a
    //   waiter that has not yet checked again (e.g. it was descheduled),
    //   the distance grows by one per release until the waiter checks.
    //   Only if more than 2^(w-1) releases happen first does the waiter see
    //   a large positive distance and keep waiting, until the counter wraps
    //   around again. That never admits too many, but it stalls that thread, and
    //   the permit it was granted, so the semaphore meanwhile runs with
    //   Permits - 1. With 64-bit usize that takes 2^63 releases during one
    //   stall, which is unreachable.
    //
    // lock()/unlock() are used (rather than acquire()/release()) so the
    // semaphore satisfies MutexType and, with Permits = 1, can be
    // benchmarked alongside the other locks.
    template <usize Permits>
    class TwoCounterSemaphore {
        static_assert(Permits > 0, "TwoCounterSemaphore needs at least one permit.");
        static_assert(Permits <= static_cast<usize>(std::numeric_limits<isize>::max()),
                      "TwoCounterSemaphore's permit count must fit the signed ticket distance.");

    private:
        std::atomic<usize> currentTicket;
        std::atomic<usize> ticketCounter;

    public:
        TwoCounterSemaphore() : TwoCounterSemaphore(0) {}

        // Start both counters at startTicket rather than 0. The semaphore
        // behaves the same from any start; TestSemaphores starts just below
        // the wrap point to exercise the wrap-safe comparison.
        explicit TwoCounterSemaphore(usize startTicket) : currentTicket(startTicket), ticketCounter(startTicket) {}

        // Take one permit, waiting until one is free.
        void lock() {
            auto ticket = ticketCounter.fetch_add(1);

            // wait until my ticket is within Permits of the current ticket
            while (static_cast<isize>(ticket - currentTicket.load()) >= static_cast<isize>(Permits)) {
                std::this_thread::yield();
            }
        }

        // Return one permit. Any holder may call this, in any order.
        void unlock() {
            currentTicket.fetch_add(1);
        }
    };

    template <usize Permits>
    struct LockTraits<TwoCounterSemaphore<Permits>> {
        constexpr static char const* name = TemplatedLockName<"TwoCounterSemaphore", Permits>::value;
    };
} // namespace parallel_suite::locks

#endif //TWO_COUNTER_SEMAPHORE_HPP
