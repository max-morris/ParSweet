cmake_minimum_required(VERSION 3.22)

find_package(Java REQUIRED)
include(UseJava)

enable_testing()

project (ParallelSuite NONE)

set(CMAKE_JAVA_COMPILE_FLAGS "--release" "11")

file(GLOB_RECURSE JAVA_SOURCES CONFIGURE_DEPENDS
     "${CMAKE_CURRENT_SOURCE_DIR}/src/*.java")

add_jar(ParallelSuite ${JAVA_SOURCES})

get_target_property(_jarFile ParallelSuite JAR_FILE)
get_target_property(_classDir ParallelSuite CLASSDIR)

message(STATUS "Jar file ${_jarFile}")
message(STATUS "Class compiled to ${_classDir}")

function(add_java_psweet_test name class)
    add_test(NAME ${name} COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp ${_jarFile} ${class})
    set_tests_properties(${name} PROPERTIES TIMEOUT 120)
endfunction()

add_java_psweet_test(TestParallelJavaToySoldiers edu.lsu.cct.parallelsuite.bench.test.TestToySoldiers)
add_java_psweet_test(TestParallelJavaBarrier edu.lsu.cct.parallelsuite.bench.test.TestBarrier)
add_java_psweet_test(TestParallelJavaSets edu.lsu.cct.parallelsuite.bench.test.TestSets)
add_java_psweet_test(TestParallelJavaMaps edu.lsu.cct.parallelsuite.bench.test.TestMaps)
add_java_psweet_test(TestParallelJavaLocks edu.lsu.cct.parallelsuite.bench.test.TestLocks)
add_java_psweet_test(TestParallelJavaQueue edu.lsu.cct.parallelsuite.bench.test.TestQueue)
add_java_psweet_test(TestParallelJavaBuffer edu.lsu.cct.parallelsuite.bench.test.TestBuffer)
add_java_psweet_test(TestParallelJavaAccounts edu.lsu.cct.parallelsuite.bench.test.TestAccounts)
add_java_psweet_test(TestParallelJavaFairness edu.lsu.cct.parallelsuite.bench.test.TestFairness)

set_tests_properties(TestParallelJavaToySoldiers PROPERTIES TIMEOUT 60)
set_tests_properties(TestParallelJavaBarrier PROPERTIES TIMEOUT 60)
