package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anderson array lock shared by ALock and OptimizedALock, matching C++
 * detail::AndersonLock. The two differ only in stride, the spacing (in
 * ints) between slot flags: 1 packs the flags densely, so neighboring
 * slots false-share; 16 gives each slot its own 64-byte line.
 *
 * threadCount is the maximum number of threads that may be inside lock()
 * at once. Two waiters on the same slot corrupt the flag handshake.
 *
 * Layout of the one int array, in cells of stride ints: one leading pad
 * cell, then one cell per slot (slot k at index (k + 1) * stride), then
 * heldSlot's cell. With stride 16:
 * - The array itself is not cache-line aligned, but slot 0 is at least 64
 *   bytes past the end of the header, so no 64-byte line holds both slot 0
 *   and the header or anything allocated just before the array (such as
 *   the AtomicIntegerArray wrapper and the tail counter).
 * - heldSlot is written on every acquisition, so it gets its own line
 *   rather than sitting among the lock's fields, which every waiter rereads
 *   while spinning, or next to a flag.
 * - No trailing pad is needed: each flag, and heldSlot, is the first int
 *   of its own cell, so the rest of that cell keeps it off the next cell
 *   and off whatever follows the array.
 * C++ OptimizedALock gets the same isolation from alignas. With stride 1
 * the pad and heldSlot cells are single ints and isolate nothing; heldSlot
 * shares a line with the last flags, as in C++ ALock.
 */
abstract class AndersonLock implements SlimLock {
    // 64-bit, like C++'s std::atomic<usize>. A 32-bit counter wraps after
    // 2^31 acquisitions. Plain % then yields a negative slot; floorMod keeps
    // it in range, but unless threadCount is a power of two the slot
    // sequence jumps back (e.g. 7 -> 4 for threadCount 12) while the release
    // goes to the next slot (8), so the lock hangs or admits two threads at
    // once. At one acquisition per nanosecond, 2^63 takes ~292 years.
    private final AtomicLong tail = new AtomicLong(0);
    // The slot flags plus heldSlot's cell; see the class comment.
    private final AtomicIntegerArray flags;
    private final int threadCount;
    private final int stride;
    // Array index of heldSlot, the slot of the thread that holds the lock.
    // Only the holder touches it: written after acquiring, read in unlock()
    // before the release. The flag hand-off (volatile set, then volatile get
    // by the next holder) orders each holder's accesses before the next's,
    // so plain (getPlain/setPlain) access is race-free and avoids a
    // ThreadLocal lookup on every acquire/release.
    private final int heldSlotIndex;

    protected AndersonLock(int threadCount, int stride) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be positive");
        }
        // (threadCount + 2) * stride must not overflow int.
        if (threadCount > Integer.MAX_VALUE / stride - 2) {
            throw new IllegalArgumentException("threadCount too large");
        }
        this.threadCount = threadCount;
        this.stride = stride;
        this.flags = new AtomicIntegerArray((threadCount + 2) * stride);
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

    // Array index of the cell after the leading pad and `slot` slot cells:
    // a slot's flag, or heldSlot's cell for slot == threadCount.
    private int flagIndex(int slot) {
        return (slot + 1) * stride;
    }
}
