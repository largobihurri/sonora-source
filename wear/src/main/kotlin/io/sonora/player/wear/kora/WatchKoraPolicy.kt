package io.sonora.player.wear.kora

import eus.bihurri.kora.core.CallerPolicy
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess

/**
 * Who may use the watch's node: the app itself and peers signed with the common Bihurri key, for every scope. Everyone else
 * (the adb shell, other apps, remote agents) is refused: the watch has no settings screen, so there is no master switch and
 * no grant to give. Spelled out here, not left to Kora's default, so a change of Kora's default cannot open the node.
 */
internal fun watchCallerPolicy(): CallerPolicy = DefaultCallerPolicy(ExternalAccess.Disabled)
