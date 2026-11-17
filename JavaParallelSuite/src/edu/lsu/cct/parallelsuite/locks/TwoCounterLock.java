package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ticket lock. TwoCounterSemaphore generalizes it to more than one holder.
 *
 * The counters are longs, as in TwoCounterSemaphore and matching C++
 * TwoCounterLock, whose usize counters are 64 bits on 64-bit targets. The
 * != test would be correct with wrapping ints too.
 */
public class TwoCounterLock implements SlimLock {
    private final AtomicLong currentTicket = new AtomicLong(0);
    private final AtomicLong ticketCounter = new AtomicLong(0);

    @Override
    public void lock() {
        var myTicket = ticketCounter.getAndIncrement();

        // wait for my ticket to come up
        while(currentTicket.get() != myTicket) {
            Thread.yield();
        }
    }

    @Override
    public void unlock() {
        // advance to the next ticket
        currentTicket.getAndIncrement();
    }
}
