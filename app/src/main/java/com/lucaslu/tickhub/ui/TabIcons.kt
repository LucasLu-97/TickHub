package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.lucaslu.tickhub.R

/**
 * Official Material outlined icons bundled as local VectorDrawables — zero
 * third-party icon dependency, minimal APK footprint, no R8 needed.
 */
@Composable
fun TabIcon(index: Int, color: Color, modifier: Modifier = Modifier) {
    val res = when (index) {
        0 -> R.drawable.ic_folder_outlined
        1 -> R.drawable.ic_alarm_outlined
        2 -> R.drawable.ic_history_outlined
        3 -> R.drawable.ic_settings_outlined
        else -> R.drawable.ic_folder_outlined
    }
    Icon(painter = painterResource(res), contentDescription = null, tint = color, modifier = modifier)
}

/** Delete icon for swipe-to-delete. */
@Composable
fun DeleteLineIcon(tint: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_delete_outlined),
        contentDescription = t("删除"),
        tint = tint,
        modifier = modifier
    )
}
