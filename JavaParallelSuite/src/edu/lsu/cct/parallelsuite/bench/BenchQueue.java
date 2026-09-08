package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.queues.TwoLockQueue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BenchQueue {
    public static void main(String[] args) {
        var params = new BenchParams("java", "queue");
        if (!params.shouldRun("TwoLockQueue")) {
            return;
        }
        System.out.println("Bench: TwoLockQueue");
        var ms = params.measure(() -> {
            var q = new TwoLockQueue<Integer>();
            int nThreads = params.getNThreads();
            int workSize = params.getWorkPerThread();
            var producing = new AtomicBoolean(true);
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
                            BlackBox.consume(v);
                        } else if (!producing.get()) {
                            v = q.tryDequeue();
                            if (v == null) {
                                break;
                            }
                            BlackBox.consume(v);
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
        });
        params.writeResult("TwoLockQueue", ms);
    }
}
