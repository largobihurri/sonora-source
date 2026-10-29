# Vendored LAME 3.100 (libmp3lame)

- Source: the official release tarball `lame-3.100.tar.gz` (https://sourceforge.net/projects/lame/files/lame/3.100/),
  sha256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`.
- Vendored: 2026-10-28, for Sonora's MP3 output in the converter (`core/mp3`).
- Licence: LGPL-2.0-or-later (`COPYING`, the GNU Library General Public License version 2). Sonora is
  GPL-3.0-or-later.
- Build: `../CMakeLists.txt` (static library `mp3lame` linked into `libsonora_mp3.so`; always `-O2` and `NDEBUG`).
  LAME's `configure`, makefiles and scripts are not used.

Only the encoder library is kept: no frontend, no mpglib decoder, no x86 assembly (`i386/`), and from `vector/` only
the header `fft.c` and `quantize.c` include (its SSE source is not built). Copied unmodified:

```
COPYING
include/lame.h
libmp3lame/*.c        bitstream encoder fft gain_analysis id3tag lame mpglib_interface newmdct presets psymodel
                      quantize quantize_pvt reservoir set_get tables takehiro util vbrquantize VbrTag version
libmp3lame/*.h        bitstream encoder fft gain_analysis id3tag l3side lame-analysis lame_global_flags lameerror
                      machine newmdct psymodel quantize quantize_pvt reservoir set_get tables util vbrquantize
                      VbrTag version
libmp3lame/vector/lame_intrin.h
```

`mpglib_interface.c` is part of upstream's library source list; without `HAVE_MPGLIB` it compiles to nothing.

Local changes: none to LAME's files. Added: `config.h` (hand-written for the NDK in place of configure's output; the
same portable C paths on arm64-v8a and x86_64) and this file.

To update: copy the same files from a newer release tarball (check its sha256 against the official release), update
the version and sha256 above, review `config.h` against the new `config.h.in`, and run `:core:mp3:assembleRelease`
and the instrumented `Mp3EncodingTest`.
