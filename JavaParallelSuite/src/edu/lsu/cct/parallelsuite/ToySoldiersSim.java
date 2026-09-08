package edu.lsu.cct.parallelsuite;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class ToySoldiersSim {
    private final int rows, cols;
    private final Tile[][] tiles;

    private final Supplier<Lock> lockSupplier;

    private int soldierCount;
    // Count wait needs a real Condition; custom spinlocks in this suite do not provide one.
    private final Lock countLock = new ReentrantLock();
    private final Condition countCond = countLock.newCondition();
    private volatile boolean running = true;

    public ToySoldiersSim(int rows, int cols) {
        this(rows, cols, ReentrantLock::new);
    }

    public ToySoldiersSim(int rows, int cols, Supplier<Lock> lockSupplier) {
        this.rows = rows;
        this.cols = cols;
        this.lockSupplier = lockSupplier;
        this.soldierCount = 0;

        this.tiles = new Tile[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                tiles[r][c] = new Tile(r, c);
            }
        }
    }

    public void deploy(ExecutorService pool, Soldier... soldiers) {
        try {
            countLock.lock();

            for (var s : soldiers) {
                pool.submit(s);
            }

            while (soldierCount > 1) {
                countCond.awaitUninterruptibly();
            }
        } finally {
            countLock.unlock();
        }

        System.out.println("deploy() finished.");
    }

    public Soldier addSoldier(int r, int c) {
        try {
            countLock.lock();
            var s = new Soldier(r, c);
            soldierCount++;
            return s;
        } finally {
            countLock.unlock();
        }
    }

    public void decrementSoldierCount() {
        try {
            countLock.lock();
            soldierCount--;
            if (soldierCount <= 1) {
                countCond.signalAll();
            }
        } finally {
            countLock.unlock();
        }
    }

    public void stop() {
        running = false;
    }

    public int getSoldierCount() {
        countLock.lock();
        try {
            return soldierCount;
        } finally {
            countLock.unlock();
        }
    }

    public boolean tilesAreConsistent() {
        var seen = new java.util.HashSet<Integer>();
        int occupants = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                var t = tiles[r][c];
                t.lock.lock();
                try {
                    if (t.occupant != null) {
                        occupants++;
                        if (!seen.add(t.occupant.id)) {
                            return false;
                        }
                        if (t.occupant.row != r || t.occupant.col != c) {
                            return false;
                        }
                    }
                } finally {
                    t.lock.unlock();
                }
            }
        }
        return occupants <= getSoldierCount();
    }

    public boolean inBounds(int row, int col) {
        return row >= 0
            && row <= rows - 1
            && col >= 0
            && col <= cols - 1;
    }

    public boolean inBounds(Tile t) {
        return inBounds(t.r, t.c);
    }

    public Tile getRC(int row, int col) {
        if (!inBounds(row, col)) {
            return null;
        }
        row = Math.max(0, Math.min(row, rows - 1));
        col = Math.max(0, Math.min(col, cols - 1));
        return tiles[row][col];
    }

    enum Direction {
        NORTH(-1, 0),
        SOUTH(1, 0),
        EAST(0, 1),
        WEST(0, -1);

        final int r, c;
        Direction(int r, int c) {
            this.r = r;
            this.c = c;
        }

        Tile getRelative(ToySoldiersSim game, int r, int c) {
            return game.getRC(r + this.r, c + this.c);
        }
    }

    private class Tile {
        final int r, c;
        private final Lock lock;
        private volatile Soldier occupant;

        Tile(int r, int c) {
            this.r = r;
            this.c = c;
            this.lock = lockSupplier.get();
        }

        public Soldier getOccupant() {
            lock.lock();
            try {
                return occupant;
            } finally {
                lock.unlock();
            }
        }

        public void setOccupant(Soldier occupant) {
            lock.lock();
            try {
                this.occupant = occupant;
            } finally {
                lock.unlock();
            }
        }

        public void swapOccupants(Tile other) {
            try {
                int myId = System.identityHashCode(lock);
                int theirId = System.identityHashCode(other.lock);

                if (myId < theirId) {
                    lock.lock();
                    other.lock.lock();
                } else {
                    other.lock.lock();
                    lock.lock();
                }

                var myOccupant = this.occupant;
                this.occupant = other.occupant;
                other.occupant = myOccupant;
            } finally {
                lock.unlock();
                other.lock.unlock();
            }
        }
    }

    private static final AtomicInteger idCounter = new AtomicInteger(0);
    public class Soldier implements Runnable {

        private final Random random;
        private final Lock lock;

        private int row, col;
        private int life;

        public final int id;

        Soldier(int row, int col) {
            this.row = row;
            this.col = col;
            this.lock = lockSupplier.get();
            this.life = 2;
            this.random = new Random();
            this.id = idCounter.getAndIncrement();
            getRC(row, col).setOccupant(this);
        }

        int getLife() {
            try {
                lock.lock();
                return life;
            } finally {
                lock.unlock();
            }
        }

        int decrementLife() {
            try {
                lock.lock();
                --life;
                if (life == 0) {
                    System.out.printf("Soldier %d fell!%n", id);
                    decrementSoldierCount();
                    getRC(row, col).setOccupant(null);
                }
                return life;
            } finally {
                lock.unlock();
            }
        }

        void step() {
            int r, c;

            try {
                lock.lock();
                r = row;
                c = col;
            } finally {
                lock.unlock();
            }

            var here = getRC(r, c);
            var dirs = new Tile[] {
                    Direction.NORTH.getRelative(ToySoldiersSim.this, r, c),
                    Direction.SOUTH.getRelative(ToySoldiersSim.this, r, c),
                    Direction.EAST.getRelative(ToySoldiersSim.this, r, c),
                    Direction.WEST.getRelative(ToySoldiersSim.this, r, c)
            };

            Soldier toAttack = null;
            for (var there : dirs) {
                if (there == null) {
                    continue;
                }
                var whoIsThere = there.getOccupant();
                if (whoIsThere != null && !whoIsThere.equals(this)) {
                    toAttack = whoIsThere;
                    break;
                }
            }

            if (toAttack != null) {
                toAttack.decrementLife();
                System.out.printf("Soldier %d attacked Soldier %d!%n", id, toAttack.id);
            } else {
                try {
                    lock.lock();

                    int d;
                    Tile headingTo;
                    do {
                        d = random.nextInt(4);
                        headingTo = dirs[d];
                    } while (headingTo == null);

                    headingTo.swapOccupants(here);
                    row = headingTo.r;
                    col = headingTo.c;
                    System.out.printf("Soldier %d went %s!%n", id, Direction.values()[d].toString());
                } finally {
                    lock.unlock();
                }
            }
        }

        @Override
        public void run() {
            while (running && getLife() > 0) {
                step();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Soldier soldier = (Soldier) o;
            return id == soldier.id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "Soldier(" + id + ")";
        }
    }
}
