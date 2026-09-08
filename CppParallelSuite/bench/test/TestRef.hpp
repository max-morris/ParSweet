#ifndef TEST_REF_HPP
#define TEST_REF_HPP

#include <mutex>
#include <optional>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace parallel_test {

    template <typename T>
    class CoarseSet {
    private:
        std::mutex mutex;
        std::unordered_set<T> set;

    public:
        bool add(T t) {
            std::lock_guard lock(mutex);
            return set.insert(std::move(t)).second;
        }

        bool remove(T const& t) {
            std::lock_guard lock(mutex);
            return set.erase(t) > 0;
        }

        bool contains(T const& t) {
            std::lock_guard lock(mutex);
            return set.count(t) > 0;
        }

        std::optional<T> getEqual(T const& t) {
            std::lock_guard lock(mutex);
            auto it = set.find(t);
            if (it == set.end()) {
                return std::nullopt;
            }
            return *it;
        }
    };

    template <typename K, typename V>
    class CoarseMap {
    private:
        std::mutex mutex;
        std::unordered_map<K, V> map;

    public:
        bool put(K k, V v) {
            std::lock_guard lock(mutex);
            map.insert_or_assign(std::move(k), std::move(v));
            return true;
        }

        bool get(K const& k, V& out) {
            std::lock_guard lock(mutex);
            auto it = map.find(k);
            if (it == map.end()) {
                return false;
            }
            out = it->second;
            return true;
        }

        bool del(K const& k) {
            std::lock_guard lock(mutex);
            return map.erase(k) > 0;
        }
    };

} // namespace parallel_test

#endif // TEST_REF_HPP
