cmake_minimum_required(VERSION 3.25)
project(takeoutfm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The shared Kotlin core, built by Gradle (./gradlew :shared:linkReleaseSharedLinuxX64).
# L3 makes CMake run that step itself.
set(TAKEOUT_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../shared/build/bin/linuxX64/takeoutReleaseShared"
    CACHE PATH "Where libtakeout.so is")
add_library(takeout SHARED IMPORTED)
set_target_properties(takeout PROPERTIES
    IMPORTED_LOCATION "${TAKEOUT_LIB_DIR}/libtakeout.so"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/src")

enable_testing()
add_executable(bridge_smoke tests/bridge_smoke.cpp)
target_link_libraries(bridge_smoke PRIVATE takeout)
add_test(NAME bridge_smoke COMMAND bridge_smoke)
