package edu.lsu.cct.parallelsuite.locks;

/**
 * Padded Anderson lock matching C++ OptimizedALock; see AndersonLock.
 *
 * Flags are strided 16 ints (64 bytes), the usual x86 cache line and the
 * common value of C++ hardware_destructive_interference_size. 128-byte
 * lines can still false-share adjacent slots.
 */
public class OptimizedALock extends AndersonLock {
    // 16 ints * 4 bytes. Assumes 64-byte lines; see class comment.
    private static final int STRIDE = 16;

    public OptimizedALock(int threadCount) {
        super(threadCount, STRIDE);
    }
}
