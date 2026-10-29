# Decoder test fixtures

Short 1 kHz sine tones at amplitude 0.5 (peak 0.5, RMS 0.3536), every channel identical, generated on the host
with FFmpeg 6+ (`-bitexact`, no metadata). Each file stays under 100 KB.

| File | Format | Command (after `S='aevalsrc=0.5*sin(2*PI*1000*t)'`) |
| --- | --- | --- |
| `tone_44k1_s16_mono.wav` | WAV PCM 16-bit LE, 44.1 kHz mono, 1 s | `ffmpeg -bitexact -f lavfi -i "$S:s=44100:c=mono:d=1" -c:a pcm_s16le` |
| `tone_48k_s24_mono.wav` | WAV PCM 24-bit LE, 48 kHz mono, 0.5 s | `ffmpeg -bitexact -f lavfi -i "$S:s=48000:c=mono:d=0.5" -c:a pcm_s24le` |
| `tone_44k1_s16_stereo.flac` | FLAC 16-bit, 44.1 kHz stereo, 1 s | `ffmpeg -bitexact -f lavfi -i "$S\|0.5*sin(2*PI*1000*t):s=44100:d=1" -c:a flac -sample_fmt s16` |
| `tone_48k_s24_mono.flac` | FLAC 24-bit, 48 kHz mono, 1 s | `ffmpeg -bitexact -f lavfi -i "$S:s=48000:c=mono:d=1" -c:a flac -sample_fmt s32 -bits_per_raw_sample 24` |
| `tone_44k1_stereo.mp3` | MP3 128 kb/s (LAME), 44.1 kHz stereo, 1 s | `ffmpeg -bitexact -f lavfi -i "$S\|0.5*sin(2*PI*1000*t):s=44100:d=1" -c:a libmp3lame -b:a 128k` |
| `tone_44k1_stereo.m4a` | AAC-LC 128 kb/s in MP4, 44.1 kHz stereo, 1 s | `ffmpeg -bitexact -f lavfi -i "$S\|0.5*sin(2*PI*1000*t):s=44100:d=1" -c:a aac -b:a 128k` |
| `tone_44k1_s16_stereo.aiff` | AIFF PCM 16-bit BE, 44.1 kHz stereo, 0.25 s | `ffmpeg -bitexact -f lavfi -i "$S\|0.5*sin(2*PI*1000*t):s=44100:d=0.25" -c:a pcm_s16be` |

Lossy files carry encoder delay and padding, so their decoded frame counts are only approximately one second.
