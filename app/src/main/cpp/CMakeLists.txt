cmake_minimum_required(VERSION 3.22)
project(tsreadex_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ──── libaribcaption ────
add_subdirectory(libaribcaption)

# ──── tsreadex + aribcaption JNI ────
set(TSREADEX_SRC
    tsreadex/util.cpp
    tsreadex/huffman.cpp
    tsreadex/aac.cpp
    tsreadex/servicefilter.cpp
    tsreadex/id3conv.cpp
    tsreadex/traceb24.cpp
)

add_library(tsreadex SHARED
    tsreadex_jni.cpp
    aribcaption_jni.cpp
    ${TSREADEX_SRC}
)

target_include_directories(tsreadex PRIVATE tsreadex)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(tsreadex aribcaption ${log-lib} ${jnigraphics-lib})
