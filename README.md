# ParSweet: A Suite of Codes for Benchmarking and Testing Mutex-Based Parallel Systems

[![DOI](https://zenodo.org/badge/734404770.svg)](https://zenodo.org/doi/10.5281/zenodo.10685293)

## What?

ParSweet is a collection of small- to medium-sized codes designed to cover common (or uncommon) use cases of mutual
exclusion in parallel programs. It also comes with correctness tests and performance benchmarks.

Coverage currently exists for the following languages/frameworks:

* C++
* HPX
* Java

The Java and C++ trees implement the same algorithms and run the same correctness scenarios. Benchmarks write a shared CSV schema so `utils/psweet_stats.py` can plot either language.

## Algorithms

Locks: TAS, TTAS, Anderson (`ALock`), cache-line padded Anderson (`OptimizedALock`), Backoff, CLH, MCS, IdLock, TIdLock, TwoCounterLock, plus the language-standard mutex (`std::mutex` / `ReentrantLock`). C++ optionally also benches `hpx::mutex` and `hpx::spinlock`.

Sets: FineGrained, Optimistic, Lazy, partitioned `LockHashSet`.

Maps: partitioned `LockHashMap`, `SetBasedMap` over each set.

Other applications: barrier, ToySoldiers (multi-lock grid), two-lock queue, bounded buffer, account transfer.

Custom spinlocks in this suite do not implement condition variables. Apps that wait (barrier, bounded buffer, ToySoldiers' "last soldier" wait) use `std::mutex` / `ReentrantLock` for the wait/signal path. Data-structure locks can still be swapped.

## How? (C++)

Compile with CMake. GCC >= 12 is needed. Other compilers should work, but have not been thoroughly tested.
HPX is an optional dependency; if it is found, benchmarks that require it will be enabled.

```
cmake -S CppParallelSuite -B CppParallelSuite/build -DCMAKE_BUILD_TYPE=Release
cmake --build CppParallelSuite/build
ctest --test-dir CppParallelSuite/build --output-on-failure
```

Tests honor `PSWEET_NTHREADS` when set (default 12).

## How? (Java)

Java 11+ is required. Build the jar with CMake (or any Java compiler on `src/`):

```
cmake -S JavaParallelSuite -B JavaParallelSuite/build
cmake --build JavaParallelSuite/build
ctest --test-dir JavaParallelSuite/build --output-on-failure
```

Correctness tests are `edu.lsu.cct.parallelsuite.bench.test.Test*` and must be run with `-ea`. Benchmarks are `edu.lsu.cct.parallelsuite.bench.Bench*`.

```
java -ea -cp JavaParallelSuite/build/ParallelSuite.jar edu.lsu.cct.parallelsuite.bench.test.TestLocks
java -cp JavaParallelSuite/build/ParallelSuite.jar edu.lsu.cct.parallelsuite.bench.BenchLocks
```

## Benchmarks

Benchmarks expect the following environment variables at runtime (C++ and Java):

* `PSWEET_NTHREADS` - Number of threads to use.
* `PSWEET_WORK_PER_THREAD` - How much work each thread should perform. What exactly this entails depends on the specific benchmark.
* `PSWEET_MACHINE` - A unique identifier. This will be written to the benchmark results file to help identify which machine each result comes from.
* `PSWEET_OUT_PATH` (Optional) - CSV output path. Defaults to `psweet.csv`.
* `PSWEET_COOLDOWN` (Optional) - How long, in milliseconds, to sleep between individual tests within one benchmark program.
  Weaker machines may need this to avoid benchmark results being confounded by thermal throttling. If absent, defaults to 0 (no sleep occurs).
* `PSWEET_WHICH` (Optional) - Specifies which individual test to run within one benchmark program. If absent, the entire benchmark runs.
* `PSWEET_USE_HPX` (C++ only) - Set to `1` to also run HPX lock variants.

CSV columns: `lang,category,specific,machine,nThreads,workPerThread,ms,debug`.

Summarize with `python3 utils/psweet_stats.py psweet.csv --category locks`.

C++ binaries live in the CMake build directory (`BenchLocks`, `BenchSets`, `BenchMaps`, `BenchSetByLocks`, `BenchMapByLocks`, `BenchQueue`, `BenchBuffer`, `BenchAccountByLocks`). Java mains are `BenchLocks`, `BenchSets`, `BenchMaps`, `BenchSetByLocks`, `BenchMapByLocks`, `BenchQueue`, `BenchBuffer`, `BenchAccountByLocks` in package `edu.lsu.cct.parallelsuite.bench`.

## Who?

See AUTHORS.

## Contributing

Pull requests that add a mutex use-case, a lock implementation, or a matching test/benchmark in **both** Java and C++ are welcome. Keep samples small-to-medium (not a full application, not a one-line deadlock). Add a CMake `add_test` for correctness; do not `add_test` long-running benchmarks.

Format C++ with clang-format 17 (`CppParallelSuite`). GitHub Actions runs C++ and Java `ctest` plus the clang-format check.
