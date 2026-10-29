/*
 * Hand-written config.h for building LAME 3.100's libmp3lame with the Android NDK (clang, bionic, LP64), in place of
 * the one configure would generate from config.h.in (configure is never run). Not part of the LAME distribution.
 * Only the macros libmp3lame reads are set; see README.vendor.md.
 */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

/* bionic provides the C89 headers and the C99 integer types. */
#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_UNISTD_H 1

/* configure always defines this for the library build. */
#define LAME_LIBRARY_BUILD 1

/*
 * Not defined, as configure leaves them off for arm64 (its generic CPU case), so both ABIs take the same portable C
 * paths: HAVE_MPGLIB (no decoder), HAVE_NASM (no x86 assembly), HAVE_XMMINTRIN_H (no SSE quantiser),
 * TAKEHIRO_IEEE754_HACK and USE_FAST_LOG.
 */

/* The AH_VERBATIM blocks of configure.in: the IEEE types LAME names. */
typedef double ieee754_float64_t;
typedef float ieee754_float32_t;

#define PACKAGE "lame"
#define VERSION "3.100"

#endif /* LAME_CONFIG_H */
