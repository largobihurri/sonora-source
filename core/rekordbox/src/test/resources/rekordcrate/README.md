# Reference rekordbox files

Copied unchanged from the rekordcrate project (https://github.com/Holzhaus/rekordcrate, commit 14d54ed,
`data/complete_export/demo_tracks/PIONEER/`), a USB stick that rekordbox 6.6.1 exported with its two demo tracks:

- `demo_tracks/P016/0000875E/ANLZ0000.{DAT,EXT}` and `demo_tracks/P053/0001D21F/ANLZ0000.{DAT,EXT}` (from
  `USBANLZ/`): rekordbox's own analysis of the demo tracks, the reference that `AnlzRealFileParityTest` rebuilds byte
  for byte.
- `demo_tracks/export.pdb` (from `rekordbox/`): the device library holding those tracks, the reference that
  `PdbDemoParityTest` rebuilds row by row.
- `demo_tracks/DEVSETTING.DAT`: the player settings rekordbox writes by default, the reference for
  `DevSettingWriterTest`.

rekordcrate is licensed under the Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/), without the
"Incompatible With Secondary Licenses" notice, so these files may be distributed alongside this GPL-3.0-or-later
project; they themselves stay under the MPL 2.0. The `.2EX` files and the rest of the export are not needed and were
not copied. The empty export used as the writer's template lives in the main resources, next to its own README.
