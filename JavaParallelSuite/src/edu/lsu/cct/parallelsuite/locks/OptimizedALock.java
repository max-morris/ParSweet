package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Anderson lock with flags spaced onto separate cache lines.
 * Each flag lives at a stride of 16 ints (64 bytes) inside an AtomicIntegerArray.
 */
public class OptimizedALock implements SlimLock {
    private static final int STRIDE = 16;

    private final ThreadLocal<Integer> mySlotIndex = new ThreadLocal<>();
    private final AtomicInteger tail = new AtomicInteger(0);
    private final AtomicIntegerArray flags;
    private final int threadCount;

    public OptimizedALock(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        this.threadCount = threadCount;
        this.flags = new AtomicIntegerArray(threadCount * STRIDE);
        flags.set(0, 1);
    }

    @Override
    public void lock() {
        var slot = Math.floorMod(tail.getAndIncrement(), threadCount);
        mySlotIndex.set(slot);
        var idx = slot * STRIDE;
        while (flags.get(idx) == 0) {
            Thread.yield();
        }
        flags.set(idx, 0);
    }

    @Override
    public void unlock() {
        var slot = mySlotIndex.get();
        var next = (slot + 1) % threadCount;
        flags.set(next * STRIDE, 1);
    }
}
