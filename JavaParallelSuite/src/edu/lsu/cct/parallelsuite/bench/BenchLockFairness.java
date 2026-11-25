package edu.lsu.cct.parallelsuite.bench;

import edu.lsu.cct.parallelsuite.locks.ALock;
import edu.lsu.cct.parallelsuite.locks.BackoffLock;
import edu.lsu.cct.parallelsuite.locks.CLHLock;
import edu.lsu.cct.parallelsuite.locks.IdLock;
import edu.lsu.cct.parallelsuite.locks.MCSLock;
import edu.lsu.cct.parallelsuite.locks.OptimizedALock;
import edu.lsu.cct.parallelsuite.locks.SlimLock;
import edu.lsu.cct.parallelsuite.locks.TASLock;
import edu.lsu.cct.parallelsuite.locks.TIdLock;
import edu.lsu.cct.parallelsuite.locks.TTASLock;
import edu.lsu.cct.parallelsuite.locks.TwoCounterLock;
import edu.lsu.cct.parallelsuite.locks.TwoCounterSemaphore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Lock fairness benchmark: how fairly each lock hands itself out, and at what
 * cost.
 *
 * PSWEET_NTHREADS threads each take the lock PSWEET_WORK_PER_THREAD times.
 * Holding the lock, a thread does PSWEET_CS_WORK units of busy work; after
 * releasing it, PSWEET_NONCS_WORK units (defaults 50 and 0).
 *
 * Fairness is measured two ways:
 * (1) Overtaking. Just before calling lock(), a thread takes an arrival
 * number from a shared counter. Holding the lock, it checks whether a thread
 * that arrived later already got the lock first. A first-come, first-served
 * lock (e.g. TwoCounterLock) is never overtaken, except within the short
 * window between taking the number and entering lock().
 * (2) Wait times. Each thread times every lock() call. The benchmark reports
 * the median, 99th and 99.9th percentiles and the maximum over all of them.
 * An unfair lock can leave one thread waiting far longer than the rest.
 * Taking the arrival number and the timestamps adds the same small cost to
 * every lock.
 *
 * Before timing anything, every selected lock runs PSWEET_WARMUP_ROUNDS
 * times (default 5), untimed, so that the JIT has fully compiled the code;
 * one round is not enough, since each thread calls the timed method only
 * once. C++ needs no such warm-up. A thread can still be descheduled
 * between taking its arrival number and calling lock(), so a strictly
 * first-come, first-served lock can show an overtakenPct of up to a few
 * percent.
 *
 * As in every benchmark, the time of the whole run goes to PSWEET_OUT_PATH.
 * The fairness measures go to PSWEET_FAIRNESS_OUT_PATH (default
 * psweet_fairness.csv), in the same columns and format as C++'s.
 *
 * Besides the locks, the benchmark times TicketCvLock: a first-come,
 * first-served lock built from a lock and a condition variable, the usual
 * way to get a fair lock from a library that has none.
 *
 * C++ also times std::mutex, std::recursive_mutex and the HPX locks. The JDK
 * locks here are ReentrantLock, which covers both std roles, and
 * ReentrantLock(true), the JDK's own fair lock, which C++ has no counterpart
 * of; its name in the results is ReentrantLock(fair).
 */
public final class BenchLockFairness {
    private static final String FAIRNESS_HEADER =
            "lang,category,specific,machine,nThreads,workPerThread,hpxThreads,csWork,nonCsWork,ms,overtaken,"
            + "overtakenPct,p50WaitUs,p99WaitUs,p999WaitUs,maxWaitUs";

    // Written once per thread so that the JIT cannot drop the busy work.
    private static volatile long sink;

    private BenchLockFairness() {
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
        var params = new BenchParameters("java", "lockFairness");
        int csWork = BenchParameters.optionalInt("PSWEET_CS_WORK", 50);
        int nonCsWork = BenchParameters.optionalInt("PSWEET_NONCS_WORK", 0);
        String out = System.getenv("PSWEET_FAIRNESS_OUT_PATH");
        Path fairnessPath = Paths.get(out == null || out.isEmpty() ? "psweet_fairness.csv" : out);
        checkFairnessHeader(fairnessPath);

        int slots = andersonSlots();
        if (params.getNThreads() <= 0 || params.getNThreads() > slots) {
            throw new IllegalArgumentException("PSWEET_NTHREADS must be in 1.." + slots);
        }

        // slots matches C++ N_THREADS_ALLOC so the Anderson rings are the same size.
        List<NamedLock> locks = List.of(
                named("ReentrantLock", ReentrantLock::new),
                named("ReentrantLock(fair)", () -> new ReentrantLock(true)),
                named("ALock", () -> new ALock(slots)),
                named("BackoffLock<1:17>", BackoffLock::new),
                named("CLHLock", CLHLock::new),
                named("IdLock", IdLock::new),
                named("MCSLock", MCSLock::new),
                named("OptimizedALock", () -> new OptimizedALock(slots)),
                named("TASLock", TASLock::new),
                named("TIdLock", TIdLock::new),
                named("TTASLock", TTASLock::new),
                named("TwoCounterLock", TwoCounterLock::new),
                named("TwoCounterSemaphore<1>", () -> new TwoCounterSemaphore(1)),
                named("TicketCvLock<ReentrantLock>", TicketCvLock::new));

        var selected = new ArrayList<NamedLock>();
        var names = new ArrayList<String>();
        for (NamedLock lock : locks) {
            names.add(lock.name);
            if (params.getWhich() == null || params.getWhich().equals(lock.name)) {
                selected.add(lock);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("PSWEET_WHICH=" + params.getWhich()
                    + " names no BenchLockFairness test; it accepts " + String.join(", ", names));
        }

        int warmupRounds = BenchParameters.optionalInt("PSWEET_WARMUP_ROUNDS", 5);
        for (int round = 0; round < warmupRounds; round++) {
            for (NamedLock lock : selected) {
                runOne(lock.supplier, params.getNThreads(), params.getWorkPerThread(), csWork, nonCsWork);
            }
        }

        for (NamedLock lock : selected) {
            System.out.println("Bench: " + lock.name);
            Result result = runOne(lock.supplier, params.getNThreads(), params.getWorkPerThread(), csWork, nonCsWork);
            params.writeResult(lock.name, result.ms);
            writeFairnessResult(fairnessPath, params, lock.name, csWork, nonCsWork, result);
            params.coolOff();
        }
    }

    /**
     * A first-come, first-served lock built from a ReentrantLock and a
     * condition variable: lock() draws a ticket and sleeps until unlock() has
     * served every earlier ticket. The ticket is drawn before taking the
     * ReentrantLock, which is itself unfair, so that the order of lock()
     * calls decides. It is not reentrant.
     */
    private static final class TicketCvLock implements SlimLock {
        private final ReentrantLock mutex = new ReentrantLock();
        private final Condition turn = mutex.newCondition();
        private final AtomicLong nextTicket = new AtomicLong();
        private long nowServing; // guarded by mutex

        @Override
        public void lock() {
            long ticket = nextTicket.getAndIncrement();
            // Waiting and serving both happen under mutex, so no wakeup is
            // lost between the check and the wait.
            mutex.lock();
            try {
                while (nowServing != ticket) {
                    turn.awaitUninterruptibly();
                }
            } finally {
                mutex.unlock();
            }
        }

        @Override
        public void unlock() {
            mutex.lock();
            try {
                nowServing++;
                // Every waiter wakes and checks whether its ticket is the one now served.
                turn.signalAll();
            } finally {
                mutex.unlock();
            }
        }
    }

    /** State shared by the threads of one run. Only arrivals is touched outside the lock. */
    private static final class Shared {
        final AtomicLong arrivals = new AtomicLong();
        long nextUnserved; // one past the highest arrival served
        long overtaken;    // acquisitions a later arrival beat
        long acquisitions; // to check mutual exclusion
    }

    private static final class Result {
        long ms;
        long overtaken;
        double overtakenPct;
        double p50WaitUs;
        double p99WaitUs;
        double p999WaitUs;
        double maxWaitUs;
    }

    private static Result runOne(Supplier<Lock> supply, int nThreads, int work, int csWork, int nonCsWork)
            throws InterruptedException {
        Lock lock = supply.get();
        var shared = new Shared();
        // Allocated before timing starts.
        long[][] waits = new long[nThreads][work];
        var failure = new AtomicReference<Throwable>();

        long t0 = System.nanoTime();
        // All threads start taking turns together.
        var start = new CountDownLatch(nThreads);
        var workers = new Thread[nThreads];
        for (int t = 0; t < nThreads; t++) {
            long[] mine = waits[t];
            workers[t] = new Thread(() -> {
                try {
                    start.countDown();
                    start.await();
                    takeTurns(lock, shared, csWork, nonCsWork, mine);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        long t1 = System.nanoTime();
        if (failure.get() != null) {
            throw new IllegalStateException("a benchmark thread failed", failure.get());
        }

        Result result = summarize(shared, waits);
        result.ms = (t1 - t0) / 1_000_000L;
        return result;
    }

    /** One thread's share of a run: take the lock waits.length times, storing how long each lock() took, in ns. */
    private static void takeTurns(Lock lock, Shared shared, int csWork, int nonCsWork, long[] waits) {
        long mix = 0;
        for (int i = 0; i < waits.length; i++) {
            long t0 = System.nanoTime();
            long arrival = shared.arrivals.getAndIncrement();
            lock.lock();
            long t1;
            try {
                t1 = System.nanoTime();
                // An arrival number below nextUnserved means that some later arrival
                // has already been served: this acquisition was overtaken.
                if (arrival < shared.nextUnserved) {
                    shared.overtaken++;
                } else {
                    shared.nextUnserved = arrival + 1;
                }
                shared.acquisitions++;
                mix ^= busy(csWork);
            } finally {
                lock.unlock();
            }
            waits[i] = t1 - t0;
            mix ^= busy(nonCsWork);
        }
        sink = mix;
    }

    /** Merges the threads' wait times and computes the fairness measures. */
    private static Result summarize(Shared shared, long[][] waits) {
        int n = 0;
        for (long[] w : waits) {
            n += w.length;
        }
        long[] all = new long[n];
        int at = 0;
        for (long[] w : waits) {
            System.arraycopy(w, 0, all, at, w.length);
            at += w.length;
        }
        if (shared.acquisitions != n) {
            throw new IllegalStateException("the lock did not provide mutual exclusion");
        }

        var result = new Result();
        if (n == 0) {
            return result;
        }
        Arrays.sort(all);
        result.overtaken = shared.overtaken;
        result.overtakenPct = 100.0 * shared.overtaken / n;
        result.p50WaitUs = quantileUs(all, 0.5);
        result.p99WaitUs = quantileUs(all, 0.99);
        result.p999WaitUs = quantileUs(all, 0.999);
        result.maxWaitUs = all[n - 1] / 1e3;
        return result;
    }

    /** The smallest wait that at least a fraction q of all (sorted) waits do not exceed, in microseconds. */
    private static double quantileUs(long[] sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.max(rank, 1) - 1] / 1e3;
    }

    /**
     * Refuses to append to a fairness results file written with a different
     * set of columns; the rows would not line up with its header.
     */
    private static void checkFairnessHeader(Path path) {
        try {
            if (!Files.exists(path) || Files.size(path) == 0) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String header = reader.readLine();
                if (!FAIRNESS_HEADER.equals(header)) {
                    throw new IllegalArgumentException("PSWEET_FAIRNESS_OUT_PATH " + path + " has header \"" + header
                            + "\", expected \"" + FAIRNESS_HEADER + "\"; use a new output file");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read PSWEET_FAIRNESS_OUT_PATH " + path, e);
        }
    }

    /**
     * Appends one row of fairness measures, writing the header first if the
     * file is new. ms repeats the run's time from PSWEET_OUT_PATH, so that
     * this file stands on its own. hpxThreads is always 0 in Java.
     */
    private static void writeFairnessResult(Path path, BenchParameters params, String specific, int csWork,
                                            int nonCsWork, Result r) throws IOException {
        boolean writeHeader = !Files.exists(path) || Files.size(path) == 0;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (writeHeader) {
                writer.write(FAIRNESS_HEADER);
                writer.newLine();
            }
            writer.write(String.format(Locale.ROOT,
                    "%s,%s,%s,%s,%d,%d,0,%d,%d,%d,%d,%.4f,%.3f,%.3f,%.3f,%.3f",
                    params.getLang(), params.getCategory(), specific, params.getMachine(), params.getNThreads(),
                    params.getWorkPerThread(), csWork, nonCsWork, r.ms, r.overtaken, r.overtakenPct, r.p50WaitUs,
                    r.p99WaitUs, r.p999WaitUs, r.maxWaitUs));
            writer.newLine();
        }
    }

    /** About steps * 1 ns of dependent multiply-adds, the same recurrence as C++'s busyWork(). */
    private static long busy(int steps) {
        long x = steps;
        for (int i = 0; i < steps; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return x;
    }

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
