
#ifndef LOCK_HASH_SET_HPP
#define LOCK_HASH_SET_HPP

#include "../KeyType.hpp"
#include "../MutexType.hpp"
#include "../Types.hpp"
#include "SetTypes.hpp"
#include <array>
#include <mutex>
#include <optional>

namespace parallel_suite::sets {

    template <KeyType T, usize NumBuckets = 16, MutexType Mutex = std::mutex>
    class LockHashSet {
    private:
        class Node {
        public:
            const T val;
            std::unique_ptr<Node> next;

            explicit Node(T val) : val(val), next(nullptr) {}
        };

        class NodeHead {
        public:
            Mutex mutex;
            std::unique_ptr<Node> head;

            NodeHead() : mutex(), head(nullptr) {}
        };

        using MyNodeHead = NodeHead;
        using MyNode = Node;

        std::array<MyNodeHead, NumBuckets> buckets;

        MyNodeHead* getNodeHead(usize index, std::unique_lock<Mutex>& lock) {
            lock = std::unique_lock(buckets[index].mutex);
            return &buckets[index];
        }

        usize getBucketIndex(T const& val) {
            return std::hash<T>{}(val) % NumBuckets;
        }

    public:
        bool add(T val) {
            auto index = getBucketIndex(val);

            std::unique_lock<Mutex> headLock{};
            MyNodeHead* nodeHead = getNodeHead(index, headLock);

            MyNode* h = nodeHead->head.get();

            if (!h) {
                nodeHead->head = std::make_unique<MyNode>(val);
            } else {
                MyNode* current = h;

                while (true) {
                    if (current->val == val) {
                        return false;
                    }

                    if (current->next) {
                        current = current->next.get();
                    } else {
                        break;
                    }
                }

                current->next = std::make_unique<MyNode>(val);
            }

            return true;
        }

        bool remove(T const& val) {
            auto index = getBucketIndex(val);

            std::unique_lock<Mutex> headLock{};
            MyNodeHead* nodeHead = getNodeHead(index, headLock);

            MyNode* h = nodeHead->head.get();

            if (!h) {
                return false;
            }

            MyNode* current = h;
            MyNode* prev = nullptr;
            while (current) {
                if (current->val == val) {
                    if (!prev) {
                        nodeHead->head = std::move(current->next);
                    } else {
                        prev->next = std::move(current->next);
                    }

                    return true;
                }

                prev = current;
                current = current->next.get();
            }

            return false;
        }

        bool contains(T const& val) {
            auto index = getBucketIndex(val);

            std::unique_lock<Mutex> headLock{};
            MyNodeHead* nodeHead = getNodeHead(index, headLock);

            MyNode* h = nodeHead->head.get();

            if (!h) {
                return false;
            }

            MyNode* current = h;

            while (true) {
                if (current->val == val) {
                    return true;
                }

                if (current->next) {
                    current = current->next.get();
                } else {
                    return false;
                }
            }
        }

        std::optional<T> getEqual(T const& val) {
            auto index = getBucketIndex(val);

            std::unique_lock<Mutex> headLock{};
            MyNodeHead* nodeHead = getNodeHead(index, headLock);

            MyNode* h = nodeHead->head.get();

            if (!h) {
                return std::nullopt;
            }

            MyNode* current = h;

            while (true) {
                if (current->val == val) {
                    return current->val;
                }

                if (current->next) {
                    current = current->next.get();
                } else {
                    return std::nullopt;
                }
            }
        }
    };
} // namespace parallel_suite::sets

#endif //LOCK_HASH_SET_HPP
