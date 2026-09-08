package edu.lsu.cct.parallelsuite.accounts;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class AccountLedger {
    private static class Account {
        private final Lock lock;
        private long balance;

        Account(Lock lock, long balance) {
            this.lock = lock;
            this.balance = balance;
        }
    }

    private final Account[] accounts;

    public AccountLedger(int nAccounts, long initialBalance) {
        this(nAccounts, initialBalance, ReentrantLock::new);
    }

    public AccountLedger(int nAccounts, long initialBalance, Supplier<Lock> lockSupplier) {
        this.accounts = new Account[nAccounts];
        for (int i = 0; i < nAccounts; i++) {
            accounts[i] = new Account(lockSupplier.get(), initialBalance);
        }
    }

    public int size() {
        return accounts.length;
    }

    public void transfer(int from, int to, long amount) {
        if (from == to || amount <= 0) {
            return;
        }
        var lo = Math.min(from, to);
        var hi = Math.max(from, to);
        accounts[lo].lock.lock();
        accounts[hi].lock.lock();
        try {
            var take = Math.min(amount, accounts[from].balance);
            accounts[from].balance -= take;
            accounts[to].balance += take;
        } finally {
            accounts[hi].lock.unlock();
            accounts[lo].lock.unlock();
        }
    }

    public long total() {
        long sum = 0;
        for (var a : accounts) {
            a.lock.lock();
            try {
                sum += a.balance;
            } finally {
                a.lock.unlock();
            }
        }
        return sum;
    }

    public boolean noNegatives() {
        for (var a : accounts) {
            a.lock.lock();
            try {
                if (a.balance < 0) {
                    return false;
                }
            } finally {
                a.lock.unlock();
            }
        }
        return true;
    }
}
