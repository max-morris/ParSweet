package edu.lsu.cct.parallelsuite.buffers;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBuffer<T> {
    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Object[] buf;
    private int head;
    private int tail;
    private int count;

    public BoundedBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.buf = new Object[capacity];
    }

    public void put(T value) {
        lock.lock();
        try {
            while (count == buf.length) {
                notFull.awaitUninterruptibly();
            }
            buf[tail] = value;
            tail = (tail + 1) % buf.length;
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public T take() {
        lock.lock();
        try {
            while (count == 0) {
                notEmpty.awaitUninterruptibly();
            }
            var value = (T) buf[head];
            buf[head] = null;
            head = (head + 1) % buf.length;
            count--;
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }
}
