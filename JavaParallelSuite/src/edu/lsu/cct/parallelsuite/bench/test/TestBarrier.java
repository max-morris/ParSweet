package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.Barrier;
import edu.lsu.cct.parallelsuite.bench.Misc;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class TestBarrier {
    private static final int N = Misc.nThreads(12);
    private static final int T = 10;
    private static final ExecutorService pool = Executors.newFixedThreadPool(N, Misc.getDaemonThreadFactory());

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        var barrier = new Barrier(N);
        var observed = new AtomicIntegerArray(N);
        for (int i = 0; i < N; i++) {
            observed.set(i, -1);
        }
        var failed = new AtomicBoolean(false);
        var futs = new LinkedList<CompletableFuture<?>>();

        for (int i = 0; i < N; i++) {
            final int threadId = i;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int t = 0; t < T; t++) {
                    try {
                        Thread.sleep(ThreadLocalRandom.current().nextLong(0, 6));
                    } catch (InterruptedException ignored) { }

                    observed.set(threadId, t);
                    barrier.sync();
                    for (int j = 0; j < N; j++) {
                        if (observed.get(j) != t) {
                            failed.set(true);
                        }
                    }
                    barrier.sync();
                }
            }, pool));
        }

        for (var fut : futs) {
            fut.join();
        }

        pool.shutdown();

        if (failed.get()) {
            System.out.println("A test failed!");
            System.exit(0xBAD);
        }
        for (int i = 0; i < N; i++) {
            assert observed.get(i) == T - 1;
            if (observed.get(i) != T - 1) {
                System.out.println("A test failed!");
                System.exit(0xBAD);
            }
        }
        System.out.println("All tests passed.");
    }
}
