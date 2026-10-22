# ParSweet: A Suite of Codes for Benchmarking and Testing Mutex-Based Parallel Systems 

[![DOI](https://zenodo.org/badge/734404770.svg)](https://zenodo.org/doi/10.5281/zenodo.10685293)

## What?
ParSweet is a collection of small- to medium-sized codes designed to cover common (or uncommon) use cases of mutual
exclusion in parallel programs. It also comes with correctness tests and performance benchmarks.

Coverage currently exists for the following languages/frameworks:
* C++
* HPX
* Java

## How? (C++)
Compile with CMake. GCC >= 12 is needed. Other compilers should work, but have not been thoroughly tested.
HPX is an optional dependency; if it is found, benchmarks that require it will be enabled.

Benchmarks expect the following environment variables at runtime:
* PSWEET_NTHREADS - Number of threads to use.
* PSWEET_WORK_PER_THREAD - How much work each thread should perform. What exactly this entails depends on the specific benchmark.
* PSWEET_MACHINE - A unique identifier. This will be written to the benchmark results file to help identify which machine each result comes from.
* PSWEET_COOLDOWN (Optional) - How long, in milliseconds, to sleep between individual tests within one benchmark program. 
Weaker machines may need this to avoid benchmark results being confounded by thermal throttling. If absent, defaults to 0 (no sleep occurs).
* PSWEET_WHICH (Optional) - Specifies which individual test to run within one benchmark program. If absent, the entire benchmark runs.
* PSWEET_WATCHES (Optional, BenchWideWatch) - Keys each delayed operation scans under its lock. Default 32.
* PSWEET_OPS (Optional, BenchWideWatch) - Delayed operations, each with its own lock. Default 8.

## How? (Java)
Build JavaParallelSuite with CMake. The benchmark harness is edu.lsu.cct.parallelsuite.bench.BenchParameters. It reads the same PSWEET_NTHREADS, PSWEET_WORK_PER_THREAD, PSWEET_MACHINE, PSWEET_OUT_PATH, PSWEET_COOLDOWN, and PSWEET_WHICH variables listed above and appends the same CSV columns as the C++ benches.

BenchWideWatch is a main class in ParallelSuite.jar. It calls BlackBox.consume on the scan result so the JIT cannot delete that loop, matching C++ blackBox.

    PSWEET_NTHREADS=4 PSWEET_WORK_PER_THREAD=100 PSWEET_MACHINE=laptop \
      java -cp JavaParallelSuite/build/ParallelSuite.jar edu.lsu.cct.parallelsuite.bench.BenchWideWatch

The C++ binary is CppParallelSuite/build/BenchWideWatch.

## Who?
See AUTHORS.

## Contributing
TODO
