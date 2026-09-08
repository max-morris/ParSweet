package edu.lsu.cct.parallelsuite.bench;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class Misc {
    public static ThreadFactory getDaemonThreadFactory() {
        return r -> {
            var t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        };
    }

    public static int nThreads(int defaultN) {
        var env = System.getenv("PSWEET_NTHREADS");
        if (env == null || env.isEmpty()) {
            return defaultN;
        }
        var n = Integer.parseInt(env);
        return n > 0 ? n : defaultN;
    }

    public static boolean assertionsEnabled() {
        var enabled = false;
        //noinspection ConstantConditions,AssertWithSideEffects
        assert enabled = true;
        return enabled;
    }
}
