package com.superstudent.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SsTone { NEUTRAL, SUCCESS, ACCENT, DANGER, INFO }

private fun SsTone.container(): Color = when (this) {
    SsTone.NEUTRAL -> Color(0xFFEAF1EC)
    SsTone.SUCCESS -> SsColors.PrimaryContainer
    SsTone.ACCENT -> Color(0xFFFDEBD6)
    SsTone.DANGER -> Color(0xFFF7DFDD)
    SsTone.INFO -> Color(0xFFDEEAF6)
}

private fun SsTone.content(): Color = when (this) {
    SsTone.NEUTRAL -> Color(0xFF4A6154)
    SsTone.SUCCESS -> SsColors.Primary
    SsTone.ACCENT -> Color(0xFF9A5B12)
    SsTone.DANGER -> SsColors.Error
    SsTone.INFO -> Color(0xFF1D4E79)
}

/** Pill button, ≥48dp touch target, primary or outlined variant. */
@Composable
fun SsPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    leadingIcon: ImageVector? = null,
    contentDescription: String? = text,
) {
    val bg = when {
        !enabled -> Color(0xFFDCE5DE)
        outlined -> Color.Transparent
        else -> SsColors.Primary
    }
    val fg = when {
        !enabled -> Color(0xFF8A9A8F)
        outlined -> SsColors.Primary
        else -> Color.White
    }
    Row(
        modifier = modifier
            .heightIn(min = SsSpacing.MinTouch)
            .clip(SsShapes.Pill)
            .background(bg)
            .then(
                if (outlined && enabled) {
                    Modifier.border(1.5.dp, SsColors.Primary, SsShapes.Pill)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .padding(horizontal = SsSpacing.Xl, vertical = SsSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Box(Modifier.size(SsSpacing.Sm))
        }
        Text(
            text = text,
            style = SsType.Label.copy(color = fg, fontSize = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Circular icon button, 48dp touch target. */
@Composable
fun SsIconCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tint: Color = SsColors.Primary,
    container: Color = SsColors.PrimaryContainer,
    enabled: Boolean = true,
) {
    val base = modifier
        .size(SsSpacing.MinTouch)
        .clip(CircleShape)
        .background(container)
    Box(
        modifier = if (onClick != null) {
            base.clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
        } else {
            base
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Status pill used for task/package state. */
@Composable
fun SsStatusChip(
    text: String,
    tone: SsTone = SsTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(SsShapes.Pill)
            .background(tone.container())
            .padding(horizontal = SsSpacing.Md, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = SsType.Label.copy(color = tone.content(), fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Determinate (0f..1f) or indeterminate progress ring. */
@Composable
fun SsProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    color: Color = SsColors.Primary,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (progress == null) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = color,
                strokeWidth = 5.dp,
                trackColor = SsColors.PrimaryContainer,
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                strokeWidth = 5.dp,
                trackColor = SsColors.PrimaryContainer,
            )
        }
    }
}

/** Result tab bar. `enabledTabs` stays so a tab whose artifact is missing can be greyed out. */
@Composable
fun SsResultTab(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabledTabs: Set<Int> = tabs.indices.toSet(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SsShapes.Pill)
            .background(Color(0xFFEAF1EC))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val enabled = index in enabledTabs
            val bg = if (selected) SsColors.Surface else Color.Transparent
            val fg = when {
                selected -> SsColors.Primary
                enabled -> Color(0xFF4A6154)
                else -> Color(0xFFA7B4AA)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(SsShapes.Pill)
                    .background(bg)
                    .clickable(
                        enabled = enabled,
                        role = Role.Tab,
                        onClickLabel = label,
                    ) { onSelect(index) }
                    .padding(vertical = SsSpacing.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = SsType.Label.copy(color = fg),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
