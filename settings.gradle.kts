pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Kora, the shared communication library of every Bihurri app: published releases only, exact versions, from
        // the public mirror (a static Maven repository on GitHub Pages, no login), so the public source builds as is.
        // exclusiveContent: the group is looked up here only, never on the repositories above.
        exclusiveContent {
            forRepository { maven("https://largobihurri.github.io/kora-public/maven") }
            filter { includeGroup("eus.bihurri.kora") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Sonora"

include(":app")
include(":wear")
include(":core:domain")
include(":core:api")
include(":core:kora")
include(":core:watchsync")
include(":core:watchlink")
include(":core:watchphone")
include(":core:watchport")
include(":core:designsystem")
include(":core:data")
include(":core:playback")
include(":core:cast")
include(":core:transcoder")
include(":core:mp3")
include(":core:fat")
include(":core:usbstorage")
include(":core:decoder")
include(":core:analysis")
include(":core:analysiscache")
include(":core:rekordbox")
include(":core:djmodel")
include(":core:dubmodel")
include(":core:audiodsp")
include(":core:djengine")
include(":core:djstore")
include(":core:beatmodel")
include(":core:djanalysis")
include(":core:djloop")
include(":tools:calibration")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:equalizer")
include(":feature:dj")
include(":feature:dub")
include(":feature:converter")
include(":feature:settings")
include(":feature:cdjexport")
