package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.queues.TwoLockQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestQueue {
    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        int nThreads = Misc.nThreads(12);
        int workSize = 200;
        var q = new TwoLockQueue<Integer>();
        var producing = new AtomicBoolean(true);
        var got = Collections.synchronizedList(new ArrayList<Integer>());
        var pool = Executors.newFixedThreadPool(nThreads * 2, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();

        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < workSize; i++) {
                    q.enqueue(threadId * workSize + i);
                }
            }, pool));
            futs.add(CompletableFuture.runAsync(() -> {
                for (;;) {
                    var v = q.tryDequeue();
                    if (v != null) {
                        got.add(v);
                    } else if (!producing.get()) {
                        v = q.tryDequeue();
                        if (v == null) {
                            break;
                        }
                        got.add(v);
                    } else {
                        Thread.yield();
                    }
                }
            }, pool));
        }

        for (int i = 0; i < nThreads; i++) {
            futs.get(i * 2).join();
        }
        producing.set(false);
        for (var fut : futs) {
            fut.join();
        }
        pool.shutdown();

        Collections.sort(got);
        assert got.size() == nThreads * workSize;
        for (int i = 0; i < nThreads * workSize; i++) {
            assert got.get(i) == i;
        }
        System.out.println("All tests passed.");
    }
}
