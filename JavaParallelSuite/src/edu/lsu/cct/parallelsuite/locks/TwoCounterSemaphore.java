package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counting semaphore generalizing TwoCounterLock; matches C++
 * TwoCounterSemaphore.
 *
 * Each acquirer draws a ticket, as in TwoCounterLock, but may enter as soon
 * as its ticket is fewer than permits ahead of currentTicket (currentTicket
 * counts releases). With permits = 1 this is equivalent to TwoCounterLock's
 * currentTicket == myTicket condition: no one can enter ahead of a waiting
 * ticket, so currentTicket never passes it.
 *
 * A similar semaphore appears in D. P. Reed and R. K. Kanodia,
 * "Synchronization with Eventcounts and Sequencers", CACM 22(2), 1979,
 * p. 119, built from a sequencer and an eventcount that play the roles of
 * ticketCounter and currentTicket. As printed there, P waits for
 * await(S.E, t - S.I), which admits S.I + 1 holders; await(S.E,
 * t - S.I + 1), the form of their bounded buffer on p. 118, admits S.I and
 * matches the test below.
 *
 * Why at most permits permits are held at once (one thread may hold
 * several; count tickets and releases from the starting ticket): every
 * ticket that has entered satisfied
 * {@code myTicket < currentTicket + permits} for some earlier, and so no
 * larger, value of currentTicket. Admitted tickets are distinct, so at most
 * currentTicket + permits of them have entered, and currentTicket of those
 * have left.
 *
 * With permits > 1, releases may come in any order, so currentTicket can
 * pass a waiter's ticket before the waiter next checks; the {@code <} test
 * (rather than {@code ==}) still admits it.
 *
 * As in C++ TwoCounterSemaphore, the test compares the difference
 * myTicket - currentTicket rather than the raw values, so it stays exact
 * across a counter wrap. The C++ header covers the one way the difference
 * can mislead: a waiter that currentTicket passed and that stays
 * descheduled for more than 2^(w-1) releases keeps waiting until the next
 * wrap, holding up one permit. The counters are longs (as in TwoCounterLock
 * and the Anderson locks, and matching C++ TwoCounterSemaphore's usize
 * counters on 64-bit targets), so that takes 2^63 releases during one stall.
 *
 * lock()/unlock() are used (rather than acquire()/release()) so the
 * semaphore is a SlimLock and, with permits = 1, can be tested and
 * benchmarked alongside the other locks.
 */
public class TwoCounterSemaphore implements SlimLock {
    private final long permits;
    private final AtomicLong currentTicket;
    private final AtomicLong ticketCounter;

    public TwoCounterSemaphore(int permits) {
        this(permits, 0);
    }

    /**
     * Start both counters at startTicket rather than 0. The semaphore behaves
     * the same from any start; TestSemaphores starts just below the wrap
     * point to exercise the wrap-safe comparison.
     */
    public TwoCounterSemaphore(int permits, long startTicket) {
        if (permits <= 0) {
            throw new IllegalArgumentException("TwoCounterSemaphore needs at least one permit.");
        }

        this.permits = permits;
        currentTicket = new AtomicLong(startTicket);
        ticketCounter = new AtomicLong(startTicket);
    }

    /** Take one permit, waiting until one is free. */
    @Override
    public void lock() {
        var myTicket = ticketCounter.getAndIncrement();

        // wait until my ticket is within permits of the current ticket
        while (myTicket - currentTicket.get() >= permits) {
            Thread.yield();
        }
    }

    /** Return one permit. Any holder may call this, in any order. */
    @Override
    public void unlock() {
        currentTicket.getAndIncrement();
    }
}
