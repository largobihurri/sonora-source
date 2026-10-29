# Vendored qm-dsp (Mixxx fork)

- Source: https://github.com/mixxxdj/mixxx, directory `lib/qm-dsp`, commit
  `332b8026fbf35509a6e006c9e1eba89a491e8ec3` (2026-10-03). Mixxx's fork of
  https://github.com/c4dm/qm-dsp; its changes against upstream are in `mixxx-changes.patch` (ConstantQ phase fix,
  GetKeyMode chroma offset, TempoTrackV2 half-window padding and `vector<int>` beat periods, Pitch rounding).
- Vendored: 2026-10-03, for Sonora's on-device track analysis (`core/analysis`). Files are copied unmodified.
- Licences: qm-dsp GPL-2.0-or-later (`COPYING`); kissfft BSD-3-Clause (`ext/kissfft/COPYING`). Sonora is
  GPL-3.0-or-later.
- Build: `../CMakeLists.txt` (static library `qmdsp`, `kiss_fft_scalar=double`, C++17/C11, no `-ffast-math`).

Only the files needed for onset detection, tempo/beat tracking, downbeats and key detection are kept:

```
COPYING
README.md
mixxx-changes.patch
base/Pitch.cpp
base/Pitch.h
base/Restrict.h
base/Window.h
dsp/chromagram/Chromagram.cpp
dsp/chromagram/Chromagram.h
dsp/chromagram/ConstantQ.cpp
dsp/chromagram/ConstantQ.h
dsp/keydetection/GetKeyMode.cpp
dsp/keydetection/GetKeyMode.h
dsp/onsets/DetectionFunction.cpp
dsp/onsets/DetectionFunction.h
dsp/phasevocoder/PhaseVocoder.cpp
dsp/phasevocoder/PhaseVocoder.h
dsp/rateconversion/Decimator.cpp
dsp/rateconversion/Decimator.h
dsp/tempotracking/DownBeat.cpp
dsp/tempotracking/DownBeat.h
dsp/tempotracking/TempoTrackV2.cpp
dsp/tempotracking/TempoTrackV2.h
dsp/transforms/FFT.cpp
dsp/transforms/FFT.h
ext/kissfft/COPYING
ext/kissfft/README
ext/kissfft/_kiss_fft_guts.h
ext/kissfft/kiss_fft.c
ext/kissfft/kiss_fft.h
ext/kissfft/tools/kiss_fftr.c
ext/kissfft/tools/kiss_fftr.h
maths/KLDivergence.cpp
maths/KLDivergence.h
maths/MathAliases.h
maths/MathUtilities.cpp
maths/MathUtilities.h
maths/nan-inf.h
```

To update: copy the same files from a newer Mixxx commit, update the commit above, and run
`./gradlew :core:analysis:hostNativeTest` and the instrumented tests; bump `AndroidTrackAnalyzer.ANALYZER_VERSION`
when results can change.
