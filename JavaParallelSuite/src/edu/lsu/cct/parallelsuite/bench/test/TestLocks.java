package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.locks.*;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class TestLocks {
    private static final int THREADS = Misc.nThreads(12);
    private static final int COUNT_TO = 20000;
    private static final int RECURSIVE_COUNT_TO = 2000;
    private static final ExecutorService pool = Executors.newFixedThreadPool(THREADS, Misc.getDaemonThreadFactory());

    private static class Counter {
        private long value = 0;
        private final Lock lock;

        public void work() {
            for (int c = 0; c < COUNT_TO; c++) {
                lock.lock();
                try {
                    ++value;
                } finally {
                    lock.unlock();
                }
            }
        }

        public void workRecursive() {
            for (int c = 0; c < RECURSIVE_COUNT_TO; c++) {
                lock.lock();
                lock.lock();
                try {
                    ++value;
                } finally {
                    lock.unlock();
                    lock.unlock();
                }
            }
        }

        public boolean verify(int countTo) {
            return value == ((long) THREADS * countTo);
        }

        public Counter(Supplier<Lock> lockSupplier) {
            lock = lockSupplier.get();
        }
    }

    private static boolean testLock(Supplier<Lock> lockSupplier) {
        var counter = new Counter(lockSupplier);
        var futs = new LinkedList<CompletableFuture<?>>();

        for (int i = 0; i < THREADS; i++) {
            futs.add(CompletableFuture.runAsync(counter::work, pool));
        }

        for (var fut : futs) {
            fut.join();
        }

        var v = counter.verify(COUNT_TO);
        assert v;
        return v;
    }

    private static boolean testRecursiveLock(Supplier<Lock> lockSupplier) {
        var counter = new Counter(lockSupplier);
        var futs = new LinkedList<CompletableFuture<?>>();

        for (int i = 0; i < THREADS; i++) {
            futs.add(CompletableFuture.runAsync(counter::workRecursive, pool));
        }

        for (var fut : futs) {
            fut.join();
        }

        var v = counter.verify(RECURSIVE_COUNT_TO);
        assert v;
        return v;
    }

    private static final int TRIES = 30;

    @SafeVarargs
    private static boolean testLocksRepeatedly(Supplier<Lock>... lockSuppliers) {
        for (int t = 0; t < TRIES; t++) {
            for (var lockSupplier : lockSuppliers) {
                if (!testLock(lockSupplier)) {
                    System.out.printf("A test failed! %s%n", lockSupplier.get().getClass().getName());
                    return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        var ok = testLocksRepeatedly(
                ReentrantLock::new,
                TASLock::new,
                TTASLock::new,
                () -> new ALock(THREADS),
                () -> new OptimizedALock(THREADS),
                BackoffLock::new,
                TwoCounterLock::new,
                CLHLock::new,
                MCSLock::new,
                IdLock::new,
                TIdLock::new
        );

        ok = testRecursiveLock(ReentrantLock::new) && ok;

        pool.shutdown();

        if (ok) {
            System.out.println("All tests passed.");
        } else {
            System.out.println("A test failed!");
            System.exit(0xBAD);
        }
    }
}
