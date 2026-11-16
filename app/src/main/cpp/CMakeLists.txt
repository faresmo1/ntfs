cmake_minimum_required(VERSION 3.22.1)
project(ntfsjni C)

set(NTFS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ntfs-3g-src")

file(GLOB NTFS_LIB_SOURCES
    "${NTFS_DIR}/libntfs-3g/*.c"
)
list(FILTER NTFS_LIB_SOURCES EXCLUDE REGEX ".*(unix_io|win32_io)\.c$")

add_library(ntfsjni SHARED
    ntfs_jni.c
    ${NTFS_LIB_SOURCES}
)

target_include_directories(ntfsjni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${NTFS_DIR}/include"
    "${NTFS_DIR}/include/ntfs-3g"
)

target_compile_definitions(ntfsjni PRIVATE
    HAVE_CONFIG_H=1
    _FILE_OFFSET_BITS=64
)

target_compile_options(ntfsjni PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-pointer-sign
    -Wno-deprecated-declarations
    -Wno-incompatible-pointer-types
)

find_library(log-lib log)
target_link_libraries(ntfsjni ${log-lib})
