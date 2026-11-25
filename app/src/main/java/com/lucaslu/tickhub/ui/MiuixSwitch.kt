package com.lucaslu.tickhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
    modifier: Modifier = Modifier
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
        if (checked) trackW - thumbSize - padding else padding
    )

    Box(
        modifier = modifier
            .width(trackW)
            .height(trackH)
            .clip(CircleShape)
            .background(trackColor)
            .pointerInput(checked) {
                detectTapGestures(onTap = {
                    onCheckedChange(!checked)
                    Haptics.tick(context)
                })
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
