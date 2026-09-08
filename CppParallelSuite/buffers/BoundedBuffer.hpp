#ifndef BOUNDED_BUFFER_HPP
#define BOUNDED_BUFFER_HPP

#include "../Types.hpp"
#include <condition_variable>
#include <mutex>
#include <optional>
#include <vector>

namespace parallel_suite::buffers {

    template <typename T>
    class BoundedBuffer {
    private:
        std::mutex mutex;
        std::condition_variable notFull;
        std::condition_variable notEmpty;
        std::vector<T> buf;
        usize cap;
        usize head{0};
        usize tail{0};
        usize count{0};

    public:
        explicit BoundedBuffer(usize capacity) : buf(capacity), cap(capacity) {}

        void put(T value) {
            std::unique_lock lock(mutex);
            notFull.wait(lock, [&] { return count < cap; });
            buf[tail] = std::move(value);
            tail = (tail + 1) % cap;
            ++count;
            notEmpty.notify_one();
        }

        T take() {
            std::unique_lock lock(mutex);
            notEmpty.wait(lock, [&] { return count > 0; });
            T value = std::move(buf[head]);
            head = (head + 1) % cap;
            --count;
            notFull.notify_one();
            return value;
        }

        std::optional<T> tryTake() {
            std::unique_lock lock(mutex);
            if (count == 0) {
                return std::nullopt;
            }
            T value = std::move(buf[head]);
            head = (head + 1) % cap;
            --count;
            notFull.notify_one();
            return value;
        }
    };

} // namespace parallel_suite::buffers

#endif // BOUNDED_BUFFER_HPP
