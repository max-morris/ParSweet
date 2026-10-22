package edu.lsu.cct.parallelsuite.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Runtime knobs and CSV row writer shared by the Java benchmarks.
 * Column order matches C++ writeBenchResult in Bench.hpp.
 */
public final class BenchParameters {
    private final String lang;
    private final String category;
    private final String machine;
    private final Path outPath;
    private final String which;
    private final int nThreads;
    private final int workPerThread;
    private final int cooldownMs;
    // C++ writes !NDEBUG. Java has no NDEBUG, so this is whether -ea is on.
    private final boolean debug;

    public BenchParameters(String lang, String category) {
        this.lang = lang;
        this.category = category;
        nThreads = requiredInt("PSWEET_NTHREADS");
        workPerThread = requiredInt("PSWEET_WORK_PER_THREAD");
        machine = required("PSWEET_MACHINE");

        String out = System.getenv("PSWEET_OUT_PATH");
        // Paths.get is on Java 10. Path.of is Java 11, and this jar is built -target 1.10.
        outPath = Paths.get(out == null || out.isEmpty() ? "psweet.csv" : out);

        String cool = System.getenv("PSWEET_COOLDOWN");
        cooldownMs = cool == null || cool.isEmpty() ? 0 : parseInt(cool, "PSWEET_COOLDOWN");

        // Null when unset, so the benchmark runs every case. An empty string is
        // kept: C++ Bench.hpp stores getenv's result whenever the variable is set.
        String selected = System.getenv("PSWEET_WHICH");
        which = selected;

        boolean assertions = false;
        assert assertions = true;
        debug = assertions;
    }

    public int getNThreads() {
        return nThreads;
    }

    public int getWorkPerThread() {
        return workPerThread;
    }

    public String getWhich() {
        return which;
    }

    public void coolOff() {
        if (cooldownMs <= 0) {
            return;
        }
        try {
            Thread.sleep(cooldownMs);
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void writeResult(String specific, long ms) throws IOException {
        boolean writeHeader = !Files.exists(outPath);
        var options = writeHeader
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND};
        try (BufferedWriter writer = Files.newBufferedWriter(outPath, options)) {
            if (writeHeader) {
                writer.write("lang,category,specific,machine,nThreads,workPerThread,ms,debug");
                writer.newLine();
            }
            // C++ streams a bool as 0 or 1. Match that, not Java's "true"/"false".
            writer.write(lang + "," + category + "," + specific + "," + machine + ","
                    + nThreads + "," + workPerThread + "," + ms + "," + (debug ? 1 : 0));
            writer.newLine();
        }
        System.out.println("Wrote to " + outPath.toAbsolutePath());
    }

    public static int optionalInt(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return parseInt(raw, name);
    }

    private static String required(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("bad " + name);
        }
        return raw;
    }

    private static int requiredInt(String name) {
        return parseInt(required(name), name);
    }

    private static int parseInt(String raw, String name) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) {
                throw new IllegalArgumentException("bad " + name);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad " + name, e);
        }
    }
}
