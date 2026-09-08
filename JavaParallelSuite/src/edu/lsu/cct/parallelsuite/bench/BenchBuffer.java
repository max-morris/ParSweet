package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.buffers.BoundedBuffer;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class BenchBuffer {
    public static void main(String[] args) {
        var params = new BenchParams("java", "buffer");
        if (!params.shouldRun("BoundedBuffer")) {
            return;
        }
        System.out.println("Bench: BoundedBuffer");
        var ms = params.measure(() -> {
            var buf = new BoundedBuffer<Integer>(32);
            int nThreads = params.getNThreads();
            int workSize = params.getWorkPerThread();
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
                        BlackBox.consume(buf.take());
                    }
                }, pool));
            }
            for (var fut : futs) {
                fut.join();
            }
            pool.shutdown();
        });
        params.writeResult("BoundedBuffer", ms);
    }
}
