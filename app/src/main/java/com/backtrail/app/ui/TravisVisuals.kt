package com.backtrail.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backtrail.app.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Explicit route-plot fallback. The integration layer supplies the offline raster map slot. */
@Composable
internal fun TravisMap(state: TravisUiState, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    val grid = palette.outlineVariant
    var zoom by remember(state.outing.id) { mutableFloatStateOf(1f) }
    var pan by remember(state.outing.id) { mutableStateOf(Offset.Zero) }
    val points = state.outing.points
    val all = points + state.outing.memories.mapNotNull { it.point } + listOfNotNull(state.fix, state.target)
    Box(modifier.background(palette.surfaceContainer)) {
        Canvas(Modifier.fillMaxSize().pointerInput(state.outing.id) {
            detectTransformGestures { _, delta, scale, _ -> zoom = (zoom * scale).coerceIn(.6f, 6f); pan += delta }
        }) {
            val gridStep = 42.dp.toPx()
            for (i in 0..(size.width / gridStep).toInt()) drawLine(grid.copy(alpha = .55f), Offset(i * gridStep, 0f), Offset(i * gridStep, size.height), 1f)
            for (i in 0..(size.height / gridStep).toInt()) drawLine(grid.copy(alpha = .55f), Offset(0f, i * gridStep), Offset(size.width, i * gridStep), 1f)
            if (all.isNotEmpty()) {
                val minLat = all.minOf { it.latitude }; val maxLat = all.maxOf { it.latitude }
                val minLon = all.minOf { it.longitude }; val maxLon = all.maxOf { it.longitude }
                val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
                val rangeX = max((maxLon - minLon) * lonScale, .0008)
                val rangeY = max(maxLat - minLat, .0008)
                val plotWidth = size.width * .72f
                val plotHeight = size.height * .42f
                val scale = min(plotWidth / rangeX, plotHeight / rangeY)
                fun position(point: TrailPoint): Offset = Offset(
                    ((point.longitude - (minLon + maxLon) / 2) * lonScale * scale).toFloat() + size.width / 2,
                    size.height * .37f - ((point.latitude - (minLat + maxLat) / 2) * scale).toFloat(),
                )
                withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset(size.width / 2, size.height * .37f)) }) {
                    points.zipWithNext().forEach { (a, b) ->
                        if (a.segment == b.segment) {
                            drawLine(palette.surface, position(a), position(b), 9.dp.toPx() / zoom, StrokeCap.Round)
                            drawLine(TravisOrange, position(a), position(b), 4.5.dp.toPx() / zoom, StrokeCap.Round)
                        }
                    }
                    points.firstOrNull()?.let { start ->
                        drawCircle(palette.primary, 7.dp.toPx() / zoom, position(start))
                        drawCircle(palette.surface, 3.dp.toPx() / zoom, position(start))
                    }
                    state.outing.memories.mapNotNull { it.point }.forEach { point ->
                        drawCircle(palette.surface, 12.dp.toPx() / zoom, position(point))
                        drawCircle(TravisGreen, 9.dp.toPx() / zoom, position(point))
                        drawCircle(Color.White, 2.5.dp.toPx() / zoom, position(point))
                    }
                    state.target?.let { target -> drawCircle(TravisOrange, 18.dp.toPx() / zoom, position(target), style = Stroke(2.dp.toPx() / zoom)) }
                    state.fix?.let { fix ->
                        val center = position(fix)
                        val opacity = if (state.fixQuality == FixQuality.STALE) .4f else 1f
                        drawCircle(TravisBlue.copy(alpha = .12f * opacity), 26.dp.toPx() / zoom, center)
                        drawCircle(Color.White.copy(alpha = opacity), 10.dp.toPx() / zoom, center)
                        drawCircle(TravisBlue.copy(alpha = opacity), 7.dp.toPx() / zoom, center)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.Center).offset(y = (-65).dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (all.isEmpty()) {
                TravisGlyph("trail", Modifier.size(72.dp), palette.primary.copy(alpha = .25f))
                Spacer(Modifier.height(16.dp))
                Text("Your trail begins here", style = MaterialTheme.typography.titleMedium, color = palette.onSurfaceVariant)
                Text("Start an outing to record measured positions.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
        }
        Surface(Modifier.align(Alignment.CenterStart).offset(y = 38.dp).padding(14.dp), shape = RoundedCornerShape(8.dp), color = palette.surface.copy(alpha = .94f)) {
            Text("ROUTE PLOT · NO BASEMAP", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 8.sp, letterSpacing = 1.sp, color = palette.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TravisCameraGuidance(state: TravisUiState, camera: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val relative = if (state.bearing != null && state.heading != null) ((state.bearing - state.heading + 540) % 360) - 180 else null
    val usable = state.fixQuality == FixQuality.USABLE && state.headingReliable && relative != null
    Box(modifier.background(Color(0xFF1C3028))) {
        if (state.replay) {
            TravisPhoto(state.targetPhoto.ifBlank { state.outing.memories.lastOrNull()?.photoPath.orEmpty() }, "Replay camera preview, not a live camera", Modifier.fillMaxSize())
        } else if (state.cameraAllowed) camera(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .25f), Color.Black.copy(alpha = .07f), Color.Black.copy(alpha = .75f)))))
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 22.dp).padding(top = if (state.replay) 139.dp else 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = .45f)) {
                Text(if (state.replay) "CAMERA PREVIEW · REPLAY" else "APPROXIMATE DIRECTION", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            when {
                !state.cameraAllowed && !state.replay -> {
                    TravisGlyph("camera", Modifier.size(52.dp), Color.White)
                    Text("Open the camera", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Allow camera access in Prepare to see the live view.", color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                state.target == null -> {
                    TravisGlyph("ar", Modifier.size(58.dp), Color.White)
                    Text("A place to look toward.", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Choose a saved place or start retracing to open camera guidance.", color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                !usable -> {
                    TravisGlyph("info", Modifier.size(44.dp), Color.White)
                    Text(if (state.fixQuality != FixQuality.USABLE) "Waiting for a reliable location" else "Direction unavailable", color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(if (state.fixQuality == FixQuality.STALE) "The last fix is stale. Keep the original photo in view." else "Location and camera orientation need a reliable reading. Use the saved photo to compare.", color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Canvas(Modifier.size(76.dp)) {
                        val angle = relative!!.coerceIn(-90.0, 90.0).toFloat()
                        rotate(angle) {
                            val arrow = Path().apply { moveTo(size.width * .5f, 4f); lineTo(size.width * .82f, size.height * .75f); lineTo(size.width * .5f, size.height * .59f); lineTo(size.width * .18f, size.height * .75f); close() }
                            drawPath(arrow, Color.White)
                        }
                    }
                    Text(when {
                        abs(relative!!) < 15 -> "Ahead, approximately"
                        abs(relative) > 140 -> "Look behind you"
                        relative > 0 -> "Look to your right"
                        else -> "Look to your left"
                    }, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("${distanceLabel(state.distanceMeters)} · straight line to target", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodySmall)
                    Text("Compare the saved photo. Follow your recorded trail.", color = Color.White.copy(alpha = .8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun TravisPhoto(path: String, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (path.isBlank()) null else runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                if (path.startsWith("asset:")) context.assets.open(path.removePrefix("asset:")).use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
                else BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFFCDD5C4)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TravisGlyph("camera", Modifier.size(26.dp), TravisGreen.copy(alpha = .6f))
            if (path.isNotBlank()) Text("Photo unavailable", fontSize = 9.sp, color = TravisGreen)
        }
    }
}

@Composable
internal fun TravisGlyph(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = min(w, h) * .075f
        fun line(a: Pair<Float, Float>, b: Pair<Float, Float>) = drawLine(color, Offset(a.first * w, a.second * h), Offset(b.first * w, b.second * h), stroke, StrokeCap.Round)
        fun path(vararg coordinates: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
            val p = Path().apply { coordinates.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }; if (close) close() }
            drawPath(p, color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        when (kind) {
            "trail" -> { path(.08f to .82f, .37f to .18f, .56f to .58f, .73f to .32f, .94f to .82f); path(.35f to .83f, .5f to .65f, .66f to .83f) }
            "map" -> { path(.1f to .2f, .36f to .1f, .64f to .24f, .9f to .12f, .9f to .8f, .64f to .91f, .36f to .77f, .1f to .9f, close = true); line(.36f to .1f, .36f to .77f); line(.64f to .24f, .64f to .91f) }
            "ar" -> { path(.1f to .32f, .1f to .1f, .32f to .1f); path(.68f to .1f, .9f to .1f, .9f to .32f); path(.9f to .68f, .9f to .9f, .68f to .9f); path(.32f to .9f, .1f to .9f, .1f to .68f); drawCircle(color, .15f * w, style = Stroke(stroke)) }
            "camera" -> { path(.08f to .3f, .29f to .3f, .37f to .17f, .63f to .17f, .71f to .3f, .92f to .3f, .92f to .83f, .08f to .83f, close = true); drawCircle(color, .18f * w, Offset(.5f * w, .55f * h), style = Stroke(stroke)) }
            "settings" -> { line(.13f to .25f, .87f to .25f); line(.13f to .75f, .87f to .75f); drawCircle(color, .13f * w, Offset(.35f * w, .25f * h)); drawCircle(color, .13f * w, Offset(.66f * w, .75f * h)) }
            "north" -> path(.5f to .08f, .85f to .9f, .5f to .7f, .15f to .9f, close = true, fill = true)
            "spark" -> { path(.5f to .06f, .61f to .39f, .94f to .5f, .61f to .61f, .5f to .94f, .39f to .61f, .06f to .5f, .39f to .39f, close = true) }
            "check" -> path(.12f to .5f, .38f to .76f, .88f to .23f)
            "pause" -> { line(.32f to .2f, .32f to .8f); line(.68f to .2f, .68f to .8f) }
            "play" -> path(.25f to .12f, .88f to .5f, .25f to .88f, close = true, fill = true)
            "undo", "back" -> { path(.4f to .18f, .12f to .43f, .4f to .68f); path(.15f to .43f, .64f to .43f, .84f to .57f, .84f to .86f) }
            "info" -> { drawCircle(color, .42f * w, style = Stroke(stroke)); drawCircle(color, stroke / 1.7f, Offset(w * .5f, h * .28f)); line(.5f to .47f, .5f to .73f) }
        }
    }
}
