#ifndef ACCOUNT_LEDGER_HPP
#define ACCOUNT_LEDGER_HPP

#include "../MutexType.hpp"
#include "../Types.hpp"
#include <algorithm>
#include <memory>
#include <mutex>
#include <utility>

namespace parallel_suite::accounts {

    template <MutexType Mutex = std::mutex>
    class AccountLedger {
    private:
        struct Account {
            Mutex mutex;
            long balance{0};
        };

        std::unique_ptr<Account[]> accounts;
        usize nAccounts;

    public:
        AccountLedger(usize nAccounts, long initialBalance)
            : accounts(std::make_unique<Account[]>(nAccounts)),
              nAccounts(nAccounts) {
            for (usize i = 0; i < nAccounts; ++i) {
                accounts[i].balance = initialBalance;
            }
        }

        usize size() const {
            return nAccounts;
        }

        void transfer(usize from, usize to, long amount) {
            if (from == to || amount <= 0) {
                return;
            }
            auto lo = std::min(from, to);
            auto hi = std::max(from, to);
            std::lock_guard lockLo(accounts[lo].mutex);
            std::lock_guard lockHi(accounts[hi].mutex);
            auto take = std::min(amount, accounts[from].balance);
            accounts[from].balance -= take;
            accounts[to].balance += take;
        }

        long total() {
            long sum = 0;
            for (usize i = 0; i < nAccounts; ++i) {
                std::lock_guard lock(accounts[i].mutex);
                sum += accounts[i].balance;
            }
            return sum;
        }

        bool noNegatives() {
            for (usize i = 0; i < nAccounts; ++i) {
                std::lock_guard lock(accounts[i].mutex);
                if (accounts[i].balance < 0) {
                    return false;
                }
            }
            return true;
        }
    };

} // namespace parallel_suite::accounts

#endif // ACCOUNT_LEDGER_HPP
