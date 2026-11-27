package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Process-wide memory cache: survives tab switches / recomposition, no re-download. */
private object AvatarCache {
    val map = ConcurrentHashMap<String, android.graphics.Bitmap>()
}

/** Avatar with letter fallback. Uses cached bitmap first, downloads only once per URL. */
@Composable
fun AvatarImage(url: String?, name: String, modifier: Modifier = Modifier) {
    val bitmapState = remember { mutableStateOf<android.graphics.Bitmap?>(url?.let { AvatarCache.map[it] }) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val cached = AvatarCache.map[url]
        if (cached != null) {
            bitmapState.value = cached
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bmp != null) {
            AvatarCache.map[url] = bmp
            bitmapState.value = bmp
        }
    }

    Box(
        modifier = modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmapState.value
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = t("头像"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape)
            )
        } else {
            Text(
                name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
