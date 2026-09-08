package edu.lsu.cct.parallelsuite.bench;

public final class BlackBox {
    private static volatile Object sink;

    private BlackBox() {}

    public static void consume(Object o) {
        sink = o;
    }

    public static void consume(int n) {
        sink = n;
    }

    public static void consume(boolean b) {
        sink = b;
    }

    public static void consume(long n) {
        sink = n;
    }
}
