package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.locks.LockFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

public class BenchLocks {
    private static void benchLock(int nThreads, int countTo, Supplier<Lock> lockSupplier) {
        var lock = lockSupplier.get();
        var counter = new long[]{0};
        var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();
        for (int t = 0; t < nThreads; t++) {
            futs.add(CompletableFuture.runAsync(() -> {
                for (int c = 0; c < countTo; c++) {
                    lock.lock();
                    try {
                        ++counter[0];
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
        BlackBox.consume(counter[0]);
    }

    public static void main(String[] args) {
        var params = new BenchParams("java", "locks");
        var locks = LockFactory.all(params.getNThreads());
        for (var entry : locks.entrySet()) {
            if (!params.shouldRun(entry.getKey())) {
                continue;
            }
            System.out.println("Bench: " + entry.getKey());
            var ms = params.measure(() -> benchLock(params.getNThreads(), params.getWorkPerThread(), entry.getValue()));
            params.writeResult(entry.getKey(), ms);
            params.coolOff();
        }
    }
}
