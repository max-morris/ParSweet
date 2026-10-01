cmake_minimum_required(VERSION 3.22)

find_package(Java REQUIRED)
include(UseJava)

enable_testing()

project (ParallelSuite NONE)

set(CMAKE_JAVA_COMPILE_FLAGS "-source" "1.10" "-target" "1.10")

add_jar(ParallelSuite 
    ./src/edu/lsu/cct/parallelsuite/Barrier.java 
    ./src/edu/lsu/cct/parallelsuite/ThreadId.java 
    ./src/edu/lsu/cct/parallelsuite/bench/Misc.java
    ./src/edu/lsu/cct/parallelsuite/bench/test/TestToySoldiers.java
    ./src/edu/lsu/cct/parallelsuite/bench/test/TestBarrier.java
    ./src/edu/lsu/cct/parallelsuite/bench/test/TestSets.java
    ./src/edu/lsu/cct/parallelsuite/bench/test/TestLocks.java
    ./src/edu/lsu/cct/parallelsuite/bench/test/TestMaps.java
    ./src/edu/lsu/cct/parallelsuite/maps/SlimMap.java
    ./src/edu/lsu/cct/parallelsuite/maps/SetBasedMap.java
    ./src/edu/lsu/cct/parallelsuite/maps/LockHashMap.java
    ./src/edu/lsu/cct/parallelsuite/ToySoldiersSim.java
    ./src/edu/lsu/cct/parallelsuite/sets/FindCallback.java
    ./src/edu/lsu/cct/parallelsuite/sets/FineGrainedSet.java
    ./src/edu/lsu/cct/parallelsuite/sets/LazySet.java
    ./src/edu/lsu/cct/parallelsuite/sets/LockHashSet.java
    ./src/edu/lsu/cct/parallelsuite/sets/OptimisticSet.java
    ./src/edu/lsu/cct/parallelsuite/sets/SlimSet.java
    ./src/edu/lsu/cct/parallelsuite/locks/AndersonLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/ALock.java
    ./src/edu/lsu/cct/parallelsuite/locks/OptimizedALock.java
    ./src/edu/lsu/cct/parallelsuite/locks/BackoffLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/CLHLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/TwoCounterLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/MCSLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/SlimLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/TASLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/IdLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/TIdLock.java
    ./src/edu/lsu/cct/parallelsuite/locks/TTASLock.java)

get_target_property(_jarFile ParallelSuite JAR_FILE)
get_target_property(_classDir ParallelSuite CLASSDIR)

message(STATUS "Jar file ${_jarFile}")
message(STATUS "Class compiled to ${_classDir}")

add_test(NAME TestParallelJavaToySoldiers COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} edu.lsu.cct.parallelsuite.bench.test.TestToySoldiers)
add_test(NAME TestParallelJavaBarrier COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} edu.lsu.cct.parallelsuite.bench.test.TestBarrier)
add_test(NAME TestParallelJavaSets COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} edu.lsu.cct.parallelsuite.bench.test.TestSets)
add_test(NAME TestParallelJavaMaps COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} edu.lsu.cct.parallelsuite.bench.test.TestMaps)
add_test(NAME TestParallelJavaLocks COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} edu.lsu.cct.parallelsuite.bench.test.TestLocks)
