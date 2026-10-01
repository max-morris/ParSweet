package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ALock implements SlimLock {
    private final ThreadLocal<Integer> mySlotIndex = new ThreadLocal<>();
    private final AtomicInteger tail = new AtomicInteger(0);
    private final AtomicBoolean[] flags;

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
        var slot = tail.getAndIncrement() % flags.length;
        mySlotIndex.set(slot);
        while (!flags[slot].get()) {
            Thread.yield();
        }
        flags[slot].set(false);
    }

    @Override
    public void unlock() {
        var slot = mySlotIndex.get();
        var next = (slot + 1) % flags.length;
        flags[next].set(true);
    }
}
