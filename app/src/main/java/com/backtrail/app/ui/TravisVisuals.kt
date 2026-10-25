package com.backtrail.app.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val displayFix = if (state.fixQuality == FixQuality.USABLE) state.fix else state.lastKnownFix ?: state.fix
    val all = points + state.outing.memories.mapNotNull { it.point } + listOfNotNull(displayFix, state.target)
    Box(modifier.background(palette.surfaceContainer).clipToBounds()) {
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
                    displayFix?.let { fix ->
                        val center = position(fix)
                        val opacity = if (state.fixQuality != FixQuality.USABLE) .4f else 1f
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
    val relative = if (state.bearing?.isFinite() == true && state.heading?.isFinite() == true) ((state.bearing - state.heading + 540) % 360) - 180 else null
    val hasDirection = (state.directionUsable || state.approximateDirection || state.lastKnownGuidance) && state.bearing?.isFinite() == true
    val usable = hasDirection && state.headingReliable && relative != null
    val fontScale = LocalDensity.current.fontScale
    var compareOriginal by rememberSaveable(state.outing.id, state.target?.id, state.targetPhoto) { mutableStateOf(false) }
    var photoOpacity by rememberSaveable { mutableFloatStateOf(.45f) }
    val hasReference = state.target != null && state.targetPhoto.isNotBlank()
    val showReference = compareOriginal && hasReference && (state.cameraAllowed || state.replay)
    BoxWithConstraints(modifier.background(Color(0xFF1C3028))) {
        val compact = maxHeight < 560.dp || fontScale > 1.2f
        val landscape = maxWidth > maxHeight
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (landscape) (if (fontScale > 1.2f) 130.dp else 112.dp) else if (fontScale > 1.2f) 126.dp else 112.dp
        val hudHeight = (if (landscape) maxHeight - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() - 88.dp - topInset else maxHeight * .64f - topInset - 4.dp).coerceAtLeast(90.dp)
        if (state.replay) TravisPhoto(state.outing.memories.lastOrNull()?.photoPath.orEmpty(), "Replay camera preview, not a live camera", Modifier.fillMaxSize())
        else if (state.cameraAllowed) camera(Modifier.fillMaxSize())
        if (showReference) TravisPhoto(state.targetPhoto, "Manual original-photo reference; not aligned or recognized", Modifier.fillMaxSize().graphicsLayer { alpha = photoOpacity })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent, Color.Black.copy(alpha = .18f)))))
        Column(
            Modifier.align(if (landscape) Alignment.TopStart else Alignment.TopCenter).width(if (landscape) maxWidth * .52f else minOf(330.dp, maxWidth * if (state.mode == GuidanceMode.RETRACE) .78f else .9f))
                .padding(top = topInset).heightIn(max = hudHeight).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (!landscape) Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = .52f)) {
                Text(if (state.replay) "REPLAY CAMERA · APPROXIMATE" else "APPROXIMATE CAMERA GUIDANCE", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 9.sp, letterSpacing = .5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            when {
                !state.cameraAllowed && !state.replay -> CameraStatus("Open the camera", "Allow camera access in settings to see the live view.")
                state.target == null -> CameraStatus("Choose where to return", "Open Your places, or return to the start of your trail.")
                showReference -> Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .6f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Original photo overlay", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text("Manual reference · not aligned or recognized", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                        Slider(value = photoOpacity, onValueChange = { photoOpacity = it }, valueRange = .1f.. .85f,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Original photo opacity" },
                            colors = SliderDefaults.colors(thumbColor = TravisOrange, activeTrackColor = TravisOrange, inactiveTrackColor = Color.White.copy(alpha = .28f)))
                    }
                }
                !usable && hasDirection -> {
                    // A fixed north-up reference is useful without pretending the compass is live.
                    ARDirectionalInstrument(state.bearing!!, 0.0, Modifier.width(158.dp).height(112.dp), northUp = true)
                    CameraStatus("North-up reference · compass unavailable", state.headingStatus.ifBlank { "Hold the phone upright, away from magnetic interference." })
                    if (state.lastKnownGuidance) Text("Last GPS direction · ${state.guidanceAgeSeconds}s ago", color = Color(0xFFFFC89E), style = MaterialTheme.typography.labelMedium)
                }
                !usable -> CameraStatus(if (!state.directionUsable) "Waiting for location" else "Direction unavailable", if (!state.directionUsable) state.navigationMessage.ifBlank { "A fresh, accurate location on this trail is needed for the arrow." } else state.headingStatus.ifBlank { "Hold the phone upright, away from magnetic interference." })
                else -> {
                    if (state.lastKnownGuidance) Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF6B390F).copy(alpha = .92f)) {
                        Text("LAST KNOWN · ${state.guidanceAgeSeconds}s ago · GPS updating", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                    if (state.approximateDirection && !state.lastKnownGuidance) Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = .6f)) {
                        Text(state.pointerMessage, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFFFC89E), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                    // Matched guidance uses route distance; approximate pointers label waypoint distance.
                    if (landscape) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        ARDirectionalInstrument(relative, state.heading!!, Modifier.width(145.dp).height(105.dp))
                        GuidanceDistance(state, relative, true, Modifier.weight(1f))
                    } else {
                        ARDirectionalInstrument(relative, state.heading!!, Modifier.width(if (compact) 158.dp else 210.dp).height(if (compact) 112.dp else 156.dp))
                        GuidanceDistance(state, relative, compact)
                    }
                }
            }
            if (hasReference && (state.cameraAllowed || state.replay)) TextButton(onClick = { compareOriginal = !compareOriginal }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White, containerColor = Color.Black.copy(alpha = .5f))) {
                TravisGlyph(if (showReference) "ar" else "camera", Modifier.size(16.dp), Color.White)
                Spacer(Modifier.width(7.dp)); Text(if (showReference) "Show direction" else "Overlay saved photo", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.mode == GuidanceMode.RETRACE && state.routeStepCount > 0 && maxHeight >= 480.dp && !landscape) {
            Surface(modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-maxHeight.value * .1f).dp).padding(end = 10.dp), shape = RoundedCornerShape(22.dp), color = Color.Black.copy(alpha = .48f)) {
                Column(Modifier.padding(horizontal = 9.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TRAIL", color = Color.White.copy(alpha = .8f), fontSize = 8.sp, letterSpacing = .6.sp)
                    Text("${state.routeStepNumber.coerceAtLeast(1)}/${state.routeStepCount}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.width(4.dp).height(92.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(state.routeProgress.coerceIn(.015f, 1f)).background(TravisOrange))
                    }
                    Text("${(state.routeProgress.coerceIn(0f, 1f) * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun GuidanceDistance(state: TravisUiState, relative: Double, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .50f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (state.approximateDirection) "≈ ${distanceLabel(state.pointerDistanceMeters)}" else if (state.mode == GuidanceMode.RETRACE) "≈ ${distanceLabel(state.remainingMeters)}" else distanceLabel(state.distanceMeters), color = Color.White, fontSize = if (compact) 26.sp else 36.sp, fontWeight = FontWeight.SemiBold)
            Text(if (state.lastKnownGuidance) "AT LAST GPS POSITION · DISTANCE HELD" else if (state.mode == GuidanceMode.FIND_LANDMARK) "STRAIGHT LINE TO PLACE" else if (state.approximateDirection) "TO WAYPOINT · APPROXIMATE BEARING" else if (state.mode == GuidanceMode.RETRACE) "LEFT ON RECORDED TRAIL" else "STRAIGHT LINE TO PLACE", color = Color.White.copy(alpha = .85f), fontSize = 9.sp, letterSpacing = .5.sp, textAlign = TextAlign.Center)
            Text(when {
                state.lastKnownGuidance -> "Reference only · check your trail"
                abs(relative) < 12 -> "Ahead, approximately"
                abs(relative) > 140 -> "Turn to look behind"
                relative > 0 -> "Turn right"
                else -> "Turn left"
            }, color = Color(0xFFFFC89E), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CameraStatus(title: String, detail: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .56f)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(detail, color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
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
                fun stream() = if (path.startsWith("asset:")) context.assets.open(path.removePrefix("asset:")) else java.io.File(path).inputStream()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = stream().use { BitmapFactory.decodeStream(it, null, options) }
                val orientation = runCatching { stream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                    }
                }
                if (decoded == null) null
                else if (matrix.isIdentity) decoded.asImageBitmap()
                else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).asImageBitmap()
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
