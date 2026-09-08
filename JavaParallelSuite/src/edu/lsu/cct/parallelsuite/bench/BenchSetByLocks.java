package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.locks.LockFactory;
import edu.lsu.cct.parallelsuite.sets.FineGrainedSet;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class BenchSetByLocks {
    private static void benchSet(int nThreads, int workSize, Set<Integer> set) {
        var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();
        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < workSize; ++i) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(set.add(token));
                    BlackBox.consume(set.contains(token));
                    BlackBox.consume(set.remove(token));
                    BlackBox.consume(set.contains(token));
                }
                for (int i = 0; i < workSize; i++) {
                    BlackBox.consume(set.add(i * nThreads + threadId));
                }
                for (int i = 0; i < workSize; i++) {
                    BlackBox.consume(set.contains(i * nThreads + threadId));
                }
                for (int i = 0; i < workSize; i++) {
                    BlackBox.consume(set.remove(i * nThreads + threadId));
                }
                for (int i = 0; i < workSize; i++) {
                    BlackBox.consume(set.contains(i * nThreads + threadId));
                }
            }, pool));
        }
        for (var fut : futs) {
            fut.join();
        }
        pool.shutdown();
    }

    public static void main(String[] args) {
        var params = new BenchParams("java", "setByLocks");
        for (var name : LockFactory.ALL) {
            if (!params.shouldRun(name)) {
                continue;
            }
            var specific = "FineGrainedSet@" + name;
            System.out.println("Bench set: " + specific);
            var lock = LockFactory.named(name, params.getNThreads());
            var ms = params.measure(() -> benchSet(params.getNThreads(), params.getWorkPerThread(), new FineGrainedSet<>(lock)));
            params.writeResult(specific, ms);
            params.coolOff();
        }
    }
}
