#ifndef TOY_SOLDIERS_SIM_HPP
#define TOY_SOLDIERS_SIM_HPP

#include "../MutexType.hpp"
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <random>
#include <vector>

namespace parallel_suite::toysoldiers {

    template <MutexType Mutex = std::mutex>
    class ToySoldiersSim {
    public:
        class Soldier;

    private:
        class Tile {
        public:
            int r{};
            int c{};
            Mutex mutex;
            Soldier* occupant{nullptr};

            void setOccupant(Soldier* s) {
                std::lock_guard<Mutex> g(mutex);
                occupant = s;
            }

            Soldier* getOccupant() {
                std::lock_guard<Mutex> g(mutex);
                return occupant;
            }

            void swapOccupants(Tile& other) {
                if (this == &other) {
                    return;
                }
                if (this < &other) {
                    mutex.lock();
                    other.mutex.lock();
                } else {
                    other.mutex.lock();
                    mutex.lock();
                }
                auto myOccupant = occupant;
                occupant = other.occupant;
                other.occupant = myOccupant;
                mutex.unlock();
                other.mutex.unlock();
            }
        };

        int rows;
        int cols;
        std::unique_ptr<Tile[]> tiles;

        int soldierCount{0};
        std::mutex countMutex;
        std::condition_variable countCond;
        std::atomic<bool> running{true};
        std::atomic<int> idCounter{0};

        enum class Direction { North,
                               South,
                               East,
                               West };

        static constexpr int dirR(Direction d) {
            switch (d) {
                case Direction::North:
                    return -1;
                case Direction::South:
                    return 1;
                default:
                    return 0;
            }
        }

        static constexpr int dirC(Direction d) {
            switch (d) {
                case Direction::East:
                    return 1;
                case Direction::West:
                    return -1;
                default:
                    return 0;
            }
        }

    public:
        class Soldier {
        public:
            int id;
            ToySoldiersSim* game;
            Mutex mutex;
            int row;
            int col;
            int life{2};
            std::mt19937 rng;

            Soldier(ToySoldiersSim* game, int row, int col)
                : id(game->idCounter.fetch_add(1)),
                  game(game),
                  row(row),
                  col(col),
                  rng(static_cast<unsigned>(id * 2654435761u)) {
                game->getRC(row, col)->setOccupant(this);
            }

            int getLife() {
                std::lock_guard<Mutex> g(mutex);
                return life;
            }

            int decrementLife() {
                std::lock_guard<Mutex> g(mutex);
                --life;
                if (life == 0) {
                    game->decrementSoldierCount();
                    game->getRC(row, col)->setOccupant(nullptr);
                }
                return life;
            }

            void step() {
                int r;
                int c;
                {
                    std::lock_guard<Mutex> g(mutex);
                    r = row;
                    c = col;
                }

                auto* here = game->getRC(r, c);
                Tile* dirs[4] = {
                        game->relative(r, c, Direction::North),
                        game->relative(r, c, Direction::South),
                        game->relative(r, c, Direction::East),
                        game->relative(r, c, Direction::West)};

                Soldier* toAttack = nullptr;
                for (auto* there : dirs) {
                    if (!there) {
                        continue;
                    }
                    auto* who = there->getOccupant();
                    if (who && who != this) {
                        toAttack = who;
                        break;
                    }
                }

                if (toAttack) {
                    toAttack->decrementLife();
                } else {
                    std::lock_guard<Mutex> g(mutex);
                    Tile* headingTo = nullptr;
                    std::uniform_int_distribution<int> dist(0, 3);
                    int guard = 0;
                    do {
                        headingTo = dirs[dist(rng)];
                        ++guard;
                    } while (!headingTo && guard < 16);
                    if (!headingTo) {
                        return;
                    }
                    headingTo->swapOccupants(*here);
                    row = headingTo->r;
                    col = headingTo->c;
                }
            }

            void run() {
                while (game->running.load() && getLife() > 0) {
                    step();
                }
            }
        };

        ToySoldiersSim(int rows, int cols)
            : rows(rows),
              cols(cols),
              tiles(std::make_unique<Tile[]>(static_cast<size_t>(rows) * static_cast<size_t>(cols))) {
            for (int r = 0; r < rows; ++r) {
                for (int c = 0; c < cols; ++c) {
                    auto& t = at(r, c);
                    t.r = r;
                    t.c = c;
                }
            }
        }

        Tile& at(int row, int col) {
            return tiles[static_cast<size_t>(row) * static_cast<size_t>(cols) + static_cast<size_t>(col)];
        }

        bool inBounds(int row, int col) const {
            return row >= 0 && row <= rows - 1 && col >= 0 && col <= cols - 1;
        }

        Tile* getRC(int row, int col) {
            if (!inBounds(row, col)) {
                return nullptr;
            }
            return &at(row, col);
        }

        Tile* relative(int row, int col, Direction d) {
            return getRC(row + dirR(d), col + dirC(d));
        }

        std::shared_ptr<Soldier> addSoldier(int r, int c) {
            std::lock_guard<std::mutex> g(countMutex);
            auto s = std::make_shared<Soldier>(this, r, c);
            ++soldierCount;
            return s;
        }

        void decrementSoldierCount() {
            std::lock_guard<std::mutex> g(countMutex);
            --soldierCount;
            if (soldierCount <= 1) {
                countCond.notify_all();
            }
        }

        void deploy() {
            std::unique_lock<std::mutex> lock(countMutex);
            countCond.wait(lock, [&] { return soldierCount <= 1; });
        }

        void stop() {
            running.store(false);
        }

        int getSoldierCount() {
            std::lock_guard<std::mutex> g(countMutex);
            return soldierCount;
        }

        bool tilesAreConsistent() {
            int occupants = 0;
            std::vector<int> seen;
            for (int r = 0; r < rows; ++r) {
                for (int c = 0; c < cols; ++c) {
                    auto& t = at(r, c);
                    std::lock_guard<Mutex> g(t.mutex);
                    if (t.occupant) {
                        ++occupants;
                        for (int id : seen) {
                            if (id == t.occupant->id) {
                                return false;
                            }
                        }
                        seen.push_back(t.occupant->id);
                        if (t.occupant->row != r || t.occupant->col != c) {
                            return false;
                        }
                    }
                }
            }
            return occupants <= getSoldierCount();
        }
    };

} // namespace parallel_suite::toysoldiers

#endif // TOY_SOLDIERS_SIM_HPP
