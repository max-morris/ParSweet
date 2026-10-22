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
 * Accumulator benchmark. Each flag has its own lock. A short job locks one
 * flag, does busy work, and increments that flag. A long job locks every
 * flag from first to last, does busy work while holding them, increments
 * each, and unlocks from last back to first. That order keeps the long jobs
 * from deadlocking with each other or with a short job.
 * When PSWEET_OPS is positive and even and more than one thread is running,
 * the higher ids, half of the threads rounded down, only run short jobs.
 * They do a fixed number of short jobs and then finish. One thread, an odd
 * count, or zero means every thread runs the long jobs.
 *
 * C++ also times std::mutex and std::recursive_mutex. The JDK lock here is
 * ReentrantLock, which covers both roles.
 */
public final class BenchAccumulator {
    private static final int DEFAULT_FLAGS = 32;
    private static final int DEFAULT_SHORT = 7;

    private BenchAccumulator() {
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
        var params = new BenchParameters("java", "accumulator");
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
                throw new IllegalStateException("accumulator flag " + flag + " count " + hits[flag].value + " expected " + expect[flag]);
            }
        }
    }

    // Which flag a short job locks. The same formula builds the expected counts.
    private static int flagFor(long round, int step, long threadId, int nShort, int nFlags) {
        long index = round * nShort + step + threadId;
        return (int) (index % nFlags);
    }

    // How many threads run long jobs. The rest only run short jobs, and only
    // when the short-job count is positive and even. A single thread has to
    // run the long jobs itself. An odd count, or zero, means every thread
    // runs them. Half, rounded down, are the higher ids.
    private static int longWorkers(int nThreads, int nShort) {
        if (nShort <= 0 || nShort % 2 != 0 || nThreads <= 1) {
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
            int mix = busy(1);
            hit.value += 1;
            BlackBox.consume(mix);
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
            }
            int mix = busy(locks.length);
            for (int i = 0; i < hits.length; i++) {
                hits[i].value += 1;
            }
            BlackBox.consume(mix);
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
