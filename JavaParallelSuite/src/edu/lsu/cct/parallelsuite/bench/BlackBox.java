package edu.lsu.cct.parallelsuite.bench;

/**
 * Keeps a computed value alive so the JIT cannot delete the work that produced it.
 * One slot per thread: a static volatile would be a shared line on every call,
 * which the C++ asm register constraint does not do.
 */
public final class BlackBox {
    private static final ThreadLocal<int[]> sink = ThreadLocal.withInitial(() -> new int[1]);

    private BlackBox() {
    }

    public static void consume(int value) {
        sink.get()[0] = value;
    }
}
