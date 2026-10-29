# DUB mode for Sonora: final design

## 1. Summary

1. DUB mode is a new Now Playing layout that turns the playing stereo track into a dub instrument. This design starts from the judges' winner, "performance-first": five thumb pads (THROW, CUT, DROP, FREEZE, RUNAWAY), a TAP pad, an XY sweep filter, and a tape-style echo locked to the beat grid.
2. All processing runs in one new forwarding sink, `DubAudioSink`, which sits between `LoopAudioSink` and `EqualizerAudioSink`. It does no allocation and takes no locks per buffer. The pure DSP lives in `core:audiodsp` so it can be tested on the JVM.
3. Ideas taken from the other two designs: RUNAWAY and stop-with-tail move into v1. Settings keep the effect setup but reset the performance state each time the mode opens. DJ loops and the beat grid stay active under a compact waveform strip. Pads are momentary by default and latch on a double-tap.
4. The latency-compensated THROW stays in v1, but its two traps are fixed: a separate pending-send ring, and a clock that converts between time bases. Quantized pads, the spring-style reverb with CRASH, the isolator strips and the siren come in v2, behind a measured CPU and underrun gate.
5. The feature runs only on the device and needs no store services and no new permission, so the play and full flavors ship the same feature. The stage passes audio through untouched while bit-perfect output is armed or a remote receiver is playing.

## 2. Features

| Feature | Behaviour | Phase |
|---|---|---|
| Mode switch and entry | A Settings > DUB Mode switch, plus a checked row "DUB mode" in the Now Playing "..." menu. Both write the same flag. The layout swaps in 300 ms, as DJ mode does. Turning DUB mode on turns the DJ layout off (see Q1). | v1 |
| Compact deck strip | Reuses the DJ waveform with its beat grid and playhead, at 56 dp high, plus play/stop, the BPM source readout ("GRID 124.0" / "TAP 87.5") and four beat LEDs. Loops and hot cues from the loop hub stay usable, so a loop can hold a riddim. | v1 |
| THROW pad | Hold opens a pre-fader send of the dry track into the echo, ramped over 5 ms. Release closes it and the tail rings out. The send is latency-compensated (§3.6): it starts from the audio that was heard when the finger landed. Double-tap latches. | v1 |
| CUT pad | Hold mutes the dry signal with a 10 ms ramp while the tails keep ringing. It is not compensated: it is heard about one buffer late. Double-tap latches. | v1 |
| DROP pad | Hold crossfades the dry path over 10 ms into an LR4 high-pass at 250 Hz (the bass kill). The echo keeps its low end. Double-tap latches. | v1 |
| FREEZE pad | Closes the echo input and holds feedback at 1.0 behind the saturator. On release, feedback returns to the slider value over 50 ms. THROW is ignored while FREEZE is engaged. | v1 |
| RUNAWAY pad (taken from the other designs) | Hold pushes feedback to 1.12 so the echo self-oscillates, with the saturator and the limiter in control of loudness. Release glides back over 250 ms. | v1 |
| TAP pad | Uses the existing TapTempo class to set a BPM override for the echo clock. Long-press clears it and returns to the grid. It is the main clock source when there is no beat grid. | v1 |
| Beat-synced echo | Divisions 1/16, 1/8, 3/16, 1/4 (default), 3/8, 1/2. Frames = sourceRate·60/bpm·division·4, in source time, so the echo stays on the grid when the DJ tempo changes. A division change glides over 50 ms (the tape pitch dive). Time is clamped to 2.0 s; a longer value drops to the next shorter division. | v1 |
| Feedback and return | FEEDBACK slider 0-95 % (higher values only through FREEZE or RUNAWAY) and RETURN slider. Values preview live while dragging and are saved on release. Double-tap resets to 55 % / 80 %. | v1 |
| XY filter | X runs from low-pass (left) through a flat detent with true bypass (centre) to high-pass (right), on a log map to 200 Hz (LP) and 6 kHz (HP) at the edges. Y is resonance, Q 0.7-8. It acts on the master sum. On lift it glides to centre over 150 ms unless LOCK is on. A haptic tick marks the detent. | v1 |
| Output protection | In the loop: soft saturator x(27+x²)/(27+9x²), 5 Hz DC blocker, denormal flush, non-finite input treated as silence. On the stage output: PeakLimiter (ceiling 0.966), a limiter LED above 1 dB of gain reduction, and a master peak meter. | v1 |
| Stop with tail (taken from the other designs) | The DUB STOP button fades the dry signal out over 20 ms while the player keeps running. Below -70 dBFS for 200 ms, or after an 8 s cap, the player really pauses and seeks to the presented position at the moment STOP was pressed. Pauses from outside the screen (headset, notification, audio focus) stay immediate. | v1 |
| Tail at end of queue | Holds playToEndOfStream until the tail is silent, capped at 10 s, following the LoopAudioSink pattern. | v1 |
| Tails across jumps | A flush (seek, cue, loop jump, gapless change in the same format) keeps the DSP state. A change of rate or channel count fades the tail out over 10 ms and re-prepares. | v1 |
| Bypass states | Bit-perfect armed, a remote receiver, or more than 2 channels: the stage passes audio through, the controls are disabled, and a banner gives the reason. Crossfade is held at 0 while DUB mode is on. | v1 |
| Settings persistence | Stored: the effect setup (division, feedback, return, LOCK, pad behaviour). Reset every time the mode opens: performance state (latches released, filter centred, freeze off). | v1 |
| Spring-style reverb, CRASH, REVERB THROW | A dispersive allpass chain (24-36 stages per channel) inside a damped, modulated loop. DECAY 0.5-6 s, TONE tilt, RETURN. CRASH injects about 8 ms of shaped transient (the kicked-tank "boing"). Its pad takes the sixth slot. | v2 (first) |
| Quantized pads | With quantize on, THROW, CUT, DROP and CRASH land on the exact frame of the next 1/4, 1/2 or 1 beat inside a window. This also hides latency for the pads that cannot be compensated. | v2 |
| Echo character | A one-knob LP-flat-HP filter in the feedback loop, DRIVE, WOW depth, ping-pong. Needs the new knob component. | v2 |
| Echo CUT | A grid-synced 1/8 or 1/16 square gate on the wet return only, with 2 ms ramps. | v2 |
| Isolator strips and centre strip | Tablet or second page. LOW/MID/HIGH LR4 bands at 250 Hz and 3.5 kHz, with an allpass on LOW so they sum flat. Each band has a fader with a 0 dB detent, KILL, and a pre-fader THROW, so "kill + throw" leaves only that band's echo. An optional mid (centre) strip throws or drops the vocal. | v2 |
| Siren (second source) | Native module on its own low-latency stream, beside the track. A swipe page with 4 trigger pads (hold = sound, last held wins) plus LATCH, PITCH, RATE, DEPTH, SHAPE, its own echo and a level. Keeps sounding while the track is paused. | v2 (see Q2) |
| Shared stage for DJ mode | DJ mode gets a small FX panel (throw, sweep, freeze) that drives the same `DubAudioSink`. | later |
| Scenes, stepped high-pass, routing switches, shimmer, recording | As listed in the faithful design. | later |

## 3. DSP architecture

### 3.1 Place in the chain (Media3)
Stack, outermost first, built in `SonoraRenderersFactory.buildAudioSink` (`core/playback/src/main/kotlin/io/sonora/core/playback/SessionPlayer.kt:120-138`):

`LoopAudioSink` (DJ) → **`DubAudioSink`** (new, session player only) → `EqualizerAudioSink` (ReplayGain, EQ, its limiter, band meter, fade gain) → default sink (float output, output playback params) → bit-perfect-aware AudioTrack provider.

It has to be a forwarding sink, not an AudioProcessor. With float output on, the default sink skips custom processors for high-resolution PCM (`EqualizerAudioSink.kt:24-26`).

Consequences:
- DJ loops feed the echo.
- The band meter and the master EQ see the effects (`EqualizerAudioSink.kt:41-43`).
- Tempo and pitch are applied later by AudioTrack playback params (`SessionPlayer.kt:43-48`), so DUB runs in source time.
- The crossfade tail player is built without this stage.

### 3.2 Control path
- **UI to repository.** The UI calls `DubSetupRepository.preview()` during a drag (in memory only) and `update()` on release (persisted). This mirrors `EqualizerViewModel.kt:44-70` and `DataStoreEqualizerRepository.kt:49-73`.
- **Repository to hub.** `DubController` (core:playback, mirrors LoopController) collects the setup and `DubModeSettings.enabled` on `Dispatchers.Main.immediate`. It publishes an immutable `DubParams` snapshot into a `@Volatile` field of `DubHub`.
- **Pad events.** Pad edges go through a `ConcurrentLinkedQueue<DubEvent>`, the same way LoopHub queues its requests (`LoopHub.kt:116-145`):
  - `PadDown(pad, eventUptimeNanos)`
  - `PadUp(pad, eventUptimeNanos)`
  - `Latch(pad, on)`
  - `ReleaseAll(generation)`
- **Release all.** `ReleaseAll` comes from the siren engine's design: an atomic generation counter that can never be dropped. It fires on background, screen off and focus loss.
- **Per buffer.** The audio thread reads the snapshot once, drains the queue, then renders. Continuous values are smoothed with one-poles (20-50 ms), and every gate is ramped over 2-10 ms.

### 3.3 Signal flow (`DubEngine`, interleaved float, 1-2 channels)
1. Input → `HistoryRing` (stereo, 400 ms), always written while DUB is hosted.
2. Send tap (pre-fader): input · throwGate (5 ms ramp, 0 under FREEZE), plus the `PendingSendRing` contribution (§3.6), feeds `TapeEcho`.
3. `TapeEcho`:
   - A stereo pair of `FractionalDelayLine` (2.5 s ring, 4-point Hermite read, 50 ms time smoother).
   - The loop: one-pole damping at 4.5 kHz → DC blocker → soft saturator → × feedback (user value, 1.0 under FREEZE, 1.12 under RUNAWAY).
   - Output × RETURN.
4. Dry path: input → DROP crossfade (dry against LR4 HP at 250 Hz, 10 ms) → CUT gain (10 ms) → STOP-tail dry gain (20 ms).
5. Sum = dry + echoReturn → `MorphSvf` (TPT state-variable filter, LP ↔ bypass ↔ HP, 10 ms crossfade into exact bypass at the detent, soft clip on the resonant node).
6. Back in core:playback: the sum goes through `PeakLimiter` (4 ms look-ahead, ceiling 0.966, `PeakLimiter.kt:12,137-140`), then to the meters.

**Limiter ceiling (conflict resolved).** The faithful design used 0.89 and the performance-first design used 0.966. **0.966 is kept.** At 0.89, ReplayGain is applied after DUB, so every loudly mastered track would be limited by about 1 dB all the time, even with no pad pressed. At the same ceiling, the equalizer's limiter only acts again when the EQ boosts. The PeakLimiter stays in core:playback, so the shared EQ code is not moved.

### 3.4 Encoding, buffers, sample rate
- **Encoding.** While DUB is hosted, `configure()` rebuilds the inner config as `ENCODING_PCM_FLOAT`, as `EqualizerAudioSink.kt:99-127` does. While DUB is off, input is forwarded with zero work.
- **Idle cost.** "Idle" (no send, tail below -90 dB, filter centred, no CUT/DROP) copies the buffer through unchanged and only writes the history ring.
- **Pending-buffer rule.** Copied from `EqualizerAudioSink.kt:145-159`: once a buffer is processed, the same output is returned until the inner sink has taken all of it, so DSP state never advances twice.
- **Buffer size.** `DjAudioTrackBufferSizeProvider`'s condition is extended to DUB mode, which gives 125 ms (`DjAudioTrackBufferSizeProvider.kt:7-19,40-53`). The size is only read when an AudioTrack is created, so the toggle re-prepares at the current position, as the DJ switch does.
- **Sample rate.** `prepare(rate, channels)` runs in configure, and every coefficient is given in Hz or ms, so 44.1, 48, 96 and 192 kHz sound the same.
- **Memory.** Echo (2.5 s) plus history (0.4 s), stereo float: about 1.1 MB at 48 kHz and about 4.5 MB at 192 kHz. Allocated only in configure.

### 3.5 Tails, pause, bit-perfect
- **Pause.** A real pause stops the AudioTrack (`MediaCodecAudioRenderer.onStopped` → `pause()`), so the tail freezes and resumes intact. This is why the DUB STOP button uses a soft stop.
- **Soft stop.** `DubTransport` performs it. The seek-back target is the presented position (`inner.getCurrentPositionUs()` mapped to media time) at the moment STOP was pressed, never the decoder frontier.
- **Interaction with other locks.** The soft stop must not fight `lockedTo(0)` for crossfade or `loops.holdsPlayback` (`PlaybackService.kt:186-189`). STOP during a held loop releases the loop first, then fades.
- **End of queue.** `hasPendingData()` and `isEnded()` count the tail, following `LoopAudioSink.kt:78-112`.
- **Bypass decision.** `DubHosting.decide(enabled, bitPerfectArmed, remoteRouted, channelCount)` returns `Hosted` or `Bypassed(reason)`, mirroring `LoopHosting.decide`. Crossfade becomes `lockedTo(0, armed || loops.holdsPlayback || dub.hosted)`.

### 3.6 Latency-compensated THROW (corrected)
The judges found two traps in the original design. Both are fixed here.

**Trap 1: mixed time bases.**
- `PresentationClock` records, at each handleBuffer, the pair (source frames handed down, `inner.getCurrentPositionUs()`, current speed).
- The heard source frame for a touch at time t is: frontierFrame − queuedFrames, then − (now − t) · rate · speed. queuedFrames = framesHandedDown − presentedSourceFrames.
- D-6 starts with a spike test on the device to confirm that the position the sink reports is already in media time while a DJ speed is applied.
- Touch time: `eventTimeNanos` on platform API 34+, otherwise `eventTime` in ms. About 5 ms of jitter is accepted.

**Trap 2: erasing older repeats on release.**
- Retro-sent audio is never written straight into the echo loop. A separate `PendingSendRing` holds the audio from the heard press point up to the frontier.
- It is summed into the echo input at write time.
- PadUp fades out (5 ms) only the part of that ring after the release point, so repeats from earlier throws that are already circulating are never touched.

**Fallback.** When delay ≤ queued depth (1/16 or 1/8 at fast tempos, normal-size buffers), the throw sends from the frontier at once. CUT, DROP, the filter and STOP always act at the frontier. Quantize (v2) covers them.

### 3.7 CPU and latency budget
- **CPU (estimate, not measured).** About 150-250 flops per stereo frame, which is about 15-25 MFLOP/s at 96 kHz. Filters use double precision and delay memory uses float.
- **Gate D-11.** Measure playback-thread CPU and underruns on the phone with the DUB screen visible and playing. The criteria: no more than 15 % of one core, and zero underruns over 10 minutes at 125 ms. The spring reverb and the isolator only start after this gate passes.
- **Latency, CUT/DROP/filter.** About 60 ms of wake-up plus 125 ms of buffer plus the device's output latency, roughly 0.2 s in total.
- **Latency, THROW.** The repeats land on the grid. Only the first instant of the send is "late".
- **Latency, siren (v2).** One stream burst-pair plus touch latency (about 20-40 ms expected; not measured).

## 4. UI

Built in a new `feature:dub` module, which feature:nowplaying hosts as it hosts the DJ layouts. It uses `rememberTranslucentBackdrop` and provides `LocalTranslucentBackdrop`. Density (TABLET / COMFORTABLE / COMPACT) is chosen from the available height, through a `DubControlSizes` table modelled on DjControlSizes. The XY pad is the only flexible element. The no-idle-frames rule applies (`DjLayout.kt:63-83`): the LEDs, the crosshair and the meters redraw only on change, and only while playing.

**Phone portrait (360 dp minimum width, 16 dp gutters), top to bottom:**
1. Header, 44 dp: title, BPM source, 4 LEDs, "..." menu with "Exit DUB mode".
2. Deck strip, 56 dp.
3. XY pad: a square that flexes, with LP/HP edge labels, a RES axis and a LOCK pill.
4. Division SegmentedControl.
5. FEEDBACK and RETURN CapsuleSliders side by side.
6. Pad grid, 2 rows × 3, in the thumb zone:
   - THROW / DROP / CUT
   - FREEZE / RUNAWAY / TAP

   In v2 the sixth slot becomes CRASH and TAP moves into the division row.
7. STOP / PLAY row.

Pads are at least 72 dp in COMPACT and 96 dp in COMFORTABLE.

**Phone landscape:**
- The deck strip spans the full width at the top.
- Left column: the 2×3 pad grid.
- Centre: the XY pad, square to the height.
- Right column: divisions (vertical), the two sliders, STOP/PLAY.

**Tablet:**
- v1 uses the landscape arrangement with TABLET sizes.
- v2 swaps the right column for the three isolator strips, the centre strip and the reverb strip. The siren page becomes a fourth strip.

**Components to build:**

| Component | Role |
|---|---|
| `DubPad` | Multi-touch, momentary or latch, haptics, latched wash plus a latch glyph, captures `eventTimeNanos`. |
| `XyFilterPad` | The XY sweep filter control. |
| `BeatLeds` | The four beat-phase dots. |
| `PeakMeter` / `LimiterLed` | Master meter and limiter indicator. |
| `BypassBanner` | Reason banner for bypass states. |
| `DubDeckStrip` | Wraps the existing DJ waveform. |
| v2: `Knob` | Rotary knob for echo character and reverb controls. |
| v2: `VerticalFader` | Log mappings and a 0 dB detent, ported from the siren project's `ui/Fader.kt`. |
| v2: `TriggerPad` | Restyled from the siren project's `ui/panel/TriggerPad.kt`. |

Reused as they are: SegmentedControl, CapsuleSlider, SwitchRow.

**Momentary controls:**
- Each pad has its own `pointerInput` with `awaitEachGesture` and `awaitFirstDown(requireUnconsumed = false)`. That gives true multi-touch: two thumbs plus the XY finger.
- Down engages at once; there is no wait to detect a double-tap.
- A second down within 250 ms of the previous up latches. The next tap unlatches.
- A pointer cancel, a system gesture, or leaving the screen counts as release.
- `systemGestureExclusion` covers the pad grid and the XY pad, so the edge back-swipe does not steal a hold.
- Momentary pads release on `ON_STOP`. Latched pads stay latched.

**Accessibility:**
- Each pad has `Role.Button`.
- `stateDescription` is "held", "latched" or "off".
- Custom actions "Latch" and "Release" let a screen-reader user, who cannot hold, operate every pad.
- The XY pad exposes two adjustable semantic nodes (cutoff, resonance) with step actions plus a "Centre" action.
- A latch is never shown by colour alone (glyph plus wash). Labels sit on a scrim over the translucent surface to keep contrast at 4.5:1.
- The LEDs are static when reduce-motion is on. Haptics follow the system setting.

## 5. Integration

- **Flag port.** `DubModeSettings` in a new pure-JVM `core:dubmodel` module: an `enabled` StateFlow plus `setEnabled`, mirroring `core/djmodel/.../DjMode.kt:11-23`.
- **Flag storage.** Implemented with SharedPreferences: file `sonora_dub_mode`, key `dub_mode_enabled`, default off, excluded from backup. Same as `DjModeSettings.kt:16-42`.
- **Effect setup.** `DubSetupRepository` (DataStore, with `preview` and `update`) holds the stored setup. Performance state lives only in the view model and is reset on open.
- **Entry point.** A checked "DUB mode" row in the Now Playing "..." menu, next to "DJ mode". The layout swaps in 300 ms.
- **Relation to DJ mode (recommended, see Q1).**
  - The two are exclusive as layouts: enabling one turns the other off, after the new one is on, so a switch never
    reads both off (that moment would let a held loop go; see §10.4 "Switch").
  - DUB keeps DJ's audio services hosted: beat-grid analysis, LoopHub, the DJ buffer size and A-B loop off (`PlaybackService.kt:153-156`).
  - The DJ tempo fader stays in DJ mode only for v1.
- **Settings.** `feature/settings/.../dub/DubModeSection.kt` goes as a LazyColumn item after the DJ section (`SettingsScreen.kt:283-285`). It contains:
  - A SwitchRow.
  - A footer stating that effects are off for bit-perfect output and remote playback, and that controls other than THROW respond about 0.2 s after the touch. This honest notice replaces the "say nothing about latency" idea.
  - A "Pads" SegmentedControl: Hold+double-tap latch / Toggle.
  - "Reset effect setup".
- **Service wiring.** `PlaybackService.onCreate` builds `DubController`, passes `dub.hub` to `SessionPlayer.build`, adds DUB to the crossfade lock, and routes STOP through `DubTransport`.
- **Flavors.** Play and full flavors ship the same code; there is no store-service, network or permission dependency. In the full flavor, when the session moves to a remote receiver, the stage is bypassed and the screen shows the banner. The siren (v2) stays off while remote.

## 6. Reuse from the developer's siren project

**v1: port to Kotlin in `core:audiodsp` (about 300 lines).**

| Source in the siren project | Kotlin port |
|---|---|
| `app/src/main/cpp/dsp/Delay.cpp:25-44,85-139` (Hermite read, damping, DC blocker, saturator, denormal flush, non-finite input as silence) | `FractionalDelayLine`, `DcBlocker`, `SoftSaturator` |
| `dsp/Smoother.h` | `Smoother` |
| `OutputStage.cpp:36-45` (soft limiter curve) | Only as a reference for the saturator. |

- The 14 `DelayTest` cases are ported as JVM tests first (RED), including the 96 kHz case.
- UI ideas, not code: the hold/latch logic in `SirenViewModel.kt` and `TriggerPad.kt`, and the log mappings in `Fader.kt`.

**v2: a native module `core:dubsiren` with its own low-latency stream.**
- Move `dsp/` (Oscillator, PolyBlep, Lfo, Modulation, Waveform, TriggerStack, SirenVoice, Delay, Smoother, OutputStage, SirenEngine) and `engine/` (EventQueue, ParameterBlock, SirenRenderer, the stream player).
- The CMakeLists sets C++20 for this target only. Model it on `core/analysis/src/main/cpp/CMakeLists.txt:1-18`.
- The host test task runs the 108 C++ tests, modelled on `core/analysis/build.gradle.kts:49-92`.
- ABI filters must match Sonora's.
- Keep the guarded library load with its no-op fallback (`NativeSirenEngine.kt`, `SirenNativeApi.kt`).
- Rename the namespaces and packages generically (`io.sonora.dub.siren`).
- Never copy the planning document (`odd/tasks/...siren-v1.md:5,67,83`, which names third-party products).
- Record provenance in the commit: same author, relicensed into GPL-3.0. Check the stream library's own licence before merging.
- Close the open T7 items first: triggers 8, 4 and 7 not confirmed by ear, resync after a refused event, and a measured hardware latency.

## 7. Task list

| ID | Title | Est. authored lines | Test-first logic | Depends on |
|---|---|---|---|---|
| D-1 | `core:dubmodel` port + flag storage + DI | 140 | RED: default off; setEnabled persists across instances; file excluded from backup rules. | — |
| D-2 | `DubSetup` model + `DubSetupRepository` (preview/update) | 200 | RED: preview does not persist; update persists; defaults are 1/4, 55 %, 80 %; corrupt store falls back to defaults. | D-1 |
| D-3 | audiodsp: Smoother, DcBlocker, SoftSaturator, FractionalDelayLine (port) | 300 | RED: the 14 ported delay cases (time accuracy, Hermite, feedback 1.2 bounded, NaN in gives silence, denormals flushed, 44.1/96 kHz). | — |
| D-4 | audiodsp: MorphSvf, LR4 high-pass | 220 | RED: bypass is bit-exact at the detent; −3 dB / −6 dB points at fc; LR4 is 24 dB/oct; coefficients do not change with rate. | — |
| D-5 | audiodsp: TapeEcho (freeze, runaway, division glide) | 220 | RED: FREEZE keeps energy constant ±0.5 dB over 10 s; RUNAWAY output ≤ saturator bound; division change glides with no discontinuity > ε. | D-3 |
| D-6 | audiodsp: HistoryRing, PendingSendRing, PresentationClock math (+ device spike) | 280 | RED with a synthetic clock: first repeat lands exactly one delay after the heard press at speed 1.0 and 1.25; PadUp leaves earlier repeats bit-identical; fallback when delay ≤ queued depth. | D-5 |
| D-7 | audiodsp: DubEngine (signal flow, idle passthrough, ramps) | 250 | RED: idle output equals input; CUT/DROP ramps take ≤ 10 ms; output finite under random pad fuzzing; flush keeps state, re-prepare fades. | D-4, D-6 |
| D-8 | playback: DubAudioSink, DubHub, DubHosting, buildAudioSink wiring, float config, buffer provider | 380 | RED with a fake inner sink: pending-buffer rule (no double advance); bypass reasons; float only when hosted; off = untouched encoding. Prior-behaviour tests: EQ-only chain unchanged, DJ-only buffer size unchanged. | D-7, D-1 |
| D-9 | playback: DubController + PlaybackService wiring (crossfade lock, bit-perfect, remote, re-prepare on toggle, ReleaseAll) | 220 | RED: crossfade is 0 while hosted and restored after; armed bit-perfect bypasses; existing lockedTo cases still pass. | D-8 |
| D-10 | playback: DubTransport soft stop + end-of-queue tail | 260 | RED: pause after the tail is below −70 dBFS for 200 ms or at 8 s; seek target is the presented position; external pause stays immediate; isEnded holds until silent or 10 s. | D-9 |
| D-11 | Device gate: CPU and underrun measurement with the screen visible | 80 | Instrumented trace plus underrun counter; pass/fail recorded in the feature document (≤ 15 % of one core, 0 underruns over 10 min). | D-13 |
| D-12 | UI components: DubPad, XyFilterPad, BeatLeds, PeakMeter, LimiterLed, BypassBanner | 420 | UI tests: two simultaneous holds; double-tap latches; cancel releases; semantics state and custom actions; detent haptic fires once. | — |
| D-13 | DubScreen (portrait/landscape/tablet) + DubViewModel (reset on open) | 450 | VM tests: performance state resets on open, setup kept; drag previews, release persists; no frames while paused. | D-2, D-9, D-12 |
| D-14 | Now Playing menu row, layout swap, DJ exclusivity | 150 | RED: enabling DUB disables the DJ layout and the reverse; DJ menu row behaviour unchanged. | D-13 |
| D-15 | Settings DubModeSection + strings + help/docs | 160 | Compose test: switch is bound to the flag; footer text present; reset clears the setup. | D-1, D-2 |
| D-16 | v2: Spring reverb + CRASH + REVERB THROW | 380 | RED: decay time within ±15 %; CRASH with no input gives non-zero bounded output; stability at max decay. | D-11 passed |
| D-17 | v2: Quantized THROW/CUT/DROP/CRASH | 220 | RED: an event inside the window applies at the exact grid frame; outside the window it applies at once. | D-8, D-16 |
| D-18 | v2: Echo character (loop tone knob, drive, wow, ping-pong) + Knob component | 300 | RED: tone at centre is flat; ping-pong alternates channels. | D-5 |
| D-19 | v2: Isolator strips + centre strip (tablet/second page) | 420 | RED: bands sum allpass-flat (±0.1 dB); a killed band is ≥ 90 dB down; kill + throw leaves only the echo of that band. | D-11 passed |
| D-20 | v2: `core:dubsiren` native import + host tests + adapter | 400 glue (C++ moved) | Move the 108 C++ tests first (must be green), then the adapter JVM tests; fallback when the library is missing. | Q2 answer, T7 closed |
| D-21 | v2: Siren page UI | 350 | Ported VM hold/latch tests; last held wins. | D-20, D-12 |

## 8. Risks

1. **THROW clock mapping.** The sink's reported position may not be in media time under a DJ speed. D-6 starts with a device spike. If it fails, ship the throw from the frontier and bring quantize forward.
2. **Underruns at 125 ms.** A busy translucent UI competes with the audio-priority thread. Gate D-11 blocks v2 DSP. Fallbacks: simpler backdrop sampling on the DUB screen, fewer redraws, then native DSP.
3. **Toggle re-prepare.** Switching float on or off and resizing the buffer mid-track causes one seek-to-current. It must not trigger the crossfade or break a held loop.
4. **Soft stop.** Interaction with the loop hold, the crossfade lock, media-session pause commands, and the seek-back position.
5. **Gain staging.** DUB runs before ReplayGain, so loud masters reach the DUB limiter. At ceiling 0.966 this is mostly transparent, but needs listening tests. Runaway feedback must never produce NaN or clip (fuzz test in D-7).
6. **Tail loss on format change.** A track at a different rate or channel count clears the tails. This is documented, not hidden.
7. **Remote and bit-perfect.** These paths silently skip effects. The UI must always explain why.
8. **Accessibility.** Hold-based instruments are hard for screen-reader users. The custom actions are mandatory, not optional.
9. **Siren (v2).** Tuning is unfinished, there is no hardware latency figure, the stream library's licence and ABI match are not verified, the APK grows, and there is a naming-leak risk from its planning document.
10. **Scope creep.** The v1 list is about 3,900 authored lines across 15 tasks. Feature-branch chained PRs are needed.

## 9. Questions for the user (ordered by impact on the plan)

1. **How should DUB mode relate to DJ mode?**
   - (a) **Recommended.** A separate layout, exclusive with the DJ layout, that keeps DJ's audio services running (beat grid, loops, DJ buffer).
   - (b) Fully independent: no loops and no deck strip in DUB.
   - (c) DUB as a tab inside DJ mode.
2. **When does the siren arrive?**
   - (a) **Recommended.** v2, as a native module on its own stream, after its tuning items are closed.
   - (b) In v1, accepting the native module, licence check and APK growth now.
   - (c) Never inside Sonora; it stays a separate app.
3. **How should v1 handle throw latency?**
   - (a) **Recommended.** Latency-compensated THROW in v1, quantized pads in v2.
   - (b) A plain throw from the frontier in v1, quantize in v2.
   - (c) Both in v1.
4. **When does the spring-style reverb with CRASH arrive?**
   - (a) **Recommended.** First v2 item, after the CPU gate.
   - (b) In v1, accepting more DSP risk before measuring.
5. **What buffer size should DUB mode use?**
   - (a) **Recommended.** 125 ms, the DJ size.
   - (b) An opt-in 80 ms setting with an underrun warning.
   - (c) 60 ms by default.
6. **Pad set on the phone?**
   - (a) **Recommended.** Five pads plus TAP (THROW, DROP, CUT, FREEZE, RUNAWAY).
   - (b) Four pads, with RUNAWAY as a long-press on FREEZE.
7. **What happens on pause in DUB mode?**
   - (a) **Recommended.** Only the DUB STOP button rings out the tail; pauses from outside the screen are immediate.
   - (b) Every pause rings out the tail while DUB mode is on.
8. **Tablet in v1?**
   - (a) **Recommended.** A scaled landscape layout; full strips in v2.
   - (b) Isolator strips on tablet already in v1.
## 10. Decisions and the five-way preamp (supersedes the sections above where they conflict)

Answers to §9 (2026-10-28): Q1 (a) separate layout, exclusive with the DJ layout, keeping DJ's audio services; Q2 (a)
siren in v2 as a native module; Q3 (a) latency-compensated THROW in v1, quantized pads in v2; Q4 (a) spring-style
reverb is the first v2 item; Q5 (a) 125 ms buffer; Q6 (a) five pads plus TAP; Q7 (a) only DUB STOP rings out the tail.
Q8 was replaced by the five-way preamp below (the isolator strips of D-19 are dropped).

### 10.1 Five-way preamp (v1, phone and tablet)

The output section works like a sound-system dub preamp: the processed signal (dry plus echo return, after the XY
filter) is split into five ways, each a band-pass made of its own configurable high-pass and low-pass, with its own
fader and kill switch; the five ways sum back to stereo before the output protection.

| Way | Default high-pass | Default low-pass | Range of each corner |
|---|---|---|---|
| 1 Sub | off (20 Hz) | 80 Hz | 20 Hz - 20 kHz, log, HP below LP |
| 2 Bass | 80 Hz | 250 Hz | same |
| 3 Low mid | 250 Hz | 1.2 kHz | same |
| 4 High mid | 1.2 kHz | 5 kHz | same |
| 5 Top | 5 kHz | off (20 kHz) | same |

- Filters: Linkwitz-Riley 24 dB/oct (two cascaded 2nd-order Butterworth sections) per corner; a corner at its range end
  is a true bypass ("off"). With the default, adjacent corners equal, so the five ways sum flat in magnitude (allpass
  phase); the user may overlap or leave gaps, as on the hardware. Coefficients are recomputed only when a corner moves,
  smoothed over 20 ms so a sweep does not click.
- Fader: -inf to +6 dB, 0 dB detent with a haptic tick, smoothed (10 ms). Kill: tap toggles (latched), hold is
  momentary; 5 ms ramps; a killed way is at least 90 dB down.
- The DROP pad momentarily kills ways 1 and 2 (it replaces the fixed 250 Hz high-pass of §2); its ways are a setting.
- Persistence: corner frequencies and fader levels are part of the stored setup; kills are performance state, released
  every time the mode opens.
- Placement: last in the DUB stage, after the echo return and the XY filter, before the saturator/limiter output
  protection, so a kill also removes that band from the echo tail, as a sound system's preamp does.
- CPU: 5 ways x up to 4 biquads x 2 channels = 40 biquads per frame at most; measured in the D-11 gate.

### 10.2 UI changes

- Main effects page: a compact row of five small kill switches (labelled 1-5 or SUB/BASS/LOW MID/HIGH MID/TOP, state
  shown lit), always visible next to the pads, so a way can be killed without leaving the effects.
- Preamp page (phone: a second page reached by a horizontal swipe or a segmented switch; tablet and phone landscape: a
  column next to the effects): five vertical strips, each with HP and LP corner controls (drag to set, double-tap to
  reset to the default, value shown in Hz/kHz), a vertical fader with the 0 dB detent, and a large kill switch.
- Accessibility: each strip announces its way, corners, level and kill state; custom actions for kill and reset.

### 10.3 Task changes

- New: D-4b FiveWayPreamp DSP (core:audiodsp; RED: default ways sum flat within 0.1 dB from 30 Hz to 18 kHz; a killed
  way is >= 90 dB down at its centre; corner moves are click-free; fader detent maps to unity). D-12b PreampStrip and
  MiniKillRow components. D-13 gains the preamp page. D-2 stores the preamp setup.
- Dropped: D-19 (isolator strips). D-4 keeps the XY morph filter; the LR4 sections it builds are reused by D-4b.

### 10.4 As built (wave 2)

Where the code differs from the sections above, or settles what they left open:

- **DROP** is the preamp's DROP (§10.1): it kills the ways in `DubSetup.dropWays` (ways 1 and 2 by default), with the
  kill switches' 5 ms ramps; there is no separate 250 Hz high-pass on the dry path, and the echo loses those bands
  too.
- **Format change.** A new rate or channel count clears the tails at once instead of fading them over 10 ms: by the
  time the sink is configured for the new stream, the old stream's buffers are already below the stage (risk 6).
- **Idle.** The five-way preamp is left out while neutral (unity faders, default corners, nothing killed, no DROP) and
  crossfades in and out over 10 ms from a clean state; the echo keeps running for its ring's length after the last
  send or audible repeat, so a repeat after a silent gap is never lost. An idle block is copied bit for bit.
- **Limiter.** `PeakLimiter` runs on every hosted stream (4 ms look-ahead), idle or not; the touch-to-frame mapping
  takes its look-ahead off.
- **Tail end.** DUB STOP and the end of the queue call a tail over when the output stays below -70 dBFS for 200 ms
  and, while the echo runs, for at least one echo period (a gap between two repeats is not the end); caps 8 s (STOP,
  plus a 9 s wall-clock guard in the controller) and 10 s (end of queue).
- **Toggle.** DUB mode on or off re-prepares a prepared player at the same position (`stop()` + `prepare()`), since
  float output and the 125 ms buffer are only chosen at configure. Turning DUB mode off during a DUB STOP ring out
  ends the ring out first (the dry signal comes back, no pause and no seek back follow).
- **Switch.** Turning one layout on writes its flag before it turns the other off, in both directions, so anything that
  reads "DJ or DUB" (the loop hub, the A-B loop lock, the analysis) never sees both off for a moment and a held loop
  survives a switch between the layouts (risk 3). Both flags found stored on at start (a crash between the two
  writes) resolve to DJ mode on the first read of the DUB flag: DUB mode is turned off and stored off.
- **Grid reads.** The DUB controller reads the playing track's analysis (for the echo's tempo) only while DUB mode is
  on; with DUB mode off no analysis is read on a change of song.
- **DJ services.** The DJ loop hub and its grid stay on while DJ or DUB mode is on, and the A-B loop is off in both.
  The background analysis (`DjAnalysisCoordinator`, core:djanalysis) runs while either mode is on, so a track never
  analysed gets a grid in DUB mode too. The deck's screen state (`DjViewModel`: the waveform and its analysis, the
  grid, the cues and the loop panel) also runs while either mode is on; the beat grid editor and the DJ tempo controls
  stay the DJ layout's alone.
- **Now Playing.** The "…" menu's "DUB Mode" row sits at the end of the playback section; the DUB layout is the track
  header, a compact deck strip (DJ's timeline at 120 dp) and the DUB screen, with the preamp page beside the effects
  in landscape or at 840 dp and wider. Under the strip sit DJ mode's own loop and hot cue controls with its actions
  (36 dp rows): one row (`Cues | Loop` switch, then the eight pads or IN, OUT, EXIT or RELOOP, 1/2X, 2X) where each pad
  keeps 36 dp (landscape, a tablet), else DJ mode's two-row pad panel (with Q, the memory cues and the beat-length
  stepper). The one-row strip has no cue editor: naming and recolouring a cue stay in the two-row panel and the DJ
  layout.
- **Upright phone.** The deck, the status row and the page switch come first, then the flexible element, then the
  effects' fixed rows (`PortraitFit`). The XY pad is a square between 160 dp and 420 dp (never wider than the screen),
  centred in the room left; the preamp page's strips take the room left, at least 240 dp. When even the minimum does not
  fit (a 320 dp wide phone at 704 dp, with DJ's two-row pad panel under the deck strip), the whole DUB screen scrolls:
  scrolling the deck away shows the effects page whole. The pads, the XY pad, the kill switches and the preamp
  controls take their pointer on the down or at their own touch slop, so the scroll never steals a held control.
