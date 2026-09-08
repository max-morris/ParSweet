package edu.lsu.cct.parallelsuite.locks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class LockFactory {
    private LockFactory() {}

    public static final String[] ALL = {
            "ReentrantLock",
            "TASLock",
            "TTASLock",
            "ALock",
            "OptimizedALock",
            "BackoffLock<1:17>",
            "CLHLock",
            "MCSLock",
            "IdLock",
            "TIdLock",
            "TwoCounterLock"
    };

    public static final Set<String> FAIR = Set.of(
            "ALock",
            "OptimizedALock",
            "CLHLock",
            "MCSLock",
            "TwoCounterLock"
    );

    public static Supplier<Lock> named(String name, int nThreads) {
        switch (name) {
            case "ReentrantLock":
                return ReentrantLock::new;
            case "TASLock":
                return TASLock::new;
            case "TTASLock":
                return TTASLock::new;
            case "ALock":
                return () -> new ALock(nThreads);
            case "OptimizedALock":
                return () -> new OptimizedALock(nThreads);
            case "BackoffLock<1:17>":
            case "BackoffLock":
                return BackoffLock::new;
            case "CLHLock":
                return CLHLock::new;
            case "MCSLock":
                return MCSLock::new;
            case "IdLock":
                return IdLock::new;
            case "TIdLock":
                return TIdLock::new;
            case "TwoCounterLock":
                return TwoCounterLock::new;
            default:
                throw new IllegalArgumentException("unknown lock: " + name);
        }
    }

    public static Map<String, Supplier<Lock>> all(int nThreads) {
        var map = new LinkedHashMap<String, Supplier<Lock>>();
        for (var name : ALL) {
            map.put(name, named(name, nThreads));
        }
        return map;
    }
}
