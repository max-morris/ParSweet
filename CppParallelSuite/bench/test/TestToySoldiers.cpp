#include "../../toysoldiers/ToySoldiersSim.hpp"
#include "TestEnv.hpp"
#include <iostream>
#include <thread>
#include <vector>

using parallel_suite::toysoldiers::ToySoldiersSim;

static bool runOnce(int rows, int cols, int r1, int c1, int r2, int c2) {
    ToySoldiersSim sim(rows, cols);
    auto s1 = sim.addSoldier(r1, c1);
    auto s2 = sim.addSoldier(r2, c2);

    std::thread t1([&] { s1->run(); });
    std::thread t2([&] { s2->run(); });

    sim.deploy();
    sim.stop();
    t1.join();
    t2.join();

    auto count = sim.getSoldierCount();
    if (count > 1) {
        std::cout << "ToySoldiers: too many survivors: " << count << std::endl;
        return false;
    }
    if (!sim.tilesAreConsistent()) {
        std::cout << "ToySoldiers: inconsistent tiles" << std::endl;
        return false;
    }
    return true;
}

int main() {
    bool ok = runOnce(5, 5, 2, 2, 4, 4);
    ok = runOnce(3, 3, 0, 0, 2, 2) && ok;
    if (ok) {
        std::cout << "All tests passed." << std::endl;
    } else {
        std::cout << "A test failed!" << std::endl;
    }
    return ok ? 0 : 0xBAD;
}
