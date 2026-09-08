package edu.lsu.cct.parallelsuite.sets;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class LockHashSet<E> implements SlimSet<E> {
    private static final int DEFAULT_BUCKETS = 16;

    private final Object[] buckets;
    private final Supplier<Lock> lockSupplier;

    public LockHashSet() {
        this(DEFAULT_BUCKETS, ReentrantLock::new);
    }

    public LockHashSet(Supplier<Lock> lockSupplier) {
        this(DEFAULT_BUCKETS, lockSupplier);
    }

    public LockHashSet(int numBuckets, Supplier<Lock> lockSupplier) {
        if (numBuckets <= 0) {
            throw new IllegalArgumentException("numBuckets must be positive");
        }
        this.lockSupplier = lockSupplier;
        this.buckets = new Object[numBuckets];
        for (int i = 0; i < numBuckets; ++i) {
            buckets[i] = new NodeHead();
        }
    }

    @Override
    public boolean add(E e) {
        var nodeHead = getNodeHead(e);
        nodeHead.lock.lock();
        try {
            if (nodeHead.head == null) {
                nodeHead.head = new Node(e);
                return true;
            }
            var current = nodeHead.head;
            while (true) {
                if (Objects.equals(current.val, e)) {
                    return false;
                }
                if (current.next != null) {
                    current = current.next;
                } else {
                    break;
                }
            }
            current.next = new Node(e);
            return true;
        } finally {
            nodeHead.lock.unlock();
        }
    }

    @Override
    public boolean remove(Object o) {
        var nodeHead = getNodeHead(o);
        nodeHead.lock.lock();
        try {
            Node current = nodeHead.head;
            Node prev = null;
            while (current != null) {
                if (Objects.equals(current.val, o)) {
                    if (prev == null) {
                        nodeHead.head = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    return true;
                }
                prev = current;
                current = current.next;
            }
            return false;
        } finally {
            nodeHead.lock.unlock();
        }
    }

    @Override
    public boolean contains(Object o) {
        var nodeHead = getNodeHead(o);
        nodeHead.lock.lock();
        try {
            var current = nodeHead.head;
            while (current != null) {
                if (Objects.equals(current.val, o)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        } finally {
            nodeHead.lock.unlock();
        }
    }

    @Override
    public E getEqual(E e) {
        var nodeHead = getNodeHead(e);
        nodeHead.lock.lock();
        try {
            var current = nodeHead.head;
            while (current != null) {
                if (Objects.equals(current.val, e)) {
                    return current.val;
                }
                current = current.next;
            }
            return null;
        } finally {
            nodeHead.lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private NodeHead getNodeHead(Object o) {
        return (NodeHead) buckets[bucketIndex(o)];
    }

    private int bucketIndex(Object o) {
        return Math.floorMod(o.hashCode(), buckets.length);
    }

    private class Node {
        private final E val;
        private Node next;

        private Node(E val) {
            this.val = val;
        }
    }

    private class NodeHead {
        private final Lock lock = lockSupplier.get();
        private Node head;
    }
}
