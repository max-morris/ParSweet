package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

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
 * tail counter). C++ gets this from alignas.
 *
 * One more STRIDE cell after the last slot holds heldSlot, the holder's
 * slot (see heldSlotIndex). It is written on every acquisition, so it gets
 * its own line rather than sitting among the lock's fields, which every
 * waiter rereads while spinning, or next to a flag.
 *
 * No trailing pad is needed: each flag, and heldSlot, is the first int of
 * its own STRIDE-int cell, so the rest of that cell keeps it off the next
 * cell and off whatever follows the array.
 */
public class OptimizedALock implements SlimLock {
    // 16 ints * 4 bytes. Assumes 64-byte lines; see class comment.
    private static final int STRIDE = 16;

    // 64-bit, like C++'s std::atomic<usize>. A 32-bit counter wraps after
    // 2^31 acquisitions, and unless threadCount is a power of two the slot
    // sequence then jumps back (e.g. 7 -> 4 for threadCount 12) while the
    // release goes to the next slot (8), so the lock hangs or admits two
    // threads at once. At one acquisition per nanosecond, 2^63 takes ~292
    // years.
    private final AtomicLong tail = new AtomicLong(0);
    // The slot flags plus heldSlot's cell; see the class comment.
    private final AtomicIntegerArray flags;
    private final int threadCount;
    // Array index of heldSlot, the slot of the thread that holds the lock.
    // Only the holder touches it: written after acquiring, read in unlock()
    // before the release. The flag hand-off (volatile set, then volatile get
    // by the next holder) orders each holder's accesses before the next's,
    // so plain (getPlain/setPlain) access is race-free and avoids a
    // ThreadLocal lookup on every acquire/release.
    private final int heldSlotIndex;

    public OptimizedALock(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        // (threadCount + 2) * STRIDE must fit in an int array length.
        if (threadCount > Integer.MAX_VALUE / STRIDE - 2) {
            throw new IllegalArgumentException("threadCount too large");
        }
        this.threadCount = threadCount;
        this.flags = new AtomicIntegerArray((threadCount + 2) * STRIDE);
        // The cell one past the last slot.
        this.heldSlotIndex = flagIndex(threadCount);
        flags.set(flagIndex(0), 1);
    }

    @Override
    public void lock() {
        // tail never goes negative in practice (see above), so % is enough.
        var slot = (int) (tail.getAndIncrement() % threadCount);
        var idx = flagIndex(slot);
        while (flags.get(idx) == 0) {
            Thread.yield();
        }
        flags.set(idx, 0);
        flags.setPlain(heldSlotIndex, slot);
    }

    @Override
    public void unlock() {
        var next = (flags.getPlain(heldSlotIndex) + 1) % threadCount;
        flags.set(flagIndex(next), 1);
    }

    // Array index of slot's flag; see the class comment for the layout.
    private static int flagIndex(int slot) {
        return (slot + 1) * STRIDE;
    }
}
