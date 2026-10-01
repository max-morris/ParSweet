package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ALock implements SlimLock {
    // 64-bit, like C++'s std::atomic<usize>. A 32-bit counter goes negative
    // after 2^31 acquisitions, and % then yields a negative slot, which
    // throws ArrayIndexOutOfBoundsException. floorMod would not help: it
    // breaks the slot sequence instead (see OptimizedALock). At one
    // acquisition per nanosecond, 2^63 takes ~292 years.
    private final AtomicLong tail = new AtomicLong(0);
    private final AtomicBoolean[] flags;
    // Slot of the thread that holds the lock. Only the holder touches it:
    // written after acquiring, read in unlock() before the release. The
    // flag hand-off (volatile set, then volatile get by the next holder)
    // orders each holder's accesses before the next's, so a plain field is
    // race-free and avoids a ThreadLocal lookup on every acquire/release.
    private int heldSlot;

    public ALock(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        flags = new AtomicBoolean[threadCount];
        for (int i = 0; i < threadCount; i++) {
            flags[i] = new AtomicBoolean(i == 0);
        }
    }

    @Override
    public void lock() {
        var slot = (int) (tail.getAndIncrement() % flags.length);
        while (!flags[slot].get()) {
            Thread.yield();
        }
        flags[slot].set(false);
        heldSlot = slot;
    }

    @Override
    public void unlock() {
        var next = (heldSlot + 1) % flags.length;
        flags[next].set(true);
    }
}
