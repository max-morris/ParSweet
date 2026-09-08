#include "../../maps/LockHashMap.hpp"
#include "../../maps/MapTypes.hpp"
#include "../../maps/SetBasedMap.hpp"
#include "../../sets/FineGrainedSet.hpp"
#include "../../sets/LazySet.hpp"
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

namespace parallel_test::maps {
    using namespace parallel_suite;
    using namespace parallel_suite::maps;

    constexpr static int WorkRange = 15;
    constexpr static int Times = 5;

    static std::string mkString(int threadId, int n) {
        return std::to_string(threadId) + "_" + std::to_string(n);
    }

    template <MapType<int, int> IntMap>
    bool testA(int nThreads, int workSize) {
        std::vector<std::future<bool>> futures;
        IntMap theMap;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, nThreads, workSize, &theMap]() {
                bool x;
                int out;
                for (int i = 0; i < workSize; ++i) {
                    auto token = i * nThreads + threadId;
                    x = theMap.put(token, token);
                    TEST_X;
                    x = theMap.get(token, out);
                    TEST_X;
                    x = token == out;
                    TEST_X;
                    theMap.del(token);
                    x = theMap.get(token, out);
                    TEST_NOT_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    x = theMap.put(token, token);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    x = theMap.get(token, out);
                    TEST_X;
                    x = token == out;
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    x = theMap.del(token);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = i * nThreads + threadId;
                    x = theMap.get(token, out);
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

    template <MapType<std::string, std::string> StrMap>
    bool testB(int nThreads, int workSize) {
        std::vector<std::future<bool>> futures;
        StrMap theMap;

        for (int threadId = 0; threadId < nThreads; ++threadId) {
            futures.push_back(std::async(std::launch::async, [threadId, workSize, &theMap]() {
                bool x;
                std::string out;
                for (int i = 0; i < workSize; ++i) {
                    auto token = mkString(threadId, i);
                    x = theMap.put(token, token);
                    TEST_X;
                    x = theMap.get(token, out);
                    TEST_X;
                    x = token == out;
                    TEST_X;
                    theMap.del(token);
                    x = theMap.get(token, out);
                    TEST_NOT_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = mkString(threadId, i);
                    x = theMap.put(token, token);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = mkString(threadId, i);
                    x = theMap.get(token, out);
                    TEST_X;
                    x = token == out;
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = mkString(threadId, i);
                    x = theMap.del(token);
                    TEST_X;
                }
                for (int i = 0; i < workSize; i++) {
                    auto token = mkString(threadId, i);
                    x = theMap.get(token, out);
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

    template <MapType<std::string, std::string> StrMap>
    bool testC(int nThreads, int workSize) {
        StrMap theMap;
        parallel_test::CoarseMap<std::string, std::string> reference;
        std::mt19937 seedRng{std::random_device{}()};
        auto baseSeed = seedRng();

        auto mutate = [&](auto& map) {
            std::vector<std::future<void>> futures;
            for (int threadId = 0; threadId < nThreads; ++threadId) {
                futures.push_back(std::async(std::launch::async, [threadId, workSize, baseSeed, &map]() {
                    std::mt19937 rng(baseSeed + static_cast<unsigned>(threadId));
                    std::uniform_int_distribution<int> dist(0, WorkRange - 1);
                    std::string ignored;
                    for (int i = 0; i < workSize; i++) {
                        auto a = mkString(threadId, dist(rng));
                        map.put(a, a);
                        auto b = mkString(threadId, dist(rng));
                        map.del(b);
                    }
                }));
            }
            for (auto&& f : futures) {
                f.get();
            }
        };

        mutate(reference);
        mutate(theMap);

        for (int t = 0; t < nThreads; ++t) {
            for (int i = 0; i < WorkRange; ++i) {
                auto token = mkString(t, i);
                std::string left;
                std::string right;
                bool inRef = reference.get(token, left);
                bool inMap = theMap.get(token, right);
                if (inRef != inMap) {
                    return false;
                }
                if (inRef && left != right) {
                    return false;
                }
            }
        }
        return true;
    }

    template <MapType<int, int> IntMap>
    bool testIntMap(int nThreads, int workSize) {
        return testA<IntMap>(nThreads, workSize);
    }

    template <MapType<std::string, std::string> StrMap>
    bool testStrMap(int nThreads, int workSize) {
        return testB<StrMap>(nThreads, workSize) && testC<StrMap>(nThreads, workSize);
    }
} // namespace parallel_test::maps

using namespace parallel_test::maps;

int main() {
    int nThreads = parallel_test::testNThreads(12);
    int workSize = 1000 / nThreads;
    if (workSize < 1) {
        workSize = 1;
    }

    bool ok = true;
    for (int t = 0; t < Times; ++t) {
        ok = ok && testIntMap<parallel_test::CoarseMap<int, int>>(nThreads, workSize);
        ok = ok && testStrMap<parallel_test::CoarseMap<std::string, std::string>>(nThreads, workSize);
        ok = ok && testIntMap<LockHashMap<int, int>>(nThreads, workSize);
        ok = ok && testStrMap<LockHashMap<std::string, std::string>>(nThreads, workSize);
        ok = ok && testIntMap<SetBasedMap<int, int, sets::FineGrainedSet>>(nThreads, workSize);
        ok = ok && testStrMap<SetBasedMap<std::string, std::string, sets::FineGrainedSet>>(nThreads, workSize);
        ok = ok && testIntMap<SetBasedMap<int, int, sets::OptimisticSet>>(nThreads, workSize);
        ok = ok && testStrMap<SetBasedMap<std::string, std::string, sets::OptimisticSet>>(nThreads, workSize);
        ok = ok && testIntMap<SetBasedMap<int, int, sets::LazySet>>(nThreads, workSize);
        ok = ok && testStrMap<SetBasedMap<std::string, std::string, sets::LazySet>>(nThreads, workSize);
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
