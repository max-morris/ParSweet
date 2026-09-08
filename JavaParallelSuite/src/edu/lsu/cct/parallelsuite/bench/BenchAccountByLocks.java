package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.accounts.AccountLedger;
import edu.lsu.cct.parallelsuite.locks.LockFactory;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class BenchAccountByLocks {
    public static void main(String[] args) {
        var params = new BenchParams("java", "accountByLocks");
        for (var name : LockFactory.ALL) {
            if (!params.shouldRun(name)) {
                continue;
            }
            var specific = "AccountLedger@" + name;
            System.out.println("Bench: " + specific);
            var ms = params.measure(() -> {
                int nThreads = params.getNThreads();
                int workSize = params.getWorkPerThread();
                var ledger = new AccountLedger(16, 100, LockFactory.named(name, nThreads));
                var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
                var futs = new ArrayList<CompletableFuture<?>>();
                for (int t = 0; t < nThreads; t++) {
                    final var threadId = t;
                    futs.add(CompletableFuture.runAsync(() -> {
                        var rng = new Random(threadId + 1L);
                        for (int i = 0; i < workSize; i++) {
                            ledger.transfer(rng.nextInt(16), rng.nextInt(16), 1 + rng.nextInt(25));
                        }
                    }, pool));
                }
                for (var fut : futs) {
                    fut.join();
                }
                pool.shutdown();
                BlackBox.consume(ledger.total());
            });
            params.writeResult(specific, ms);
            params.coolOff();
        }
    }
}
