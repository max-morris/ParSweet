package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.accounts.AccountLedger;
import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.locks.LockFactory;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class TestAccounts {
    private static boolean runLedger(String lockName, int nThreads, int workSize) {
        int nAccounts = 16;
        long initial = 100;
        var ledger = new AccountLedger(nAccounts, initial, LockFactory.named(lockName, nThreads));
        var pool = Executors.newFixedThreadPool(nThreads, Misc.getDaemonThreadFactory());
        var futs = new ArrayList<CompletableFuture<?>>();
        for (int t = 0; t < nThreads; t++) {
            final var threadId = t;
            futs.add(CompletableFuture.runAsync(() -> {
                var rng = new Random((threadId + 1L) * 2654435761L);
                for (int i = 0; i < workSize; i++) {
                    ledger.transfer(rng.nextInt(nAccounts), rng.nextInt(nAccounts), 1 + rng.nextInt(25));
                }
            }, pool));
        }
        for (var fut : futs) {
            fut.join();
        }
        pool.shutdown();
        var ok = ledger.total() == nAccounts * initial && ledger.noNegatives();
        assert ok;
        return ok;
    }

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        int nThreads = Misc.nThreads(12);
        int workSize = 500;
        var ok = runLedger("ReentrantLock", nThreads, workSize);
        ok = runLedger("TASLock", nThreads, workSize) && ok;
        ok = runLedger("TwoCounterLock", nThreads, workSize) && ok;
        if (!ok) {
            System.out.println("A test failed!");
            System.exit(0xBAD);
        }
        System.out.println("All tests passed.");
    }
}
