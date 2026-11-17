package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.locks.*;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

public class TestSemaphores {
    // Largest permit count tested, in main().
    private static final int MAX_PERMITS = 8;

    // One thread per core, but more threads than the largest permit count
    // even on small machines, as in C++ TestSemaphores; otherwise the holder
    // bound could never be exceeded and its test would pass vacuously.
    private static final int THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 2 * MAX_PERMITS);
    private static final int COUNT_TO = 20000;
    private static final ExecutorService pool = Executors.newFixedThreadPool(THREADS, Misc.getDaemonThreadFactory());

    /** Makes a semaphore with the given permits and starting ticket. */
    @FunctionalInterface
    private interface SemaphoreFactory {
        Lock make(int permits, long startTicket);
    }

    // Each test runs from ticket 0 and from just below the point where the
    // ticket counters wrap (Long.MAX_VALUE to Long.MIN_VALUE). Counters that
    // wrap must be compared by difference; comparing raw values (e.g.
    // myTicket >= currentTicket + permits) misbehaves across the wrap. In the
    // stress test it admits too many holders (caught as overflow) and then
    // hangs once currentTicket + permits wraps but myTicket has not; the
    // admits test hangs on its first lock(). The CTest timeout turns a hang
    // into a failure.

    // Starting the stress test this far below the wrap point puts the wrap
    // early in the run. The workers' start gate makes all threads contend by
    // then.
    private static final long STRESS_WRAP_LEAD = 1000;

    /**
     * One thread takes all permits before releasing any. A semaphore that
     * admits fewer than permits holders hangs here instead of returning.
     * Starting at Long.MAX_VALUE - permits / 2 makes currentTicket + permits
     * wrap on the first lock(), and the tickets themselves wrap partway
     * through (for permits >= 3).
     */
    private static boolean testAdmitsPermits(SemaphoreFactory semaphoreFactory, int permits, long startTicket) {
        var semaphore = semaphoreFactory.make(permits, startTicket);

        for (int p = 0; p < permits; p++) {
            semaphore.lock();
        }

        for (int p = 0; p < permits; p++) {
            semaphore.unlock();
        }

        return true;
    }

    /**
     * Each holder counts itself in and out of inside and flags overflow if it
     * ever sees more than permits holders at once. The yield widens the
     * window in which holders overlap. Each worker first spins at a start
     * gate until all THREADS workers have started (the pool has exactly
     * THREADS threads), so the run, including a wrap early in it, is
     * contended from the first lock(). The gate spins, as in C++
     * TestSemaphores, rather than blocking on a latch: a latch wakes its
     * waiters one after another, too slowly to have them all running by the
     * wrap.
     */
    private static class Holders {
        private final int permits;
        private final Lock semaphore;
        private final AtomicInteger ready = new AtomicInteger(0);
        private final AtomicInteger inside = new AtomicInteger(0);
        private final AtomicBoolean overflow = new AtomicBoolean(false);

        public void work() {
            ready.incrementAndGet();
            while (ready.get() < THREADS) {
                Thread.yield();
            }

            for (int c = 0; c < COUNT_TO; c++) {
                semaphore.lock();
                try {
                    if (inside.incrementAndGet() > permits) {
                        overflow.set(true);
                    }

                    Thread.yield();
                    inside.decrementAndGet();
                } finally {
                    semaphore.unlock();
                }
            }
        }

        public boolean verify() {
            return !overflow.get();
        }

        public Holders(SemaphoreFactory semaphoreFactory, int permits, long startTicket) {
            this.permits = permits;
            semaphore = semaphoreFactory.make(permits, startTicket);
        }
    }

    private static boolean testBoundsHolders(SemaphoreFactory semaphoreFactory, int permits, long startTicket) {
        var holders = new Holders(semaphoreFactory, permits, startTicket);
        var futs = new LinkedList<CompletableFuture<?>>();

        for (int i = 0; i < THREADS; i++) {
            futs.add(CompletableFuture.runAsync(holders::work, pool));
        }

        for (var fut : futs) {
            fut.join();
        }

        var v = holders.verify();
        assert v;
        return v;
    }

    private static final int TRIES = 10;

    private static boolean testSemaphore(SemaphoreFactory semaphoreFactory, int permits) {
        return testAdmitsPermits(semaphoreFactory, permits, 0) &&
                testAdmitsPermits(semaphoreFactory, permits, Long.MAX_VALUE - permits / 2) &&
                testBoundsHolders(semaphoreFactory, permits, 0) &&
                testBoundsHolders(semaphoreFactory, permits, Long.MAX_VALUE - STRESS_WRAP_LEAD);
    }

    private static void testSemaphoresRepeatedly(SemaphoreFactory semaphoreFactory, int... permitCounts) {
        for (int t = 0; t < TRIES; t++) {
            for (var permits : permitCounts) {
                if (!testSemaphore(semaphoreFactory, permits)) {
                    System.out.printf("A test failed! %s with %d permits\n", semaphoreFactory.make(permits, 0).getClass().getName(), permits);
                    return;
                }
            }
        }

        System.out.println("All tests passed.");
    }

    public static void main(String[] args) {
        testSemaphoresRepeatedly(TwoCounterSemaphore::new, 1, 2, 3, MAX_PERMITS);
    }
}
