package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.locks.ALock;
import edu.lsu.cct.parallelsuite.locks.BackoffLock;
import edu.lsu.cct.parallelsuite.locks.CLHLock;
import edu.lsu.cct.parallelsuite.locks.IdLock;
import edu.lsu.cct.parallelsuite.locks.MCSLock;
import edu.lsu.cct.parallelsuite.locks.OptimizedALock;
import edu.lsu.cct.parallelsuite.locks.TASLock;
import edu.lsu.cct.parallelsuite.locks.TIdLock;
import edu.lsu.cct.parallelsuite.locks.TTASLock;
import edu.lsu.cct.parallelsuite.locks.TwoCounterLock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * N integers start at 0. An attempt reads 3, 4, or 5 of them with no locks
 * held, then locks every location from first to last. If any of those values
 * changed, it unlocks from last back to first and tries again. Otherwise it
 * increments the smallest, breaking a tie toward the lowest index, and
 * unlocks the same way. Each thread stops after a fixed number of commits.
 * The sum of the integers is that many commits times the number of threads.
 *
 * C++ also times std::mutex and std::recursive_mutex. The JDK lock here is
 * ReentrantLock, which covers both roles. The unlocked read uses getPlain so
 * it matches a relaxed atomic load.
 */
public final class BenchTransaction {
    private static final int DEFAULT_VALUES = 32;
    private static final int MIN_SUBSET = 3;
    private static final int MAX_SUBSET = 5;

    private BenchTransaction() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        var params = new BenchParameters("java", "transaction");
        int nValues = BenchParameters.optionalInt("PSWEET_WATCHES", DEFAULT_VALUES);
        int slots = andersonSlots();
        if (params.getNThreads() <= 0 || params.getNThreads() > slots) {
            throw new IllegalArgumentException("PSWEET_NTHREADS must be in 1.." + slots);
        }
        if (params.getWorkPerThread() <= 0 || nValues < MAX_SUBSET) {
            throw new IllegalArgumentException("PSWEET_WORK_PER_THREAD must be positive and PSWEET_WATCHES must be at least 5");
        }
        // One cell can receive every commit. Keep that increment inside a signed int.
        if (params.getWorkPerThread() > Integer.MAX_VALUE / params.getNThreads()) {
            throw new IllegalArgumentException("PSWEET_NTHREADS * PSWEET_WORK_PER_THREAD must fit in a signed int");
        }

        List<NamedLock> locks = List.of(
                named("ReentrantLock", ReentrantLock::new),
                named("ALock", () -> new ALock(slots)),
                named("BackoffLock", BackoffLock::new),
                named("CLHLock", CLHLock::new),
                named("IdLock", IdLock::new),
                named("MCSLock", MCSLock::new),
                named("OptimizedALock", () -> new OptimizedALock(slots)),
                named("TASLock", TASLock::new),
                named("TIdLock", TIdLock::new),
                named("TTASLock", TTASLock::new),
                named("TwoCounterLock", TwoCounterLock::new));

        boolean ran = false;
        for (NamedLock lock : locks) {
            if (params.getWhich() != null && !params.getWhich().equals(lock.name)) {
                continue;
            }
            ran = true;
            System.out.println("Bench: " + lock.name);
            long ms = measure(() -> runOne(lock.supplier, params.getNThreads(), params.getWorkPerThread(), nValues));
            params.writeResult(lock.name, ms);
            params.coolOff();
        }
        if (params.getWhich() != null && !ran) {
            throw new IllegalArgumentException("PSWEET_WHICH does not name a lock: " + params.getWhich());
        }
    }

    private static void runOne(Supplier<Lock> supply, int nThreads, int work, int nValues) {
        var locks = new Lock[nValues];
        var values = new AtomicInteger[nValues];
        for (int i = 0; i < nValues; i++) {
            locks[i] = supply.get();
            values[i] = new AtomicInteger();
        }

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            var workers = new ArrayList<Future<?>>(nThreads);
            for (int t = 0; t < nThreads; t++) {
                workers.add(pool.submit(() -> {
                    var order = new int[nValues];
                    var snap = new int[MAX_SUBSET];
                    for (int done = 0; done < work; ) {
                        if (commit(locks, values, order, snap)) {
                            done++;
                        }
                    }
                }));
            }
            for (Future<?> worker : workers) {
                try {
                    worker.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw new IllegalStateException(cause == null ? e : cause);
                }
            }
        } finally {
            pool.shutdown();
        }

        long sum = 0;
        for (AtomicInteger value : values) {
            sum += value.getPlain();
        }
        long expect = (long) nThreads * work;
        if (sum != expect) {
            throw new IllegalStateException("transaction sum " + sum + " expected " + expect);
        }
    }

    // True when this attempt committed. order has room for every location.
    // snap has room for MAX_SUBSET samples from the unlocked read.
    private static boolean commit(Lock[] locks, AtomicInteger[] values, int[] order, int[] snap) {
        int n = values.length;
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int k = rng.nextInt(MIN_SUBSET, MAX_SUBSET + 1);
        for (int i = 0; i < k; i++) {
            int j = rng.nextInt(i, n);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        for (int i = 0; i < k; i++) {
            snap[i] = values[order[i]].getPlain();
        }

        int held = 0;
        boolean ok = false;
        try {
            for (int i = 0; i < n; i++) {
                locks[i].lock();
                held++;
            }
            ok = true;
            for (int i = 0; i < k; i++) {
                if (values[order[i]].getPlain() != snap[i]) {
                    ok = false;
                }
            }
            if (ok) {
                int best = 0;
                for (int i = 1; i < k; i++) {
                    if (snap[i] < snap[best] || (snap[i] == snap[best] && order[i] < order[best])) {
                        best = i;
                    }
                }
                values[order[best]].setPlain(snap[best] + 1);
            }
        } finally {
            for (int i = held - 1; i >= 0; i--) {
                locks[i].unlock();
            }
        }
        return ok;
    }

    private static long measure(Runnable work) {
        long t1 = System.nanoTime();
        work.run();
        return (System.nanoTime() - t1) / 1_000_000L;
    }

    /**
     * C++ sizes Anderson locks with N_THREADS_ALLOC (cores * 6/5 + 1, or PSWEET_N_THREADS_ALLOC at cmake time).
     * Read the same env here so a rebuilt C++ binary and this process agree. availableProcessors is the
     * runtime stand-in for cmake's logical-core count when the env var is unset.
     */
    private static int andersonSlots() {
        int configured = BenchParameters.optionalInt("PSWEET_N_THREADS_ALLOC", 0);
        if (configured > 0) {
            return configured;
        }
        return Runtime.getRuntime().availableProcessors() * 6 / 5 + 1;
    }

    private static NamedLock named(String name, Supplier<Lock> supplier) {
        return new NamedLock(name, supplier);
    }

    private static final class NamedLock {
        private final String name;
        private final Supplier<Lock> supplier;

        private NamedLock(String name, Supplier<Lock> supplier) {
            this.name = name;
            this.supplier = supplier;
        }
    }
}
