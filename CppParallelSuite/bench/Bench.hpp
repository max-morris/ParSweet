
#ifndef BENCH_PARAMETERS_HPP
#define BENCH_PARAMETERS_HPP

#include "../Types.hpp"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace parallel_bench {

    constexpr static char const* BenchResultHeader = "lang,category,specific,machine,nThreads,workPerThread,ms,debug,hpxThreads";

    // A results file that is missing or empty gets a header before the first
    // row is written.
    inline bool needsBenchResultHeader(std::string const& outPath) {
        return !std::filesystem::exists(outPath) || std::filesystem::is_empty(outPath);
    }

    // Refuse to append rows to a results file written with a different set of
    // columns (e.g. one from before hpxThreads was added); the rows would not
    // line up with its header.
    inline void checkBenchResultHeader(std::string const& outPath) {
        if (needsBenchResultHeader(outPath)) {
            return;
        }

        std::ifstream f(outPath);
        std::string header;
        if (!std::getline(f, header)) {
            throw std::runtime_error("cannot read PSWEET_OUT_PATH " + outPath);
        }
        if (header != BenchResultHeader) {
            throw std::runtime_error("PSWEET_OUT_PATH " + outPath + " has header \"" + header + "\", expected \"" +
                                     BenchResultHeader + "\"; use a new output file");
        }
    }

    class BenchParameters {
    private:
        std::string lang;
        std::string category;
        std::string machine{};
        std::string outPath{};
        std::optional<std::string> which{};
        usize nThreads{};
        usize workPerThread{};
        usize cooldown{};
        bool useHpx{false};
        usize hpxThreads{0};

#ifdef NDEBUG
        static constexpr bool debug{false};
#else
        static constexpr bool debug{true};
#endif
    public:
        BenchParameters(std::string lang, std::string category) : lang(std::move(lang)),
                                                                  category(std::move(category)) {
            if (auto* szNThreads = std::getenv("PSWEET_NTHREADS")) {
                nThreads = std::strtoull(szNThreads, nullptr, 10);
            } else {
                throw std::runtime_error("bad PSWEET_NTHREADS");
            }

            if (auto* szWorkPerThread = std::getenv("PSWEET_WORK_PER_THREAD")) {
                workPerThread = std::strtoull(szWorkPerThread, nullptr, 10);
            } else {
                throw std::runtime_error("bad PSWEET_WORK_PER_THREAD");
            }

            if (auto* szMachine = std::getenv("PSWEET_MACHINE")) {
                machine = szMachine;
            } else {
                throw std::runtime_error("bad PSWEET_MACHINE");
            }

            if (auto* szOutPath = std::getenv("PSWEET_OUT_PATH")) {
                outPath = szOutPath;
            } else {
                outPath = "psweet.csv";
            }
            checkBenchResultHeader(outPath);

            if (auto* szCooldown = std::getenv("PSWEET_COOLDOWN")) {
                cooldown = std::strtoull(szCooldown, nullptr, 10);
            } else {
                cooldown = 0;
            }

            if (auto* szWhich = std::getenv("PSWEET_WHICH")) {
                which = szWhich;
            }

            if (auto* szUseHpx = std::getenv("PSWEET_USE_HPX")) {
                if (std::strcmp(szUseHpx, "1") == 0) {
                    useHpx = true;
                }
            }
        }

        [[nodiscard]]
        inline std::string const& getLang() const {
            return lang;
        }

        [[nodiscard]]
        inline std::string const& getCategory() const {
            return category;
        }

        [[nodiscard]]
        inline std::string const& getMachine() const {
            return machine;
        }

        [[nodiscard]]
        inline usize const& getNThreads() const {
            return nThreads;
        }

        [[nodiscard]]
        inline usize const& getWorkPerThread() const {
            return workPerThread;
        }

        [[nodiscard]]
        inline std::string const& getOutPath() const {
            return outPath;
        }

        [[nodiscard]]
        inline std::optional<std::string> const& getWhich() const {
            return which;
        }

        [[nodiscard]]
        inline bool const& getUseHpx() const {
            return useHpx;
        }

        [[nodiscard]]
        inline usize const& getHpxThreads() const {
            return hpxThreads;
        }

        // Benchmarks that run under HPX record how many worker (OS) threads
        // the HPX runtime has, since the cost of a lock depends on how many
        // HPX threads (nThreads) share each worker. 0 means "not run under
        // HPX".
        inline void setHpxThreads(usize n) {
            hpxThreads = n;
        }

        [[nodiscard]]
        inline static consteval bool getDebug() {
            return debug;
        }

        inline void coolOff() const {
            if (cooldown > 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(cooldown));
            }
        }
    };

    // PSWEET_WHICH, if present, must name exactly one of a benchmark program's
    // tests, which is then the only one run; an HPX test also needs
    // PSWEET_USE_HPX=1. stdTests and hpxTests are the names of the program's
    // tests outside and inside HPX. Returns whether the program should start
    // HPX: PSWEET_USE_HPX=1, and PSWEET_WHICH is absent or names an HPX test.
    inline bool checkWhich(BenchParameters const& params, std::string const& program,
                           std::vector<std::string> const& stdTests, std::vector<std::string> const& hpxTests) {
        auto const& which = params.getWhich();
        auto names = [&which](std::vector<std::string> const& tests) {
            return which && std::find(tests.begin(), tests.end(), *which) != tests.end();
        };
        bool const whichIsStd = names(stdTests);
        bool const whichIsHpx = names(hpxTests);

        if (which && !whichIsStd && !whichIsHpx) {
            // The names differ between programs (they are what the results'
            // specific column shows), so list the accepted ones.
            std::string accepted;
            for (auto const* tests : {&stdTests, &hpxTests}) {
                for (auto const& name : *tests) {
                    accepted += (accepted.empty() ? "" : ", ") + name;
                }
            }
            throw std::runtime_error("PSWEET_WHICH=" + *which + " names no " + program + " test; it accepts " +
                                     accepted);
        }
        if (whichIsHpx && !params.getUseHpx()) {
            throw std::runtime_error("PSWEET_WHICH names an HPX lock; set PSWEET_USE_HPX=1 to run it");
        }
        return params.getUseHpx() && !whichIsStd;
    }

    using Duration_t = decltype(std::chrono::high_resolution_clock::now() - std::chrono::high_resolution_clock::now());

    template <typename F, typename... Args>
    inline Duration_t measure(F&& fn, Args&&... args) {
        auto t1 = std::chrono::high_resolution_clock::now();
        fn(std::forward<Args>(args)...);
        auto t2 = std::chrono::high_resolution_clock::now();
        return t2 - t1;
    }

    template <typename D>
    inline auto countDuration(Duration_t const& duration) {
        return std::chrono::duration_cast<D>(duration).count();
    }

    void writeBenchResult(BenchParameters const& params, std::string const& specific, Duration_t const& duration) {
        auto const& outPath = params.getOutPath();

        std::ofstream f;
        if (needsBenchResultHeader(outPath)) {
            f = std::ofstream(outPath);
            f << BenchResultHeader << std::endl;
        } else {
            f = std::ofstream(outPath, std::ios::app);
        }

        f << params.getLang() << "," << params.getCategory() << "," << specific << "," << params.getMachine() << ","
          << params.getNThreads() << "," << params.getWorkPerThread() << "," << countDuration<std::chrono::milliseconds>(duration)
          << "," << BenchParameters::getDebug() << "," << params.getHpxThreads() << std::endl;

        std::cout << "Wrote to " << std::filesystem::absolute(std::filesystem::path(outPath)) << std::endl;
    }

} // namespace parallel_bench

#endif //BENCH_PARAMETERS_HPP
