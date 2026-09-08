#ifndef TWO_LOCK_QUEUE_HPP
#define TWO_LOCK_QUEUE_HPP

#include "../MutexType.hpp"
#include <memory>
#include <mutex>
#include <optional>
#include <utility>

namespace parallel_suite::queues {

    template <typename T, MutexType Mutex = std::mutex>
    class TwoLockQueue {
    private:
        struct Node {
            std::optional<T> value;
            std::unique_ptr<Node> next;
            Node() : value(std::nullopt), next(nullptr) {}
            explicit Node(T v) : value(std::move(v)), next(nullptr) {}
        };

        Mutex headLock;
        Mutex tailLock;
        std::unique_ptr<Node> dummy;
        Node* head;
        Node* tail;

    public:
        TwoLockQueue() : dummy(std::make_unique<Node>()), head(dummy.get()), tail(dummy.get()) {}

        void enqueue(T value) {
            auto node = std::make_unique<Node>(std::move(value));
            auto* raw = node.get();
            std::lock_guard lock(tailLock);
            tail->next = std::move(node);
            tail = raw;
        }

        std::optional<T> tryDequeue() {
            std::lock_guard lock(headLock);
            if (!head->next) {
                return std::nullopt;
            }
            dummy = std::move(head->next);
            head = dummy.get();
            return std::move(head->value);
        }
    };

} // namespace parallel_suite::queues

#endif // TWO_LOCK_QUEUE_HPP
