#include "../../sets/FineGrainedSet.hpp"
#include "../../sets/LazySet.hpp"
#include "../../sets/LockHashSet.hpp"
#include "../../sets/OptimisticSet.hpp"
#include "TestEnv.hpp"
#include "TestRef.hpp"
#include <future>
#include <iostream>
#include <random>
#include <string>
#include <vector>

#define TEST_X        \
    if (!x) {         \
        return false; \
    }
#define TEST_NOT_X    \
    if (x) {          \
        return false; \
    }

namespace parallel_test::sets {
    using namespace parallel_suite;
    using namespace parallel_suite::sets;

    constexpr static int WorkRange = 15;
    constexpr static int Times = 5;

    static std::string mkString(int threadId, int n) {
        return std::to_string(threadId) + "_" + std::to_string(n);
    }

    template <SetType<int> IntSet>
    bool testA(int nThreads, int workSize) {
        std::vector<std::future<bool>> futures;
        IntSet theSet;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, nThreads, workSize, &theSet]() {
                bool x;
                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;
                    x = theSet.add(token);
                    TEST_X;
                    x = theSet.contains(token);
                    TEST_X;
                    x = theSet.remove(token);
                    TEST_X;
                    x = theSet.contains(token);
                    TEST_NOT_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.add(i * nThreads + threadId);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.contains(i * nThreads + threadId);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.remove(i * nThreads + threadId);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.contains(i * nThreads + threadId);
                    TEST_NOT_X;
                }
                return true;
            }));
        }

        for (auto&& worker : futures) {
            if (!worker.get()) {
                return false;
            }
        }
        return true;
    }

    template <SetType<std::string> StrSet>
    bool testB(int nThreads, int workSize) {
        std::vector<std::future<bool>> futures;
        StrSet theSet;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, workSize, &theSet]() {
                bool x;
                for (int i = 0; i < workSize; ++i) {
                    auto token = mkString(threadId, i);
                    x = theSet.add(token);
                    TEST_X;
                    x = theSet.contains(token);
                    TEST_X;
                    x = theSet.remove(token);
                    TEST_X;
                    x = theSet.contains(token);
                    TEST_NOT_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.add(mkString(threadId, i));
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.contains(mkString(threadId, i));
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.remove(mkString(threadId, i));
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    x = theSet.contains(mkString(threadId, i));
                    TEST_NOT_X;
                }
                return true;
            }));
        }

        for (auto&& worker : futures) {
            if (!worker.get()) {
                return false;
            }
        }
        return true;
    }

    template <SetType<std::string> StrSet>
    bool testC(int nThreads, int workSize) {
        StrSet theSet;
        parallel_test::CoarseSet<std::string> reference;
        std::mt19937 seedRng{std::random_device{}()};
        auto baseSeed = seedRng();

        auto mutate = [&](auto& set) {
            std::vector<std::future<void>> futures;
            for (int threadId = 0; threadId < nThreads; ++threadId) {
                futures.push_back(std::async(std::launch::async, [threadId, workSize, baseSeed, &set]() {
                    std::mt19937 rng(baseSeed + static_cast<unsigned>(threadId));
                    std::uniform_int_distribution<int> dist(0, WorkRange - 1);
                    for (int i = 0; i < workSize; i++) {
                        set.add(mkString(threadId, dist(rng)));
                        set.remove(mkString(threadId, dist(rng)));
                    }
                }));
            }
            for (auto&& f : futures) {
                f.get();
            }
        };

        mutate(reference);
        mutate(theSet);

        for (int t = 0; t < nThreads; ++t) {
            for (int i = 0; i < WorkRange; ++i) {
                auto token = mkString(t, i);
                if (reference.contains(token) != theSet.contains(token)) {
                    return false;
                }
            }
        }
        return true;
    }

    template <SetType<int> IntSet>
    bool testD(int nThreads, int workSize) {
        IntSet theSet;
        std::vector<std::future<void>> futures;
        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, workSize, &theSet]() {
                std::mt19937 rng(static_cast<unsigned>(threadId + 1) * 2654435761u);
                std::uniform_int_distribution<int> dist(0, WorkRange - 1);
                for (int i = 0; i < workSize; i++) {
                    theSet.add(dist(rng));
                    theSet.remove(dist(rng));
                    theSet.contains(dist(rng));
                }
            }));
        }
        for (auto&& f : futures) {
            f.get();
        }

        for (int k = 0; k < WorkRange; ++k) {
            bool present = theSet.contains(k);
            if (present) {
                if (!theSet.remove(k) || theSet.contains(k)) {
                    return false;
                }
            } else {
                if (!theSet.add(k) || !theSet.contains(k)) {
                    return false;
                }
            }
        }
        return true;
    }

    template <SetType<int> IntSet>
    bool testIntSet(int nThreads, int workSize) {
        return testA<IntSet>(nThreads, workSize) && testD<IntSet>(nThreads, workSize);
    }

    template <SetType<std::string> StrSet>
    bool testStrSet(int nThreads, int workSize) {
        return testB<StrSet>(nThreads, workSize) && testC<StrSet>(nThreads, workSize);
    }
} // namespace parallel_test::sets

using namespace parallel_test::sets;

int main() {
    int nThreads = parallel_test::testNThreads(12);
    int workSize = 1000 / nThreads;
    if (workSize < 1) {
        workSize = 1;
    }

    bool ok = true;
    for (int t = 0; t < Times; ++t) {
        ok = ok && testIntSet<parallel_test::CoarseSet<int>>(nThreads, workSize);
        ok = ok && testStrSet<parallel_test::CoarseSet<std::string>>(nThreads, workSize);
        ok = ok && testIntSet<LockHashSet<int>>(nThreads, workSize);
        ok = ok && testStrSet<LockHashSet<std::string>>(nThreads, workSize);
        ok = ok && testIntSet<FineGrainedSet<int>>(nThreads, workSize);
        ok = ok && testStrSet<FineGrainedSet<std::string>>(nThreads, workSize);
        ok = ok && testIntSet<OptimisticSet<int>>(nThreads, workSize);
        ok = ok && testStrSet<OptimisticSet<std::string>>(nThreads, workSize);
        ok = ok && testIntSet<LazySet<int>>(nThreads, workSize);
        ok = ok && testStrSet<LazySet<std::string>>(nThreads, workSize);
        if (!ok) {
            break;
        }
    }

    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
