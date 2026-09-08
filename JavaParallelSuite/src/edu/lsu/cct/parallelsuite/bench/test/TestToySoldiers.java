package edu.lsu.cct.parallelsuite.bench.test;

import edu.lsu.cct.parallelsuite.ToySoldiersSim;
import edu.lsu.cct.parallelsuite.bench.Misc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TestToySoldiers {

    public static ExecutorService pool = Executors.newCachedThreadPool(Misc.getDaemonThreadFactory());

    private static boolean runOnce(int rows, int cols, int r1, int c1, int r2, int c2) {
        var ts = new ToySoldiersSim(rows, cols);
        var s1 = ts.addSoldier(r1, c1);
        var s2 = ts.addSoldier(r2, c2);
        ts.deploy(pool, s1, s2);
        ts.stop();
        var count = ts.getSoldierCount();
        assert count <= 1 : "too many survivors: " + count;
        assert ts.tilesAreConsistent();
        return count <= 1 && ts.tilesAreConsistent();
    }

    public static void main(String[] args) {
        boolean assertionsOn = Misc.assertionsEnabled();
        System.out.printf("Assertions are %s.%n", assertionsOn ? "ON" : "OFF");

        var ok = runOnce(5, 5, 2, 2, 4, 4);
        ok = runOnce(3, 3, 0, 0, 2, 2) && ok;

        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        if (!ok) {
            System.out.println("A test failed!");
            System.exit(0xBAD);
        }
        System.out.println("All tests passed.");
    }
}
