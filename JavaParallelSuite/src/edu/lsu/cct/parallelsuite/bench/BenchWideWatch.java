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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per flag. A short job locks one flag. A long job locks every flag
 * from 0 upward, does busy work while holding them all, and unlocks from the
 * last flag back down. That one order is what keeps the long jobs from
 * deadlocking with each other or with a short job. The lower thread ids run
 * that mix. When there is more than one thread and there are short jobs,
 * the higher ids, half of the threads rounded down, only run short jobs.
 * Nothing holds those threads in a round, so overlap with a long job is
 * not guaranteed. One thread, or zero short jobs, means every thread runs
 * the long jobs.
 *
 * C++ also times std::mutex and std::recursive_mutex. The JDK lock here is
 * ReentrantLock, which covers both roles.
 */
public final class BenchWideWatch {
    private static final int DEFAULT_FLAGS = 32;
    private static final int DEFAULT_SHORT = 8;

    private BenchWideWatch() {
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
        var params = new BenchParameters("java", "wideWatch");
        int nFlags = BenchParameters.optionalInt("PSWEET_WATCHES", DEFAULT_FLAGS);
        int nShort = BenchParameters.optionalInt("PSWEET_OPS", DEFAULT_SHORT);
        int slots = andersonSlots();
        if (params.getNThreads() <= 0 || params.getNThreads() > slots) {
            throw new IllegalArgumentException("PSWEET_NTHREADS must be in 1.." + slots);
        }
        if (params.getWorkPerThread() <= 0 || nFlags <= 0 || nShort < 0) {
            throw new IllegalArgumentException("PSWEET_WORK_PER_THREAD and PSWEET_WATCHES must be positive");
        }

        // slots matches C++ N_THREADS_ALLOC so the Anderson rings are the same size.
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
            long ms = measure(() -> runOne(lock.supplier, params.getNThreads(), params.getWorkPerThread(), nFlags, nShort));
            params.writeResult(lock.name, ms);
            params.coolOff();
        }
        if (params.getWhich() != null && !ran) {
            throw new IllegalArgumentException("PSWEET_WHICH does not name a lock: " + params.getWhich());
        }
    }

    private static void runOne(Supplier<Lock> supply, int nThreads, int work, int nFlags, int nShort) {
        var locks = new Lock[nFlags];
        var hits = new Hit[nFlags];
        for (int i = 0; i < nFlags; i++) {
            locks[i] = supply.get();
            hits[i] = new Hit();
        }

        // A fixed pool, not the common pool: PSWEET_NTHREADS is how many handlers run at once.
        int nLong = longWorkers(nThreads, nShort);
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            var workers = new ArrayList<Future<?>>(nThreads);
            for (int t = 0; t < nThreads; t++) {
                final int threadId = t;
                final boolean shortsOnly = threadId >= nLong;
                workers.add(pool.submit(() -> {
                    for (int round = 0; round < work; round++) {
                        for (int step = 0; step < nShort; step++) {
                            int flag = flagFor(round, step, threadId, nShort, nFlags);
                            shortJob(locks[flag], hits[flag]);
                        }
                        if (!shortsOnly) {
                            longJob(locks, hits);
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

        long[] expect = expected(nThreads, work, nFlags, nShort, nLong);
        for (int flag = 0; flag < nFlags; flag++) {
            if (hits[flag].value != expect[flag]) {
                throw new IllegalStateException("wideWatch flag " + flag + " count " + hits[flag].value + " expected " + expect[flag]);
            }
        }
    }

    // Which flag a short job locks. The same formula builds the expected counts.
    private static int flagFor(long round, int step, long threadId, int nShort, int nFlags) {
        long index = round * nShort + step + threadId;
        return (int) (index % nFlags);
    }

    // How many threads run long jobs. The rest only run short jobs. A single
    // thread has to run the long jobs itself, and zero short jobs means there
    // is nothing else for a thread to do.
    private static int longWorkers(int nThreads, int nShort) {
        if (nShort <= 0 || nThreads <= 1) {
            return nThreads;
        }
        return nThreads - nThreads / 2;
    }

    private static long[] expected(int nThreads, int work, int nFlags, int nShort, int nLong) {
        var expect = new long[nFlags];
        // Each long job adds one to every flag. Short jobs are counted below.
        long longs = nLong * (long) work;
        for (int flag = 0; flag < nFlags; flag++) {
            expect[flag] = longs;
        }
        for (int t = 0; t < nThreads; t++) {
            for (int round = 0; round < work; round++) {
                for (int step = 0; step < nShort; step++) {
                    expect[flagFor(round, step, t, nShort, nFlags)] += 1;
                }
            }
        }
        return expect;
    }

    private static void shortJob(Lock lock, Hit hit) {
        lock.lock();
        try {
            hit.value += 1;
            BlackBox.consume(busy(1));
        } finally {
            lock.unlock();
        }
    }

    private static void longJob(Lock[] locks, Hit[] hits) {
        int held = 0;
        try {
            for (int i = 0; i < locks.length; i++) {
                locks[i].lock();
                held++;
                hits[i].value += 1;
            }
            BlackBox.consume(busy(locks.length));
        } finally {
            for (int i = held - 1; i >= 0; i--) {
                locks[i].unlock();
            }
        }
    }

    // Busy work whose length is the argument. The multiply wraps in a signed
    // int, which Java defines, and BlackBox.consume keeps the loop alive.
    private static int busy(int steps) {
        int mix = 1;
        for (int i = 0; i < steps; i++) {
            mix = mix * 33 + 1;
        }
        return mix;
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

    // value plus seven longs is 64 bytes of payload, so two counters do not
    // share the line that holds value. The trial is measuring the locks.
    private static final class Hit {
        private long value;
        private long pad0, pad1, pad2, pad3, pad4, pad5, pad6;
    }
}
