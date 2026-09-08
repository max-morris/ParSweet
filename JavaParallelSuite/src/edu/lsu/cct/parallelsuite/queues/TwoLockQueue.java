package edu.lsu.cct.parallelsuite.queues;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class TwoLockQueue<T> {
    private static class Node<T> {
        T value;
        Node<T> next;

        Node() {}

        Node(T value) {
            this.value = value;
        }
    }

    private final Lock headLock;
    private final Lock tailLock;
    private Node<T> head;
    private Node<T> tail;

    public TwoLockQueue() {
        this(ReentrantLock::new);
    }

    public TwoLockQueue(Supplier<Lock> lockSupplier) {
        this.headLock = lockSupplier.get();
        this.tailLock = lockSupplier.get();
        this.head = this.tail = new Node<>();
    }

    public void enqueue(T value) {
        var node = new Node<>(value);
        tailLock.lock();
        try {
            tail.next = node;
            tail = node;
        } finally {
            tailLock.unlock();
        }
    }

    public T tryDequeue() {
        headLock.lock();
        try {
            var first = head.next;
            if (first == null) {
                return null;
            }
            head = first;
            return first.value;
        } finally {
            headLock.unlock();
        }
    }
}
