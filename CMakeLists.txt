cmake_minimum_required(VERSION 3.22.1)
project(chiaki)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_compile_options(
    -Wall
    -Wno-unused-parameter
    -Wno-missing-field-initializers
    -O2
)

include(android_openssl/android_openssl.cmake)

# ── chiaki-ng source roots ────────────────────────────────────────────────────
set(CHIAKI_ROOT      ${CMAKE_CURRENT_SOURCE_DIR}/chiaki)
set(CHIAKI_LIB_SRC   ${CHIAKI_ROOT}/lib/src)
set(CHIAKI_LIB_INC   ${CHIAKI_ROOT}/lib/include)
set(CHIAKI_THIRD     ${CHIAKI_ROOT}/third-party)
set(CHIAKI_PROTO_DIR ${CHIAKI_ROOT}/lib/protobuf)

# ── nanopb (protobuf for senkusha / streamconnection) ────────────────────────
set(NANOPB_DIR ${CHIAKI_THIRD}/nanopb)

set(NANOPB_SRCS
    ${NANOPB_DIR}/pb_encode.c
    ${NANOPB_DIR}/pb_decode.c
    ${NANOPB_DIR}/pb_common.c
)

# ── Jerasure + GF-Complete (erasure coding for common.c / frameprocessor.c) ──
set(JERASURE_DIR  ${CHIAKI_THIRD}/jerasure)
set(GFCOMPLETE_DIR ${CHIAKI_THIRD}/gf-complete)

set(JERASURE_SRCS
    ${JERASURE_DIR}/src/galois.c
    ${JERASURE_DIR}/src/jerasure.c
    ${JERASURE_DIR}/src/reed_sol.c
    ${JERASURE_DIR}/src/cauchy.c
    ${JERASURE_DIR}/src/liberation.c
)

set(GFCOMPLETE_SRCS
    ${GFCOMPLETE_DIR}/src/gf.c
    ${GFCOMPLETE_DIR}/src/gf_cpu.c
    ${GFCOMPLETE_DIR}/src/gf_w4.c
    ${GFCOMPLETE_DIR}/src/gf_w8.c
    ${GFCOMPLETE_DIR}/src/gf_w16.c
    ${GFCOMPLETE_DIR}/src/gf_w32.c
    ${GFCOMPLETE_DIR}/src/gf_w64.c
    ${GFCOMPLETE_DIR}/src/gf_w128.c
    ${GFCOMPLETE_DIR}/src/gf_wgen.c
    ${GFCOMPLETE_DIR}/src/gf_general.c
    ${GFCOMPLETE_DIR}/src/gf_method.c
    ${GFCOMPLETE_DIR}/src/gf_rand.c
)

# ── takion.pb.c/h — pre-generated from lib/protobuf/takion.proto ─────────────
set(TAKION_PB_SRCS
    ${CHIAKI_LIB_SRC}/takion.pb.c
)

# ── libcurl (Static Android Prebuilt) ─────────────────────────────────────────
add_library(curl STATIC IMPORTED)
set_target_properties(curl PROPERTIES IMPORTED_LOCATION 
    ${CMAKE_CURRENT_SOURCE_DIR}/curl/lib/${ANDROID_ABI}/libcurl.a
)

# ── json-c (Compile from source) ──────────────────────────────────────────────
set(BUILD_SHARED_LIBS OFF CACHE INTERNAL "")
set(BUILD_TESTING OFF CACHE INTERNAL "")
set(BUILD_APPS OFF CACHE INTERNAL "")
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/json-c)

# ── miniupnpc (Compile from source) ───────────────────────────────────────────
set(UPNPC_BUILD_SHARED OFF CACHE INTERNAL "")
set(UPNPC_BUILD_TESTS OFF CACHE INTERNAL "")
set(UPNPC_BUILD_SAMPLE OFF CACHE INTERNAL "")
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/miniupnp/miniupnpc)

# Create a wrapper directory so #include <miniupnpc/miniupnpc.h> works natively
file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/miniupnp_wrapper/miniupnpc)
file(GLOB MINIPNP_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/miniupnp/miniupnpc/include/*.h")
file(COPY ${MINIPNP_HEADERS} DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/miniupnp_wrapper/miniupnpc)

# ── Main shared library ───────────────────────────────────────────────────────
add_library(chiaki SHARED
    # JNI bridge (this project)
    ${CMAKE_CURRENT_SOURCE_DIR}/chiaki-jni.c

    # chiaki-ng core
    ${CHIAKI_LIB_SRC}/session.c
    ${CHIAKI_LIB_SRC}/remote/dummy_holepunch.c   # stub — no PSN holepunch
    ${CHIAKI_LIB_SRC}/remote/rudp.c              # ADDED: RUDP init/fini/send
    ${CHIAKI_LIB_SRC}/remote/rudpsendbuffer.c    # ADDED: RUDP send buffer
    ${CHIAKI_LIB_SRC}/ctrl.c
    ${CHIAKI_LIB_SRC}/rpcrypt.c
    ${CHIAKI_LIB_SRC}/log.c
    ${CHIAKI_LIB_SRC}/sock.c
    ${CHIAKI_LIB_SRC}/base64.c
    ${CHIAKI_LIB_SRC}/random.c
    ${CHIAKI_LIB_SRC}/stoppipe.c
    ${CHIAKI_LIB_SRC}/time.c
    ${CHIAKI_LIB_SRC}/common.c
    ${CHIAKI_LIB_SRC}/controller.c
    ${CHIAKI_LIB_SRC}/thread.c

    ${CHIAKI_LIB_SRC}/regist.c
    ${CHIAKI_LIB_SRC}/ecdh.c

    ${CHIAKI_LIB_SRC}/takion.c
    ${CHIAKI_LIB_SRC}/takionsendbuffer.c
    ${CHIAKI_LIB_SRC}/gkcrypt.c

    ${CHIAKI_LIB_SRC}/http.c
    ${CHIAKI_LIB_SRC}/reorderqueue.c
    ${CHIAKI_LIB_SRC}/streamconnection.c
    ${CHIAKI_LIB_SRC}/senkusha.c
    ${CHIAKI_LIB_SRC}/congestioncontrol.c
    ${CHIAKI_LIB_SRC}/frameprocessor.c

    ${CHIAKI_LIB_SRC}/feedback.c
    ${CHIAKI_LIB_SRC}/feedbacksender.c

    ${CHIAKI_LIB_SRC}/discovery.c
    ${CHIAKI_LIB_SRC}/discoveryservice.c

    # ADDED: audio/video pipeline
    ${CHIAKI_LIB_SRC}/audioreceiver.c
    ${CHIAKI_LIB_SRC}/videoreceiver.c
    ${CHIAKI_LIB_SRC}/audio.c
    ${CHIAKI_LIB_SRC}/audiosender.c

    # ADDED: stream helpers
    ${CHIAKI_LIB_SRC}/packetstats.c     # chiaki_packet_stats_*
    ${CHIAKI_LIB_SRC}/fec.c             # chiaki_fec_decode
    ${CHIAKI_LIB_SRC}/launchspec.c      # chiaki_launchspec_format
    ${CHIAKI_LIB_SRC}/bitstream.c
    ${CHIAKI_LIB_SRC}/orientation.c

    # Third-party dependencies
    ${TAKION_PB_SRCS}
    ${NANOPB_SRCS}
    ${JERASURE_SRCS}
    ${GFCOMPLETE_SRCS}
)

target_include_directories(chiaki PRIVATE
    # chiaki-ng public headers
    ${CHIAKI_LIB_INC}
    # chiaki-ng internal headers
    ${CHIAKI_LIB_SRC}
    # this project's own headers
    ${CMAKE_CURRENT_SOURCE_DIR}
    # nanopb
    ${NANOPB_DIR}
    # Jerasure
    ${JERASURE_DIR}/include
    # GF-Complete
    ${GFCOMPLETE_DIR}/include
    # libcurl headers
    ${CMAKE_CURRENT_SOURCE_DIR}/curl/include
    # miniupnpc wrapper headers
    ${CMAKE_CURRENT_BINARY_DIR}/miniupnp_wrapper
)

find_library(log-lib log)

target_link_libraries(chiaki
    ${log-lib}
    OpenSSL::SSL
    OpenSSL::Crypto
    curl
    json-c
    libminiupnpc-static
)

# Enable 16KB page size alignment for Android 15+ compatibility
target_link_options(chiaki PRIVATE "-Wl,-z,max-page-size=16384")