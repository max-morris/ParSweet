package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.locks.LockFactory;
import edu.lsu.cct.parallelsuite.maps.LockHashMap;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class BenchMapByLocks {
    private static void benchMap(int nThreads, int workSize, Map<Integer, Integer> map) {
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
        var params = new BenchParams("java", "mapByLocks");
        for (var name : LockFactory.ALL) {
            if (!params.shouldRun(name)) {
                continue;
            }
            var specific = "LockHashMap@" + name;
            System.out.println("Bench map: " + specific);
            var lock = LockFactory.named(name, params.getNThreads());
            var ms = params.measure(() -> benchMap(params.getNThreads(), params.getWorkPerThread(), new LockHashMap<>(lock)));
            params.writeResult(specific, ms);
            params.coolOff();
        }
    }
}
