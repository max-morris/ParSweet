package edu.lsu.cct.parallelsuite.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class BenchParams {
    private final String lang;
    private final String category;
    private final String machine;
    private final String outPath;
    private final String which;
    private final int nThreads;
    private final int workPerThread;
    private final int cooldown;
    private final boolean debug;

    public BenchParams(String lang, String category) {
        this.lang = lang;
        this.category = category;
        this.nThreads = requireInt("PSWEET_NTHREADS");
        this.workPerThread = requireInt("PSWEET_WORK_PER_THREAD");
        this.machine = require("PSWEET_MACHINE");
        var out = System.getenv("PSWEET_OUT_PATH");
        this.outPath = (out == null || out.isEmpty()) ? "psweet.csv" : out;
        var cool = System.getenv("PSWEET_COOLDOWN");
        this.cooldown = (cool == null || cool.isEmpty()) ? 0 : Integer.parseInt(cool);
        var whichEnv = System.getenv("PSWEET_WHICH");
        this.which = (whichEnv == null || whichEnv.isEmpty()) ? null : whichEnv;
        this.debug = Misc.assertionsEnabled();
    }

    private static String require(String name) {
        var v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("bad " + name);
        }
        return v;
    }

    private static int requireInt(String name) {
        return Integer.parseInt(require(name));
    }

    public String getLang() { return lang; }
    public String getCategory() { return category; }
    public String getMachine() { return machine; }
    public String getOutPath() { return outPath; }
    public String getWhich() { return which; }
    public int getNThreads() { return nThreads; }
    public int getWorkPerThread() { return workPerThread; }

    public boolean shouldRun(String specific) {
        return which == null || which.equals(specific);
    }

    public void coolOff() {
        if (cooldown > 0) {
            try {
                Thread.sleep(cooldown);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long measure(Runnable fn) {
        var t1 = System.nanoTime();
        fn.run();
        var t2 = System.nanoTime();
        return (t2 - t1) / 1_000_000L;
    }

    public void writeResult(String specific, long ms) {
        var path = Path.of(outPath);
        try {
            var line = String.join(",",
                    lang,
                    category,
                    specific,
                    machine,
                    Integer.toString(nThreads),
                    Integer.toString(workPerThread),
                    Long.toString(ms),
                    debug ? "1" : "0") + System.lineSeparator();
            if (!Files.exists(path)) {
                Files.writeString(path, "lang,category,specific,machine,nThreads,workPerThread,ms,debug\n" + line);
            } else {
                Files.writeString(path, line, StandardOpenOption.APPEND);
            }
            System.out.println("Wrote to " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
