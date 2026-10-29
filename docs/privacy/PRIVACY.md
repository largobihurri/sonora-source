# Sonora privacy policy

Effective date: 29 October 2026

Sonora is a music player for the audio files on your device. It has no accounts, no ads, no analytics and no
tracking. The developer runs no server for Sonora and receives no data from the app.

This policy covers two builds:

- **The Google Play build** (`io.sonora.player`), the one sold on Google Play.
- **The full build** (`io.sonora.player.full`, shown as "Sonora Full"), built from the same source for personal use.
  It is not distributed on Google Play. It adds Google Cast and the Wear OS watch app. Sections marked
  **Full build only** apply to it alone.

## What Sonora keeps on your device

Sonora keeps its data in its private app storage on your device:

- the folders you chose with the system folder picker, and an index of the songs in them (tags and cover art);
- your playlists, favourites, pinned and hidden items;
- play counts and your listening history (which song, when, for how long), used for the app's statistics;
- lyrics you downloaded, and DJ analyses (beat grids, keys, cues);
- your settings and the playback queue.

Sonora reads music only from folders you pick, and changes your files only when you ask it to (for example, editing
tags or converting files). Clearing the app's data or uninstalling Sonora deletes everything it kept.

## What leaves your device, and when

**Lyrics search (LRCLIB), Sonora Full only.** The Google Play version has no lyrics search and no internet access
at all. In Sonora Full, only when you tap "Search lyrics online" for a song, Sonora sends that song's title, artist,
album and length to [LRCLIB](https://lrclib.net), a free third-party lyrics service, over an encrypted (HTTPS)
connection. Like any web request, it also carries your IP address and the app's name and version. The answer is kept
on your device, so the song is not searched again unless you ask. Sonora never searches by itself. LRCLIB handles the
request under its own terms.

**Other apps on your device.** No network is involved:

- Apps from Sonora's developer that are signed with the same key can read your library, playback state, listening
  history and cover art, and control playback, through Android's on-device app communication.
- **External control** is off until you turn it on in Sonora's settings. While it is on, the Android debug shell
  (`adb`, from a computer you connected with USB debugging) and the apps you grant Sonora's "Control Sonora"
  permission can do the same. Turning it off denies them again.
- When you share songs or a compressed archive, Sonora hands them to the app you pick in the system share sheet.

**Backups and exports you make.** Sonora's backup file, compressed archives, converted files and exports to a USB stick
go only to the place you choose. What happens to them there is up to that place.

**Android device backup.** If you turned on backup in Android's settings, Android includes Sonora's settings and
library data (except the DJ analysis database) in your device backup and in transfers to a new device. Your backup
provider handles it, not the developer. You can turn it off in Android's settings.

**Android's media controls.** Sonora shows what is playing (title, artist, cover art) to Android's media controls, so
the notification, the lock screen, Bluetooth devices, Android Auto and the voice assistant can show and control it.

**Full build only: Google Cast.** When you cast, Sonora sends the song's title, artist, album and cover art to your
Cast device, which then streams the audio from a small server on your phone. That server listens on your Wi-Fi network
only, answers only requests that carry a code made for that cast session, and stops when the cast ends. Cast is
provided by Google through Google Play services, under Google's privacy policy.

**Full build only: Wear OS watch.** When you send music to your watch or control Sonora from it, Sonora uses Google's
Wear OS Data Layer (Google Play services) to send copies of the songs and the remote-control commands between your
phone and your watch.

Nothing else leaves your device.

## What Sonora does not do

- No accounts and no sign-in.
- No ads, no analytics, no crash-reporting service, no tracking across apps or websites.
- No access to your location, contacts, camera or microphone.
- No sale or sharing of your data: the developer never receives it.

## Buying Sonora on Google Play

Google processes your purchase under its own privacy policy. The developer sees only the order information Google
Play gives to sellers, and uses it only to handle that order.

If you allowed Android to share usage and diagnostics data, Google may show the developer anonymous crash and
performance reports for Sonora in the Google Play Console.

## Permissions

| Permission | Why |
|---|---|
| Internet | Full build only: the lyrics search you ask for, and Google Cast. The Google Play version does not have it. |
| Network state | Used by the audio player and background-work libraries. Full build: also the Wi-Fi address for Cast. |
| Notifications | Playback controls, and progress of conversions, duplicate scans and exports. |
| Foreground services (media playback, media processing, data sync) | Keep playback, file conversion and exports (to a USB stick, and in the full build to the watch) running when you leave the app. |
| Promoted notifications | Show conversion progress as a live update on Android 16. |
| Wake lock | Keep playing with the screen off. |
| Modify audio settings | Bit-perfect output to a USB audio device. |
| Run at startup | Lets Android resume scheduled background work (such as a conversion) after a restart. |
| USB devices (asked when you plug one in) | Read and write the USB stick you choose for exports. |

Sonora also defines its own "Control Sonora" permission, which you grant to other apps (see above).

## Children

Sonora is not directed at children. It collects no personal data from anyone, children included.

## Your choices

- In Sonora Full, remove a song's downloaded lyrics ("Remove downloaded lyrics"); it can then be searched again.
- Keep External control off, or turn it off at any time.
- Turn off Android device backup in Android's settings.
- Clear Sonora's data or uninstall it to delete everything it kept.

Because the developer holds no data about you, there is nothing to request from the developer. For a lyrics request made
from Sonora Full, contact LRCLIB.

## Source code

Sonora is free software under the GNU GPL version 3. Its source code is at
<https://github.com/largobihurri/sonora-source>, so anyone can check this policy against the code.

## Changes

Changes to this policy are published in this file, with a new effective date.

## Contact

bihurridev@gmail.com
