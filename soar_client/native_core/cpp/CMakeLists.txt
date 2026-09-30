# CMakeLists.txt
cmake_minimum_required(VERSION 3.10.0)

project(soar_core)

# C++17 requirement
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Create shared library
add_library(soar_core SHARED soar_core.cpp)

# Link against Android's log library for debugging if needed
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(soar_core ${log-lib})
endif()
