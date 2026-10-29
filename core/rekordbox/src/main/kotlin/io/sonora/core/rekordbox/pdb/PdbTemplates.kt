package io.sonora.core.rekordbox.pdb

/**
 * The empty export rekordbox 6.6.1 writes to a stick without tracks (from rekordcrate, see the README next to the
 * resources): [exportPdb] supplies the tables the writer copies unchanged, [exportExtPdb] is written to the stick as is.
 */
object PdbTemplates {
    private const val ROOT = "/io/sonora/core/rekordbox/pdb/template"

    private val exportPdb: ByteArray by lazy { resource("export.pdb") }
    private val exportExtPdb: ByteArray by lazy { resource("exportExt.pdb") }

    /** rekordbox's `export.pdb` without tracks (41 pages). */
    fun exportPdb(): ByteArray = exportPdb.copyOf()

    /** rekordbox's `exportExt.pdb` without tracks, for `PIONEER/rekordbox/exportExt.pdb`. */
    fun exportExtPdb(): ByteArray = exportExtPdb.copyOf()

    private fun resource(name: String): ByteArray =
        checkNotNull(PdbTemplates::class.java.getResourceAsStream("$ROOT/$name")) { "missing template $name" }
            .use { it.readBytes() }
}
