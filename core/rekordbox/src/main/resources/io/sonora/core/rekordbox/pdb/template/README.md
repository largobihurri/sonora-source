# Empty rekordbox export template

`export.pdb` and `exportExt.pdb` are copied unchanged from the rekordcrate project
(https://github.com/Holzhaus/rekordcrate, commit 14d54ed, `data/complete_export/empty/PIONEER/rekordbox/`). They are
what rekordbox 6.6.1 writes for a USB stick without any tracks.

`PdbWriter` copies the data pages of the tables it does not generate (colours, columns, menu and the unknown table 18)
from `export.pdb` unchanged, and `PdbTemplates.exportExtPdb()` hands out `exportExt.pdb` as is: the players only read
it for My Tag data, which the export does not write.

rekordcrate is licensed under the Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/), without the
"Incompatible With Secondary Licenses" notice, so these files may be distributed alongside this GPL-3.0-or-later
project; they themselves stay under the MPL 2.0, and their source is the rekordcrate repository named above.
