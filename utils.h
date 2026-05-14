/**
 * utils.h — internal utilities for chiaki src files
 */

#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include <chiaki/common.h>
#include <chiaki/log.h>
#include <chiaki/sock.h>
#include <chiaki/stoppipe.h>

#ifdef _WIN32
#include <winsock2.h>
#else
#include <sys/socket.h>
#endif

// ── XOR Utility ───────────────────────────────────────────────────────────────
static inline void xor_bytes(uint8_t *dst, const uint8_t *src, size_t size)
{
    for(size_t i = 0; i < size; i++)
        dst[i] ^= src[i];
}

// ── Aligned alloc / free ──────────────────────────────────────────────────────
#ifndef CHIAKI_EXPORT

static inline void *chiaki_aligned_alloc(size_t alignment, size_t size)
{
    void *ptr = NULL;
    if(posix_memalign(&ptr, alignment, size) != 0)
        return NULL;
    return ptr;
}

static inline void chiaki_aligned_free(void *ptr)
{
    free(ptr);
}

#endif /* CHIAKI_EXPORT */

// ── Hex dump ──────────────────────────────────────────────────────────────────
#ifndef CHIAKI_EXPORT

static inline void chiaki_log_hexdump(ChiakiLog *log, ChiakiLogLevel level,
                                       const uint8_t *buf, size_t size)
{
    if(!buf || size == 0) return;
    char line[256 * 3 + 1];
    size_t out = 0;
    for(size_t i = 0; i < size && out + 3 < sizeof(line); i++)
        out += (size_t)snprintf(line + out, sizeof(line) - out, "%02x ", buf[i]);
    chiaki_log(log, level, "%s", line);
}

static inline void chiaki_log_hexdump_raw(ChiakiLog *log, ChiakiLogLevel level,
                                           const uint8_t *buf, size_t size)
{
    chiaki_log_hexdump(log, level, buf, size);
}

#endif /* CHIAKI_EXPORT */

// ── Forward declarations for sock.c helpers ───────────────────────────────────
const char *sockaddr_str(struct sockaddr *addr, char *buf, size_t buf_size);

void set_port(struct sockaddr *addr, uint16_t port_network_order);

int sendto_broadcast(ChiakiLog *log, chiaki_socket_t sock,
                     const void *buf, size_t buf_size, int flags,
                     struct sockaddr *addr, size_t addr_size);

ChiakiErrorCode chiaki_send_fully(ChiakiStopPipe *stop_pipe, chiaki_socket_t sock,
                                   const uint8_t *buf, size_t buf_size,
                                   uint64_t timeout_ms);

ChiakiErrorCode format_hex(char *out, size_t out_size,
                            const uint8_t *in, size_t in_size);

ChiakiErrorCode parse_hex(uint8_t *out, size_t *out_size,
                           const char *in, size_t in_size);
