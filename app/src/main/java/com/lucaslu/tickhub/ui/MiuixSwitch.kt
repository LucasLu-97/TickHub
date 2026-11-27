package com.lucaslu.tickhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.util.Haptics

/**
 * Slim Miuix-style switch: narrow rounded track + perfectly circular thumb.
 * Width/height are fixed so the thumb never deforms into an ellipse.
 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String = ""
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val trackW = 44.dp
    val trackH = 22.dp
    val thumbSize = 18.dp
    val padding = (trackH - thumbSize) / 2

    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    )
    val thumbColor = Color.White // MIUI: white circular thumb in both states
    val offsetX by animateDpAsState(
        targetValue = if (checked) trackW - thumbSize - padding else padding,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 420f)
    )

    Box(
        modifier = modifier
            .width(trackW)
            .height(trackH)
            .clip(CircleShape)
            .background(trackColor)
            // TalkBack / keyboard accessible (the previous pointerInput-only
            // switch was completely invisible to accessibility services).
            .toggleable(value = checked, role = Role.Switch) {
                onCheckedChange(it)
                Haptics.tick(context)
            }
            .semantics {
                if (description.isNotBlank()) contentDescription = description
                stateDescription = if (checked) "开" else "关"
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(thumbSize)
                .offset(x = offsetX)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
