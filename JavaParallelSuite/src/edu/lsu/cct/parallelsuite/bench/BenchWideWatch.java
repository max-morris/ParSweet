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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
 * Wide-watch completion (benchmarks.md candidate 11), blocking-lock policy only.
 *
 * Each delayed operation watches every key. An event scans all of that
 * operation's keys while holding its lock. The scan is the critical section:
 * watch width, not the lock algorithm, sets the hold time. tryLock-helping
 * and the async completer are not in this program. Suite spin locks throw
 * from tryLock, so those policies cannot sweep the same backends.
 *
 * C++ also times std::mutex and std::recursive_mutex. The JDK lock here is
 * ReentrantLock, which covers both roles.
 */
public final class BenchWideWatch {
    private static final int DEFAULT_WATCHES = 32;
    private static final int DEFAULT_OPS = 8;

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
        int watches = BenchParameters.optionalInt("PSWEET_WATCHES", DEFAULT_WATCHES);
        int nOps = BenchParameters.optionalInt("PSWEET_OPS", DEFAULT_OPS);
        int slots = andersonSlots();
        if (params.getNThreads() <= 0 || params.getNThreads() > slots) {
            throw new IllegalArgumentException("PSWEET_NTHREADS must be in 1.." + slots);
        }
        if (params.getWorkPerThread() <= 0 || watches <= 0 || nOps <= 0) {
            throw new IllegalArgumentException("PSWEET_WORK_PER_THREAD, PSWEET_WATCHES, and PSWEET_OPS must be positive");
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
            long ms = measure(() -> runOne(lock.supplier, params.getNThreads(), params.getWorkPerThread(), watches, nOps));
            params.writeResult(lock.name, ms);
            params.coolOff();
        }
        if (params.getWhich() != null && !ran) {
            throw new IllegalArgumentException("PSWEET_WHICH does not name a lock: " + params.getWhich());
        }
    }

    private static void runOne(Supplier<Lock> locks, int nThreads, int work, int watches, int nOps) {
        var ops = new ArrayList<Op>(nOps);
        for (int i = 0; i < nOps; i++) {
            ops.add(new Op(locks.get(), watches));
        }

        // A fixed pool, not the common pool: PSWEET_NTHREADS is how many handlers run at once.
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            var workers = new ArrayList<Future<?>>(nThreads);
            for (int t = 0; t < nThreads; t++) {
                workers.add(pool.submit(() -> {
                    for (int round = 0; round < work; round++) {
                        for (int key = 0; key < watches; key++) {
                            // Op index order is the only lock order, so two handlers cannot deadlock.
                            for (Op op : ops) {
                                op.onKey(key);
                            }
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

        for (Op op : ops) {
            for (int key = 0; key < watches; key++) {
                if ((byte) Op.SEEN.getOpaque(op.seen, key) != 1) {
                    throw new IllegalStateException("wideWatch missed a completion");
                }
            }
        }
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

    private static final class Op {
        // Opaque byte accesses match C++ relaxed atomic<char>: one byte,
        // no acquire/release. Plain loads can be deleted; volatile loads
        // are acquire and four times wider.
        private static final VarHandle SEEN = MethodHandles.arrayElementVarHandle(byte[].class);

        private final Lock lock;
        private final byte[] seen;

        private Op(Lock lock, int watches) {
            this.lock = lock;
            this.seen = new byte[watches];
        }

        private void onKey(int key) {
            int mix = 0;
            lock.lock();
            try {
                if ((byte) SEEN.getOpaque(seen, key) == 0) {
                    SEEN.setOpaque(seen, key, (byte) 1);
                }
                mix = 1;
                for (int k = 0; k < seen.length; k++) {
                    int bit = (byte) SEEN.getOpaque(seen, k) == 0 ? 0 : 1;
                    // Wrapping multiply keeps every key in the result. A plain
                    // countdown would let the JIT turn a finished scan into O(1).
                    mix = mix * 33 + bit;
                }
            } finally {
                lock.unlock();
            }
            BlackBox.consume(mix);
        }
    }
}
