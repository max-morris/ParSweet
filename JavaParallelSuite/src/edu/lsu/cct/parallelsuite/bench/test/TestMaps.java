package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.bench.Misc;
import edu.lsu.cct.parallelsuite.maps.LockHashMap;
import edu.lsu.cct.parallelsuite.maps.SetBasedMap;
import edu.lsu.cct.parallelsuite.sets.FineGrainedSet;
import edu.lsu.cct.parallelsuite.sets.LazySet;
import edu.lsu.cct.parallelsuite.sets.OptimisticSet;

import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class TestMaps {
    private static final int THREADS = Misc.nThreads(12);
    private static final int WORK_SIZE = Math.max(1, 1000 / THREADS);
    private static final int WORK_RANGE = 15;

    private static final ExecutorService pool = Executors.newFixedThreadPool(THREADS, Misc.getDaemonThreadFactory());

    private static abstract class Controller<K, V> {
        protected final Map<K, V> mapImpl;

        public Controller(Supplier<Map<K, V>> mapSupplier) {
            this.mapImpl = mapSupplier.get();
        }

        public abstract void test();
    }

    private static class ControllerA extends Controller<Integer, Integer> {
        public ControllerA(Supplier<Map<Integer, Integer>> mapSupplier) {
            super(mapSupplier);
        }

        @Override
        public void test() {
            var futs = new LinkedList<CompletableFuture<?>>();

            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;

                futs.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = i * THREADS + threadId;

                        mapImpl.put(token, token);
                        assert Objects.equals(token, mapImpl.get(token));
                        mapImpl.remove(token);
                        assert Objects.isNull(mapImpl.get(token));
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = i * THREADS + threadId;
                        mapImpl.put(token, token);
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = i * THREADS + threadId;
                        assert Objects.equals(token, mapImpl.get(token));
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        mapImpl.remove(i * THREADS + threadId);
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = i * THREADS + threadId;
                        assert Objects.isNull(mapImpl.get(token));
                    }
                }, pool));
            }

            for (var fut : futs) {
                fut.join();
            }
        }
    }

    private static class ControllerB extends Controller<String, String> {
        public ControllerB(Supplier<Map<String, String>> mapSupplier) {
            super(mapSupplier);
        }

        @Override
        public void test() {
            var futs = new LinkedList<CompletableFuture<?>>();

            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;

                futs.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = mkString(threadId, i);
                        mapImpl.put(token, token);
                        assert Objects.equals(token, mapImpl.get(token));
                        mapImpl.remove(token);
                        assert Objects.isNull(mapImpl.get(token));
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = mkString(threadId, i);
                        mapImpl.put(token, token);
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = mkString(threadId, i);
                        assert Objects.equals(token, mapImpl.get(token));
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        mapImpl.remove(mkString(threadId, i));
                    }

                    for (int i = 0; i < WORK_SIZE; i++) {
                        var token = mkString(threadId, i);
                        assert Objects.isNull(mapImpl.get(token));
                    }
                }, pool));
            }

            for (var fut : futs) {
                fut.join();
            }
        }
    }

    private static class ControllerC extends Controller<String, String> {
        private final Map<String, String> referenceImpl = new ConcurrentHashMap<>();
        private final long baseSeed;
        private final ThreadLocal<Random> rand = ThreadLocal.withInitial(Random::new);

        public ControllerC(Supplier<Map<String, String>> mapSupplier) {
            super(mapSupplier);
            baseSeed = rand.get().nextLong();
        }

        @Override
        public void test() {
            mutate(referenceImpl);
            mutate(mapImpl);

            for (int t = 0; t < THREADS; t++) {
                for (int i = 0; i < WORK_RANGE; i++) {
                    var token = mkString(t, i);
                    assert Objects.equals(referenceImpl.get(token), mapImpl.get(token));
                }
            }
        }

        private void mutate(final Map<String, String> map) {
            var futs = new LinkedList<CompletableFuture<?>>();
            for (int t = 0; t < THREADS; t++) {
                final var threadId = t;
                futs.add(CompletableFuture.runAsync(() -> {
                    rand.get().setSeed(baseSeed + threadId);
                    for (int i = 0; i < WORK_SIZE; i++) {
                        var a = mkString(threadId, rand.get().nextInt(WORK_RANGE));
                        map.put(a, a);
                        var b = mkString(threadId, rand.get().nextInt(WORK_RANGE));
                        map.remove(b);
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

    private static void testMap(Supplier<Map<Integer, Integer>> intMapSupplier,
                                Supplier<Map<String, String>> stringMapSupplier) {
        new ControllerA(intMapSupplier).test();
        new ControllerB(stringMapSupplier).test();
        new ControllerC(stringMapSupplier).test();
    }

    private static void testMaps() {
        testMap(ConcurrentHashMap::new, ConcurrentHashMap::new);
        testMap(LockHashMap::new, LockHashMap::new);
        testMap(() -> new SetBasedMap<>(FineGrainedSet::new), () -> new SetBasedMap<>(FineGrainedSet::new));
        testMap(() -> new SetBasedMap<>(OptimisticSet::new), () -> new SetBasedMap<>(OptimisticSet::new));
        testMap(() -> new SetBasedMap<>(LazySet::new), () -> new SetBasedMap<>(LazySet::new));
    }

    private static final int TIMES = 5;

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        for (int i = 0; i < TIMES; i++) {
            testMaps();
        }
        pool.shutdown();
        System.out.println("All tests passed.");
    }
}
