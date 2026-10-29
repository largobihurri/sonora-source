# feature:cdjexport

The "Export" tab: prepares a USB stick for Pioneer players (FAT32 on MBR, see `core:fat` and `core:usbstorage`) and
exports tracks with a Rekordbox device library to it.

## Export to Rekordbox

The user picks files or a folder and the stick (the top level of the mounted USB volume) with the system's document
picker. Files are checked against the target `DeviceProfile` (XDJ-700: AIFF, 16-bit, up to 48 kHz, mono or stereo);
anything else the decoder reads (AIFF 24/32-bit or float, AIFF-C, over 48 kHz, WAV, FLAC, MP3, AAC/M4A, Ogg/Opus) is
listed as "will be converted on export" (`ExportConversion`). Only files that cannot be read, or whose audio cannot be
decoded (compressed AIFF-C such as IMA ADPCM, an unknown format), are listed apart with the reason.

On export, each such file is first converted by the app's converter engine (`AudioConverter`, the Converter screen's,
through `TranscoderExportConverter`) with the converter profile of the same player (`ConversionProfiles.XDJ_700`):
plain AIFF 16-bit, rates up to 48 kHz kept (faster ones become 44.1 kHz), up to two channels kept, tags and artwork
copied. The result is a temporary file in the app's cache (`export-conversions/`), checked against the profile, that
stands for the original for the rest of the track (hash, analysis, copy) and is deleted when the track is done, fails or
is cancelled; leftovers of an export killed with the app are deleted when the next one starts. Its progress is the
`CONVERTING` phase of the track (the first 40 % of it). The space check counts a converted track at its 16-bit size
plus room for tags and artwork. The dither is seeded by the source, so exporting the same file again converts it to the
same bytes and it is found on the stick as a duplicate.

Formatting keeps the stick's previous FAT volume id when it had one (`core:fat` `ExistingVolumeId`), so Android sees the
same volume and does not create its standard folders again; each export also removes those folders (Alarms, DCIM,
Music, ... exactly Android's names) when they hold no file at any depth (`AndroidStandardFolders`).

`StickExporter` (pure Kotlin, behind the `StickFileSystem` and `ExportSourceReader` ports) writes:

- `Contents/<Artist|UnknownArtist>/<Album|UnknownAlbum>/<file>`: FAT-safe names, compared without regard to case;
  a name that is taken gets " (2)"; files the export does not own are never overwritten;
- `PIONEER/USBANLZ/Pxxx/xxxxxxxx/ANLZnnnn.DAT/.EXT` per track, in the folder rekordbox computes from the track's
  `file_path` (the players look only there, whatever `analyze_path` says); tracks sharing a folder get ANLZ0000,
  ANLZ0001, ... Analysis an earlier Sonora left elsewhere is moved there, and analysis folders no track uses are
  removed once the new library is in place;
- then, only if something changed, the library in two stages: first `PIONEER/rekordbox/export.pdb`, `exportExt.pdb`,
  `PIONEER/DEVSETTING.DAT` (alphanumeric keys) and `PIONEER/sonora/library.json` are each written as
  `<name>.sonora-new`; then they are put in place, **the manifest first** (delete + rename: SAF has no atomic replace,
  so there is a short moment without the old file).

`library.json` (versioned, see `ManifestJson`) is Sonora's record of the library: every export reads it, adds its
tracks (a track with the same size and SHA-256 is not copied again but joins the new playlist) and regenerates
`export.pdb` from it. Because the manifest is put in place before `export.pdb`, a swap that stops half-way heals
itself: the next export finds `.sonora-new` files (or a missing library file), rebuilds every library file from the
manifest even if it adds nothing, and a manifest lost between its delete and rename is read back from its complete
`.sonora-new` copy.

A stick with an `export.pdb` but no manifest (made by rekordbox) or with a manifest Sonora cannot read is replaced
only after the user confirms. Every damaged manifest (bad JSON, nesting deeper than 64, a mistyped or out-of-range
member, a newer schema) is a `ManifestFormatException`: the stick shows as "library record damaged" (or "from a newer
Sonora") and the confirmation says plainly that the new library will list only the tracks exported now. The library
is never dropped silently, and the old audio files stay on the stick.

A track that cannot be read or analysed is reported and skipped; any other failure (the stick's or unexpected) stops
the export with a report. Cancelling, or a failure before the library files are put in place, removes the files this
export wrote and leaves the previous library as it was. From the first replace on, the copied files stay (the new
manifest may list them) and the next export completes the library. Once the library files are being written the
export is no longer cancellable.

Access to picked files is kept with persisted read grants only while needed: the screen gives up a grant as soon as no
listed track (or pick still being checked) uses it and when it goes away; a started export takes its tracks' grants
over and `ExportCoordinator` gives them up when it ends. `OwnedReadGrants` records the grants the export took (never
one the app already held, such as a library folder), gives up on the next app start whatever a killed run left, and
holds at most 64 at once, far below Android's per-app cap (128, or 512 from Android 11). The stick keeps one
read/write grant per volume.

The export runs in `ExportCoordinator` (app-wide) with `ExportService`, a `dataSync` foreground service, so it
survives rotation and leaving the app. Logs use the tag `SonoraExport`.

## Diagnosing USB failures on a phone

Every error of the "Prepare USB stick" flow offers **Copy technical details**: a plain-text report (app and Android
version, phone model, USB VID:PID, SCSI INQUIRY data, capacity, failure type, LBA and the exception chain) to paste
into a bug report.

The full trace goes to logcat under two tags:

- `SonoraUsbStorage`: every Bulk-Only transport failure with its phase (CBW, data-in, data-out, CSW, clear-halt,
  reset), the `bulkTransfer` return value, bytes requested and moved, opcode and attempt.
- `SonoraPrepareUsb`: the flow itself (open, format, retry, close).

ColorOS and realme phones silence app logs by default. Enable both tags before reproducing:

```sh
adb shell setprop log.tag.SonoraUsbStorage VERBOSE
adb shell setprop log.tag.SonoraPrepareUsb VERBOSE
adb logcat -s SonoraUsbStorage SonoraPrepareUsb
```

The properties last until the phone restarts.
