package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Official Material outlined icons — clean line art, no hand-drawn paths. */
@Composable
fun TabIcon(index: Int, color: Color, modifier: Modifier = Modifier) {
    val imageVector = when (index) {
        0 -> Icons.Outlined.Folder
        1 -> Icons.Outlined.Alarm
        2 -> Icons.Outlined.History
        3 -> Icons.Outlined.Settings
        else -> Icons.Outlined.Folder
    }
    Icon(imageVector = imageVector, contentDescription = null, tint = color, modifier = modifier)
}

/** Delete icon for swipe-to-delete, using the official outlined trash can. */
@Composable
fun DeleteLineIcon(tint: Color, modifier: Modifier = Modifier) {
    Icon(imageVector = Icons.Outlined.Delete, contentDescription = t("删除"), tint = tint, modifier = modifier)
}
