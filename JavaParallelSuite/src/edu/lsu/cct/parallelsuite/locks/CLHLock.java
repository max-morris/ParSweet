package edu.lsu.cct.parallelsuite.locks;

import java.util.concurrent.atomic.AtomicReference;

public class CLHLock implements SlimLock {
    private final ThreadLocal<QNode> myPredecessor = new ThreadLocal<>();
    private final ThreadLocal<QNode> myNode = ThreadLocal.withInitial(QNode::new);
    private final AtomicReference<QNode> tail = new AtomicReference<>(new QNode());

    @Override
    public void lock() {
        var qNode = myNode.get();
        qNode.locked = true;

        var predecessor = tail.getAndSet(qNode);
        myPredecessor.set(predecessor);

        while (predecessor.locked) {
            Thread.yield();
        }
    }

    @Override
    public void unlock() {
        var qNode = myNode.get();
        qNode.locked = false;
        myNode.set(myPredecessor.get());
        myPredecessor.set(null);
    }

    private static class QNode {
        // Volatile, so that the holder's write of false in unlock() happens
        // before its successor's read in lock(), ordering their critical
        // sections; a plain field guarantees neither visibility nor order.
        public volatile boolean locked = false;
    }
}
