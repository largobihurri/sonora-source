plugins {
    alias(libs.plugins.sonora.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.domain)
}

tasks.test {
    // AnlzOracleFilesTest writes sample ANLZ files here, to check them by hand with rekordcrate and pyrekordbox.
    val anlzOut = layout.buildDirectory.dir("anlz-out")
    outputs.dir(anlzOut)
    systemProperty("sonora.anlzOutDir", anlzOut.get().asFile.path)
    // PdbOracleFilesTest writes sample export.pdb, exportExt.pdb and DEVSETTING.DAT files here, to check with rekordcrate.
    val pdbOut = layout.buildDirectory.dir("pdb-out")
    outputs.dir(pdbOut)
    systemProperty("sonora.pdbOutDir", pdbOut.get().asFile.path)
}
