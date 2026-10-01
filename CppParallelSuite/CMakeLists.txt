cmake_minimum_required(VERSION 3.22)
project(CppParallelSuite)

set(CMAKE_CXX_STANDARD 20)

include(CheckCXXCompilerFlag)

check_cxx_compiler_flag(-Winterference-size HAVE_W_INTERFERENCE_SIZE)
if (HAVE_W_INTERFERENCE_SIZE)
    add_compile_options(-Wno-interference-size)
endif ()

check_cxx_compiler_flag(-pthread HAVE_PTHREAD)
if (HAVE_PTHREAD)
    set(CMAKE_CXX_FLAGS ${CMAKE_CXX_FLAGS} "-pthread")
endif ()

set(THREADS_PREFER_PTHREAD_FLAG ON)

if (DEFINED ENV{ASAN})
    add_compile_options(-fsanitize=address)
    add_link_options(-fsanitize=address)
    message(STATUS "ASan enabled")
endif ()

if (DEFINED ENV{UBSAN})
    add_compile_options(-fsanitize=undefined)
    add_link_options(-fsanitize=undefined)
    message(STATUS "UBSan enabled")
endif ()

find_package(Threads REQUIRED)

find_package(HPX QUIET)
if (HPX_FOUND)
    message("-- HPX Found")
    add_compile_definitions(HAVE_HPX=1)
else ()
    add_compile_definitions(HAVE_HPX=0)
endif ()

cmake_host_system_information(RESULT N_LOGICAL_CORES
                              QUERY NUMBER_OF_LOGICAL_CORES)
add_compile_definitions(N_LOGICAL_CORES=${N_LOGICAL_CORES})
message(STATUS "Setting N_LOGICAL_CORES=${N_LOGICAL_CORES}")

# Sometimes we need to statically allocate memory depending on the maximum number of threads.
# By default, set this to the number of logical cores plus a little. Override this with PSWEET_N_THREADS_ALLOC.
if (DEFINED ENV{PSWEET_N_THREADS_ALLOC})
    message(STATUS "Setting N_THREADS_ALLOC=$ENV{PSWEET_N_THREADS_ALLOC}")
    add_compile_definitions(N_THREADS_ALLOC=$ENV{PSWEET_N_THREADS_ALLOC})
else ()
    if (N_LOGICAL_CORES LESS_EQUAL 0)
        message(WARNING "Could not determine number of cores, and PSWEET_N_THREADS_ALLOC is not defined. Defaulting to 100.")
        message(STATUS "Setting N_THREADS_ALLOC=100")
        add_compile_definitions(N_THREADS_ALLOC=100)
    else ()
        math(EXPR N_LOGICAL_CORES "${N_LOGICAL_CORES} * 6 / 5 + 1")
        message(STATUS "Setting N_THREADS_ALLOC=${N_LOGICAL_CORES}")
        add_compile_definitions(N_THREADS_ALLOC=${N_LOGICAL_CORES})
    endif ()
endif ()

add_executable(TestBarrier
               barrier/Barrier.hpp
               bench/test/TestBarrier.cpp)

add_executable(TestLocks
               bench/test/TestLocks.cpp
               MutexType.hpp
               locks/TASLock.hpp
               locks/TTASLock.hpp
               locks/ALock.hpp
               threadlocal/ThreadLocal.hpp
               locks/BackoffLock.hpp
               Rand.hpp
               locks/CLHLock.hpp
               locks/MCSLock.hpp
               threadlocal/ThreadLocalRand.cpp
               threadlocal/ThreadId.cpp
               locks/IdLock.hpp
               locks/TIdLock.hpp
               locks/TwoCounterLock.hpp
               locks/OptimizedALock.hpp
               locks/AndersonLock.hpp)

add_executable(TestSets
               bench/test/TestSets.cpp
               sets/FineGrainedSet.hpp
               sets/OptimisticSet.hpp
               sets/LazySet.hpp
               sets/NodeMarkers.hpp
               sets/SetNode.hpp
               sets/SetTypes.hpp
               sets/LockHashSet.hpp)

add_executable(TestMaps
               bench/test/TestMaps.cpp
               maps/LockHashMap.hpp
               maps/MapTypes.hpp
               maps/SetBasedMap.hpp)

if (HPX_FOUND)
    add_hpx_executable(BenchLocks
                       SOURCES bench/BenchLocks.cpp
                       threadlocal/ThreadId.cpp
                       threadlocal/ThreadLocalRand.cpp)
else ()
    add_executable(BenchLocks
                   bench/Bench.hpp
                   bench/BenchLocks.cpp
                   threadlocal/ThreadId.cpp
                   threadlocal/ThreadLocalRand.cpp)
endif ()

add_executable(TuneBackoffLock
               bench/Bench.hpp
               bench/TuneBackoffLock.cpp
               threadlocal/ThreadId.cpp
               threadlocal/ThreadLocalRand.cpp)

add_executable(BenchSets
               bench/Bench.hpp
               bench/BenchSets.cpp
               bench/BlackBox.hpp
               sets/LockHashSet.hpp)

add_executable(BenchMaps
               bench/Bench.hpp
               bench/BenchMaps.cpp
               bench/BlackBox.hpp)


if (HPX_FOUND)
    add_hpx_executable(BenchSetByLocks
                       SOURCES bench/Bench.hpp
                       bench/BenchSetByLocks.cpp
                       bench/BlackBox.hpp
                       threadlocal/ThreadLocalRand.cpp
                       threadlocal/ThreadId.cpp
                       locks/LockTraits.hpp)

    add_hpx_executable(BenchMapByLocks
                       SOURCES bench/Bench.hpp
                       bench/BenchMapByLocks.cpp
                       bench/BlackBox.hpp
                       threadlocal/ThreadLocalRand.cpp
                       threadlocal/ThreadId.cpp
                       locks/LockTraits.hpp)
else ()
    add_executable(BenchSetByLocks
                   bench/Bench.hpp
                   bench/BenchSetByLocks.cpp
                   bench/BlackBox.hpp
                   threadlocal/ThreadLocalRand.cpp
                   threadlocal/ThreadId.cpp
                   locks/LockTraits.hpp)

    add_executable(BenchMapByLocks
                   bench/Bench.hpp
                   bench/BenchMapByLocks.cpp
                   bench/BlackBox.hpp
                   threadlocal/ThreadLocalRand.cpp
                   threadlocal/ThreadId.cpp
                   locks/LockTraits.hpp)
endif ()

link_libraries(Threads::Threads)

enable_testing()

add_test(NAME TestLocks
         COMMAND ./TestLocks)
add_test(NAME TestBarrier
         COMMAND ./TestBarrier)
add_test(NAME TestSets
         COMMAND ./TestSets)
add_test(NAME TestMaps
         COMMAND ./TestMaps)
