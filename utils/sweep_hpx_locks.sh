#!/usr/bin/env bash
# Sweep BenchLocks' HPX locks over HPX worker counts and HPX-thread counts.
#
# A lock's cost under HPX depends on how many HPX threads (PSWEET_NTHREADS)
# share each worker (OS) thread, so this runs every lock for every worker
# count W in WORKERS and every ratio R in RATIOS, with W * R HPX threads.
# Results are appended to OUT (the hpxThreads column holds W); summarize them
# with psweet_stats.py --hpx-threads W --num-threads T.
#
# Usage: sweep_hpx_locks.sh path/to/BenchLocks
# Settings (environment variables, with defaults):
#   OUT=hpx_locks.csv  WORKERS="1 2 4 8 16"  RATIOS="1 2 4"  REPS=5
#   LOCKS="hpx::spinlock hpx::mutex hpx::two_counter_lock hpx::two_counter_semaphore<1>"
#   PSWEET_WORK_PER_THREAD=20000  PSWEET_MACHINE=$(hostname)
#   TIMEOUT=300 (seconds per run; a run that takes longer is killed and reported)
# Exits with status 1 if any run failed or timed out; BenchLocks fails if it
# does not have a requested lock (e.g. it was built against an HPX without it).
set -euo pipefail

bench=${1:?usage: sweep_hpx_locks.sh path/to/BenchLocks}

OUT=${OUT:-hpx_locks.csv}
WORKERS=${WORKERS:-1 2 4 8 16}
RATIOS=${RATIOS:-1 2 4}
REPS=${REPS:-5}
LOCKS=${LOCKS:-hpx::spinlock hpx::mutex hpx::two_counter_lock hpx::two_counter_semaphore<1>}
TIMEOUT=${TIMEOUT:-300}

export PSWEET_USE_HPX=1
export PSWEET_WORK_PER_THREAD=${PSWEET_WORK_PER_THREAD:-20000}
export PSWEET_MACHINE=${PSWEET_MACHINE:-$(hostname)}

failures=0

# Each run writes to its own file, which is merged into OUT only if the run
# succeeded; a failed run thus leaves no rows behind.
run_out=$(mktemp)
trap 'rm -f "$run_out"' EXIT
# Stop on Ctrl-C even if BenchLocks handles the signal itself (HPX does) and
# exits normally; bash runs this once the current run has ended.
trap 'echo "interrupted" >&2; exit 130' INT TERM

# Repetitions form the outer loop so that slow drifts in machine state (e.g.
# thermal throttling) spread over all configurations instead of one.
for ((rep = 1; rep <= REPS; ++rep)); do
    for workers in $WORKERS; do
        for ratio in $RATIOS; do
            for lock in $LOCKS; do
                threads=$((workers * ratio))
                run="rep $rep, $lock, $workers workers, $threads HPX threads"
                echo "$run"
                : > "$run_out"
                # --foreground keeps BenchLocks in the terminal's process
                # group, so that Ctrl-C reaches it too.
                status=0
                PSWEET_OUT_PATH=$run_out PSWEET_NTHREADS=$threads PSWEET_WHICH=$lock \
                    HPX_COMMANDLINE_OPTIONS="--hpx:threads=$workers" \
                    timeout --foreground "$TIMEOUT" "$bench" > /dev/null || status=$?
                if ((status == 124)); then
                    echo "TIMED OUT after ${TIMEOUT}s: $run" >&2
                    failures=$((failures + 1))
                elif ((status != 0)); then
                    echo "FAILED with status $status: $run" >&2
                    failures=$((failures + 1))
                elif [[ ! -s $OUT ]]; then
                    # Not cp, which would copy the temp file's owner-only mode.
                    cat "$run_out" > "$OUT"
                elif [[ $(head -n 1 "$OUT") != $(head -n 1 "$run_out") ]]; then
                    echo "$OUT has a different header than BenchLocks writes; use a new OUT" >&2
                    exit 1
                else
                    tail -n +2 "$run_out" >> "$OUT"
                fi
            done
        done
    done
done

if ((failures > 0)); then
    echo "$failures run(s) failed; see the messages above" >&2
    exit 1
fi
