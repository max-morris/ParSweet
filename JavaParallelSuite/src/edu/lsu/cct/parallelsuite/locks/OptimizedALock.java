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
 *
 * Slot k lives at index (k + 1) * STRIDE: one STRIDE of ints pads the
 * front of the array. The array itself is not cache-line aligned, but
 * slot 0 is then at least 64 bytes past the end of the header, so no
 * 64-byte line holds both slot 0 and the header or anything allocated
 * just before the array (such as the AtomicIntegerArray wrapper and the
 * tail counter). No trailing pad is needed: each flag is the first int of
 * its own STRIDE-int cell, so the rest of the last cell keeps the last
 * slot off whatever follows the array. C++ gets this from alignas.
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
        // (threadCount + 1) * STRIDE must fit in an int array length.
        if (threadCount > Integer.MAX_VALUE / STRIDE - 1) {
            throw new IllegalArgumentException("threadCount too large");
        }
        this.threadCount = threadCount;
        this.flags = new AtomicIntegerArray((threadCount + 1) * STRIDE);
        flags.set(flagIndex(0), 1);
    }

    @Override
    public void lock() {
        // floorMod: AtomicInteger wraps to negative; Java % would then be negative.
        var slot = Math.floorMod(tail.getAndIncrement(), threadCount);
        mySlotIndex.set(slot);
        var idx = flagIndex(slot);
        while (flags.get(idx) == 0) {
            Thread.yield();
        }
        flags.set(idx, 0);
    }

    @Override
    public void unlock() {
        var slot = mySlotIndex.get();
        var next = (slot + 1) % threadCount;
        flags.set(flagIndex(next), 1);
    }

    // Array index of slot's flag; see the class comment for the layout.
    private static int flagIndex(int slot) {
        return (slot + 1) * STRIDE;
    }
}
