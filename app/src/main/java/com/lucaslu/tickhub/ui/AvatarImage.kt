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

/** Process-wide bounded LRU cache (50 avatars ≈ a few MB) with sampling.
 *  Avoids the unbounded growth of a plain ConcurrentHashMap on long sessions. */
private object AvatarCache {
    private const val TARGET_PX = 128 // 36dp display @3x — plenty of resolution
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtMost(8 * 1024) // ≤8MB or 1/16 heap
    private val lru = object : android.util.LruCache<String, android.graphics.Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            value.byteCount / 1024 // size in KB, matches maxKb

    fun get(url: String): android.graphics.Bitmap? = synchronized(lru) { lru.get(url) }
    fun put(url: String, bmp: android.graphics.Bitmap) = synchronized(lru) { lru.put(url, bmp) }

    /** Decode with inSampleSize so a 460px GitHub avatar never fills memory. */
    fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val boundsStream = java.io.ByteArrayInputStream(bytes)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(boundsStream, null, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample / 2 >= TARGET_PX) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeStream(java.io.ByteArrayInputStream(bytes), null, opts)
    }
}

/** Avatar with letter fallback. Uses cached bitmap first, downloads only once per URL. */
@Composable
fun AvatarImage(url: String?, name: String, modifier: Modifier = Modifier) {
    val bitmapState = remember { mutableStateOf<android.graphics.Bitmap?>(url?.let { AvatarCache.get(it) }) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val cached = AvatarCache.get(url)
        if (cached != null) {
            bitmapState.value = cached
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { it.readBytes() }.let { AvatarCache.decodeSampled(it) }
            }.getOrNull()
        }
        if (bmp != null) {
            AvatarCache.put(url, bmp)
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
