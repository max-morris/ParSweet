package edu.lsu.cct.parallelsuite.locks;

/**
 * Unpadded Anderson lock matching C++ ALock; see AndersonLock.
 *
 * Flags are packed one int apart, so neighboring slots share cache lines,
 * as with C++ ALock's densely packed one-byte flags.
 */
public class ALock extends AndersonLock {
    public ALock(int threadCount) {
        super(threadCount, 1);
    }
}
