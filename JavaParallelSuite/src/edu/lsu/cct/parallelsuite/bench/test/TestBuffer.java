package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.buffers.BoundedBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class TestBuffer {
    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        int nThreads = Misc.nThreads(12);
        int workSize = 200;
        var buf = new BoundedBuffer<Integer>(8);
        var got = Collections.synchronizedList(new ArrayList<Integer>());
        var pool = Executors.newFixedThreadPool(nThreads * 2, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();

        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < workSize; i++) {
                    buf.put(threadId * workSize + i);
                }
            }, pool));
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < workSize; i++) {
                    got.add(buf.take());
                }
            }, pool));
        }

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
