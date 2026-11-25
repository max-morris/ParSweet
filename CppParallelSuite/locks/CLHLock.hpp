
#ifndef CLH_LOCK_HPP
#define CLH_LOCK_HPP


#include "../threadlocal/ThreadLocal.hpp"
#include "LockTraits.hpp"
#include <atomic>
#include <thread>

namespace parallel_suite::locks {
    using namespace parallel_suite::threadlocal;

    namespace clh {
        class QNode {
        public:
            // Atomic, so that the holder's store of false in unlock()
            // synchronizes with its successor's load in lock(), ordering
            // their critical sections; a plain bool is a data race.
            std::atomic<bool> locked;
            QNode() : locked(false) {}
        };
    } // namespace clh

    class CLHLock {
    private:
        ThreadLocal<clh::QNode*> myPredecessor;
        ThreadLocal<clh::QNode*> myNode;
        std::atomic<clh::QNode*> tail;

    public:
        CLHLock() : myPredecessor(), myNode(), tail(new clh::QNode()) {}

        ~CLHLock() {
            delete tail.load();
        }

        void lock() {
            if (!myNode.isSet()) {
                myNode.set(new clh::QNode());
            }

            clh::QNode* qNode = myNode.get();
            qNode->locked = true;

            clh::QNode* predecessor = tail.exchange(qNode);

            myPredecessor.set(predecessor);

            while (predecessor->locked) {
                std::this_thread::yield();
            }
        }

        void unlock() {
            clh::QNode* qNode = myNode.get();
            qNode->locked = false;
            myNode.set(myPredecessor.get());
            myPredecessor.set(nullptr);
        }
    };

    template <>
    struct LockTraits<CLHLock> {
        constexpr static char const* name = "CLHLock";
    };
} // namespace parallel_suite::locks

#endif //CLH_LOCK_HPP
