package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.sets.FineGrainedSet;
import edu.lsu.cct.parallelsuite.sets.LazySet;
import edu.lsu.cct.parallelsuite.sets.LockHashSet;
import edu.lsu.cct.parallelsuite.sets.OptimisticSet;

import java.util.LinkedList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class TestSets {
    private static final int THREADS = 12;
    private static final int WORK_SIZE = 1000 / THREADS;
    private static final int WORK_RANGE = 15;

    private static final ExecutorService pool = Executors.newFixedThreadPool(THREADS, Misc.getDaemonThreadFactory());

    private static abstract class Controller<T> {
        protected final Set<T> setImpl;

        public Controller(Supplier<Set<T>> setSupplier) {
            this.setImpl = setSupplier.get();
        }

        public abstract void test();
    }

    private static class ControllerA extends Controller<Integer> {
        public ControllerA(Supplier<Set<Integer>> setSupplier) {
            super(setSupplier);
        }

        @Override
        public void test() {
            var futs = new LinkedList<CompletableFuture<?>>();

            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;

                futs.add(CompletableFuture.runAsync(() -> {
                    boolean x;

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = i * THREADS + threadId;

                        x = setImpl.add(token);
                        assert x;
                        x = setImpl.contains(token);
                        assert x;
                        x = setImpl.remove(token);
                        assert x;
                        x = setImpl.contains(token);
                        assert !x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.add(i * THREADS + threadId);
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.contains(i * THREADS + threadId);
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.remove(i * THREADS + threadId);
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.contains(i * THREADS + threadId);
                        assert !x;
                    }
                }, pool));
            }

            for (var fut : futs) {
                fut.join();
            }
        }
    }

    private static class ControllerB extends Controller<String> {
        public ControllerB(Supplier<Set<String>> setSupplier) {
            super(setSupplier);
        }

        @Override
        public void test() {
            var futs = new LinkedList<CompletableFuture<?>>();

            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;

                futs.add(CompletableFuture.runAsync(() -> {
                    boolean x;

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = mkString(threadId, i);

                        x = setImpl.add(token);
                        assert x;
                        x = setImpl.contains(token);
                        assert x;
                        x = setImpl.remove(token);
                        assert x;
                        x = setImpl.contains(token);
                        assert !x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.add(mkString(threadId, i));
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.contains(mkString(threadId, i));
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.remove(mkString(threadId, i));
                        assert x;
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        x = setImpl.contains(mkString(threadId, i));
                        assert !x;
                    }
                }, pool));
            }

            for (var fut : futs) {
                fut.join();
            }
        }
    }

    private static class ControllerC extends Controller<String> {
        private final Set<String> referenceImpl = ConcurrentHashMap.newKeySet();
        private final long baseSeed;
        private final ThreadLocal<Random> rand = ThreadLocal.withInitial(Random::new);

        public ControllerC(Supplier<Set<String>> setSupplier) {
            super(setSupplier);
            baseSeed = rand.get().nextLong();
        }

        public ControllerC(Supplier<Set<String>> setSupplier, long baseSeed) {
            super(setSupplier);
            this.baseSeed = baseSeed;
        }

        @Override
        public void test() {
            mutate(referenceImpl);
            mutate(setImpl);

            for (String e : referenceImpl) {
                assert setImpl.contains(e);
            }
        }

        private void mutate(final Set<String> set) {
            var futs = new LinkedList<CompletableFuture<?>>();

            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;

                futs.add(CompletableFuture.runAsync(() -> {
                    rand.get().setSeed(baseSeed + threadId);

                    for (int i = 0; i < WORK_SIZE; i++) {
                        set.add(mkString(threadId, rand.get().nextInt(WORK_RANGE)));
                        set.remove(mkString(threadId, rand.get().nextInt(WORK_RANGE)));
                    }
                }, pool));
            }

            for (var fut : futs) {
                fut.join();
            }
        }
    }

    private static String mkString(int threadId, int n) {
        return String.format("%d_%d", threadId, n);
    }

    private static void testSet(Supplier<Set<Integer>> intSetSupplier, Supplier<Set<String>> stringSetSupplier) {
        Controller<?> testA = new ControllerA(intSetSupplier),
                      testB = new ControllerB(stringSetSupplier),
                      testC = new ControllerC(stringSetSupplier);

        testA.test();
        testB.test();
        testC.test();
    }

    private static void testSets() {
        // Sanity check: Standard Java implementation
        testSet(ConcurrentHashMap::newKeySet, ConcurrentHashMap::newKeySet);

        testSet(LockHashSet::new, LockHashSet::new);
        testSet(FineGrainedSet::new, FineGrainedSet::new);
        testSet(OptimisticSet::new, OptimisticSet::new);
        testSet(LazySet::new, LazySet::new);
    }

    private static final int TIMES = 5;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        for (int i = 0; i < TIMES; i++) {
            testSets();
        }
    }
}
