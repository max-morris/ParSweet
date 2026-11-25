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
In BenchLocks, BenchMapByLocks and BenchSetByLocks, it must name one lock (e.g. `std::mutex`), whose test is then the
only one run; an HPX lock (e.g. `hpx::spinlock`) also needs PSWEET_USE_HPX=1. Locks with template arguments carry them
in their names (e.g. `BackoffLock<1:17>`, `TwoCounterSemaphore<1>`). An unknown name makes the program list the names it
accepts.
* PSWEET_USE_HPX (Optional) - Set to 1 to also run the HPX variants of the benchmarks. There, PSWEET_NTHREADS is the
number of HPX threads, which share the HPX runtime's worker (OS) threads. Set the number of workers through HPX's own
environment variable, e.g. `HPX_COMMANDLINE_OPTIONS=--hpx:threads=8`; it is recorded in the results file's hpxThreads
column (0 for runs outside HPX).
* PSWEET_OUT_PATH (Optional) - The results file (CSV) to append to; defaults to `psweet.csv`. A benchmark refuses to
append to a file whose header differs from its own (such as one written before the hpxThreads column existed).

`utils/sweep_hpx_locks.sh` runs BenchLocks' HPX locks over a range of worker and HPX-thread counts; see the script for
its settings. Summarize results with `utils/psweet_stats.py`, using `--hpx-threads` to select one worker count.

## How? (Java)
TODO

## Who?
See AUTHORS.

## Contributing
TODO
