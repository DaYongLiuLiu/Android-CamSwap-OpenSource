cmake_minimum_required(VERSION 3.22.1)
project("camswap-native-hook" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Dobby inline hook library
set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Dobby)
if(EXISTS ${DOBBY_DIR}/CMakeLists.txt)
    # Dobby build options
    set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
    set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
    add_subdirectory(${DOBBY_DIR} dobby_build)
else()
    message(FATAL_ERROR "Dobby not found at ${DOBBY_DIR}. Run: git submodule update --init --recursive")
endif()

add_library(camswap-native-hook SHARED
    native_audio_hook.cpp
    pcm_bridge.cpp
    hook_aaudio.cpp
    hook_opensles.cpp
)

target_include_directories(camswap-native-hook PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${DOBBY_DIR}/include
)

target_link_libraries(camswap-native-hook
    dobby
    log
    dl
    android
    OpenSLES
)

# CameraServer Native Hook Library (Scheme 2)
add_library(cs_camserver SHARED
    camserver_hook.cpp
    graphic_buffer_bridge.cpp
)

target_include_directories(cs_camserver PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${DOBBY_DIR}/include
)

target_link_libraries(cs_camserver
    dobby
    log
    dl
    android
)

# Root Process Injector (Scheme 2 - Executable)
add_executable(cs_injector
    cs_injector.cpp
)

set_target_properties(cs_injector PROPERTIES
    OUTPUT_NAME "libcs_injector.so"
)

target_link_libraries(cs_injector
    log
    dl
)

# Video Decoding & Frame Feeder Daemon (Scheme 2 - Executable)
add_executable(cs_daemon
    cs_daemon.cpp
)

set_target_properties(cs_daemon PROPERTIES
    OUTPUT_NAME "libcs_daemon.so"
)

target_link_libraries(cs_daemon
    log
    dl
)
