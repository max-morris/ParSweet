package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.sets.FineGrainedSet;
import edu.lsu.cct.parallelsuite.sets.LazySet;
import edu.lsu.cct.parallelsuite.sets.LockHashSet;
import edu.lsu.cct.parallelsuite.sets.OptimisticSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class BenchSets {
    private static void benchSet(int nThreads, int workSize, Supplier<Set<Integer>> setSupplier) {
        var set = setSupplier.get();
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
        var params = new BenchParams("java", "sets");
        var impls = new LinkedHashMap<String, Supplier<Set<Integer>>>();
        impls.put("LockHashSet", LockHashSet::new);
        impls.put("FineGrainedSet", FineGrainedSet::new);
        impls.put("OptimisticSet", OptimisticSet::new);
        impls.put("LazySet", LazySet::new);

        for (var entry : impls.entrySet()) {
            if (!params.shouldRun(entry.getKey())) {
                continue;
            }
            System.out.println("Bench: " + entry.getKey());
            var ms = params.measure(() -> benchSet(params.getNThreads(), params.getWorkPerThread(), entry.getValue()));
            params.writeResult(entry.getKey(), ms);
            params.coolOff();
        }
    }
}
