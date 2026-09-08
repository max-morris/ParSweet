package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.maps.LockHashMap;
import edu.lsu.cct.parallelsuite.maps.SetBasedMap;
import edu.lsu.cct.parallelsuite.sets.FineGrainedSet;
import edu.lsu.cct.parallelsuite.sets.LazySet;
import edu.lsu.cct.parallelsuite.sets.OptimisticSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class BenchMaps {
    private static void benchMap(int nThreads, int workSize, Supplier<Map<Integer, Integer>> mapSupplier) {
        var map = mapSupplier.get();
        var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();
        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < workSize; ++i) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(map.put(token, token));
                    BlackBox.consume(map.get(token));
                    BlackBox.consume(map.remove(token));
                    BlackBox.consume(map.get(token));
                }
                for (int i = 0; i < workSize; i++) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(map.put(token, token));
                }
                for (int i = 0; i < workSize; i++) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(map.get(token));
                }
                for (int i = 0; i < workSize; i++) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(map.remove(token));
                }
                for (int i = 0; i < workSize; i++) {
                    var token = i * nThreads + threadId;
                    BlackBox.consume(map.get(token));
                }
            }, pool));
        }
        for (var fut : futs) {
            fut.join();
        }
        pool.shutdown();
    }

    public static void main(String[] args) {
        var params = new BenchParams("java", "maps");
        var impls = new LinkedHashMap<String, Supplier<Map<Integer, Integer>>>();
        impls.put("LockHashMap", LockHashMap::new);
        impls.put("SetBasedMap<FineGrainedSet>", () -> new SetBasedMap<>(FineGrainedSet::new));
        impls.put("SetBasedMap<OptimisticSet>", () -> new SetBasedMap<>(OptimisticSet::new));
        impls.put("SetBasedMap<LazySet>", () -> new SetBasedMap<>(LazySet::new));

        for (var entry : impls.entrySet()) {
            if (!params.shouldRun(entry.getKey())) {
                continue;
            }
            System.out.println("Bench: " + entry.getKey());
            var ms = params.measure(() -> benchMap(params.getNThreads(), params.getWorkPerThread(), entry.getValue()));
            params.writeResult(entry.getKey(), ms);
            params.coolOff();
        }
    }
}
