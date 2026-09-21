package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Anderson lock matching C++ OptimizedALock.
 *
 * threadCount is the maximum number of threads that may be inside lock()
 * at once. Two waiters on the same slot corrupt the flag handshake.
 *
 * Flags are strided 16 ints (64 bytes), the usual x86 cache line and the
 * common value of C++ hardware_destructive_interference_size. 128-byte
 * lines can still false-share adjacent slots. Java ALock's AtomicBoolean
 * objects are separately allocated but can still sit on one line; the
 * stride is what makes this variant the padded one.
 */
public class OptimizedALock implements SlimLock {
    // 16 ints * 4 bytes. Assumes 64-byte lines; see class comment.
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
        // floorMod: AtomicInteger wraps to negative; Java % would then be negative.
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
        var next = Math.floorMod(slot + 1, threadCount);
        flags.set(next * STRIDE, 1);
    }
}
