package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.locks.LockFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;

public class TestFairness {
    private static final int WORK = 1000;

    private static int[] counts(Lock lock, int nThreads) {
        var c = new int[nThreads];
        var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();
        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < WORK; i++) {
                    lock.lock();
                    try {
                        c[threadId]++;
                    } finally {
                        lock.unlock();
                    }
                }
            }, pool));
        }
        for (var fut : futs) {
            fut.join();
        }
        pool.shutdown();
        return c;
    }

    private static boolean checkFair(int[] c, String name, boolean requireFair) {
        int mn = Integer.MAX_VALUE;
        int mx = 0;
        for (int v : c) {
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        System.out.printf("%s min=%d max=%d%n", name, mn, mx);
        if (requireFair && (mn == 0 || mx > 4 * mn)) {
            System.out.printf("%s failed fairness bound%n", name);
            return false;
        }
        return true;
    }

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        int nThreads = Misc.nThreads(12);
        boolean ok = true;
        for (var name : LockFactory.ALL) {
            var lock = LockFactory.named(name, nThreads).get();
            ok = checkFair(counts(lock, nThreads), name, LockFactory.FAIR.contains(name)) && ok;
        }
        if (!ok) {
            System.out.println("A test failed!");
            System.exit(0xBAD);
        }
        System.out.println("All tests passed.");
    }
}
