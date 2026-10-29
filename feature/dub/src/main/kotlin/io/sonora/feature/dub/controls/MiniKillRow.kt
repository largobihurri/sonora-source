package io.sonora.feature.dub.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.feature.dub.R

/**
 * The five preamp kill switches in small, for the effects page (design §10.2): numbered 1 to 5, lit while their way
 * is in [killed], always next to the pads so a way can be killed without leaving the effects. They work like the
 * strips' kill switches (tap toggles, hold is momentary) and report through [onKilledChange].
 */
@Composable
fun MiniKillRow(
    killed: Set<DubWay>,
    onKilledChange: (way: DubWay, killed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = MiniKillHeight,
) {
    val title = stringResource(R.string.dub_kill_row)
    Row(
        modifier.height(height).semantics {
            isTraversalGroup = true
            contentDescription = title
        },
        horizontalArrangement = Arrangement.spacedBy(MiniKillGap),
    ) {
        for (way in DubWay.entries) {
            KillSwitch(
                killed = way in killed,
                onKilledChange = { onKilledChange(way, it) },
                label = way.number.toString(),
                description = stringResource(R.string.dub_way_title, way.number, stringResource(way.label)),
                enabled = enabled,
                corner = 10.dp,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).height(height),
            )
        }
    }
}

private val MiniKillHeight = 40.dp
private val MiniKillGap = 6.dp
