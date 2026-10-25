package com.backtrail.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.backtrail.app.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.*

private const val MAP_ANCHOR_Y = .34
private const val MIN_ZOOM = 12.0
private const val MAX_ZOOM = 20.0

/** Normalized Mercator camera. No Android types: gesture geometry is independently testable. */
internal data class MapViewport(val zoom: Double, val centerX: Double, val centerY: Double) {
    val worldSize: Double get() = 256.0 * 2.0.pow(zoom)
    fun worldAt(x: Double, y: Double, width: Double, height: Double): Pair<Double, Double> =
        centerX + (x - width / 2) / worldSize to centerY + (y - height * MAP_ANCHOR_Y) / worldSize

    fun transform(x: Double, y: Double, panX: Double, panY: Double, scale: Double, width: Double, height: Double): MapViewport {
        if (listOf(x, y, panX, panY, scale, width, height).any { !it.isFinite() } || scale <= 0 || width <= 0 || height <= 0) return this
        val nextZoom = (zoom + log2(scale)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val nextWorldSize = 256.0 * 2.0.pow(nextZoom)
        val anchor = worldAt(x, y, width, height)
        // The world point under the pinch centroid follows the centroid's translation.
        return MapViewport(nextZoom,
            anchor.first - (x + panX - width / 2) / nextWorldSize,
            (anchor.second - (y + panY - height * MAP_ANCHOR_Y) / nextWorldSize).coerceIn(0.0, 1.0))
    }
}

private fun world(latitude: Double, longitude: Double): Pair<Double, Double> =
    (longitude + 180) / 360 to (1 - asinh(tan(Math.toRadians(latitude.coerceIn(-85.0, 85.0)))) / PI) / 2
private data class TileAddress(val zoom: Int, val x: Int, val y: Int, val path: String, val pixels: Int)
private data class RasterLevel(val layer: String, val tiles: Map<TileAddress, ImageBitmap>)

/** Local rasters only. Gestures never request assets or alter guidance/recording state. */
@Composable
fun OfflineTrailMap(state: TravisUiState, modifier: Modifier = Modifier, onMemorySelected: ((String) -> Unit)? = null) {
    val assets = LocalContext.current.assets
    val density = LocalDensity.current
    val palette = MaterialTheme.colorScheme
    val points = state.outing.points
    val focus = if (state.mode == GuidanceMode.FIND_LANDMARK) state.target else state.fix
    val fallback = remember(points) {
        if (points.isEmpty()) 37.566 to -122.369 else
            (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2 to
                (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2
    }
    val focusWorld = world(focus?.latitude ?: fallback.first, focus?.longitude ?: fallback.second)
    var viewport by remember(state.outing.id) { mutableStateOf(MapViewport(if (points.isEmpty()) 15.0 else 17.0, focusWorld.first, focusWorld.second)) }
    var following by remember(state.outing.id) { mutableStateOf(true) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var previewId by remember(state.outing.id) { mutableStateOf<String?>(null) }
    val projectedPoints = remember(points) { points.map { world(it.latitude, it.longitude) } }
    var selectedLayer by rememberSaveable { mutableStateOf("maps-hybrid") }
    val mapIndex by produceState<Map<String, Map<Int, List<TileAddress>>>>(emptyMap(), assets) {
        value = withContext(Dispatchers.IO) {
            listOf("maps", "maps-aerial", "maps-hybrid").mapNotNull { layer ->
                val sources = if (layer == "maps-aerial") listOf(layer, "maps-detail") else listOf(layer)
                val levels = sources.flatMap { source ->
                    val pixels = runCatching { assets.open("$source/manifest.json").bufferedReader().use { org.json.JSONObject(it.readText()).optInt("tile_pixels", 256) } }.getOrDefault(256)
                    assets.list(source).orEmpty().mapNotNull { z ->
                    val zoom = z.toIntOrNull() ?: return@mapNotNull null
                    val addresses = assets.list("$source/$z").orEmpty().flatMap { x ->
                        val tileX = x.toIntOrNull() ?: return@flatMap emptyList()
                        assets.list("$source/$z/$x").orEmpty().mapNotNull { filename ->
                            val tileY = filename.substringBeforeLast('.').toIntOrNull()
                            if (tileY == null || filename.substringAfterLast('.') !in listOf("png", "jpg", "jpeg")) null
                            else TileAddress(zoom, tileX, tileY, "$source/$z/$x/$filename", pixels)
                        }
                    }
                    if (addresses.isEmpty()) null else zoom to addresses
                } }.toMap()
                if (levels.isEmpty()) null else layer to levels
            }.toMap()
        }
    }
    val layer = if (selectedLayer in mapIndex) selectedLayer else "maps"
    val levels = mapIndex[layer].orEmpty()
    val tileZoom = levels.keys.filter { it <= floor(viewport.zoom).toInt() }.maxOrNull() ?: levels.keys.minOrNull() ?: 16
    val visibleTiles by remember(mapIndex, layer, tileZoom, mapSize) {
        derivedStateOf {
            // Keep a coarser layer underneath so edges of the detailed region never
            // become blank while panning or while a higher-resolution tile decodes.
            val renderLevels = listOfNotNull(levels.keys.filter { it < tileZoom }.maxOrNull(), tileZoom).distinct()
            renderLevels.flatMap { zoom ->
                val count = 2.0.pow(zoom)
                val left = (viewport.centerX - mapSize.width / 2.0 / viewport.worldSize) * count
                val right = (viewport.centerX + mapSize.width / 2.0 / viewport.worldSize) * count
                val top = (viewport.centerY - mapSize.height * MAP_ANCHOR_Y / viewport.worldSize) * count
                val bottom = (viewport.centerY + mapSize.height * (1 - MAP_ANCHOR_Y) / viewport.worldSize) * count
                val candidates = levels[zoom].orEmpty().filter { it.x + 1 >= left && it.x <= right && it.y + 1 >= top && it.y <= bottom }
                if (candidates.size <= 32) candidates else candidates
                    .sortedBy { abs(it.x - viewport.centerX * count) + abs(it.y - viewport.centerY * count) }.take(32)
            }
        }
    }
    val tileCache = remember {
        object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
        }
    }
    val decodeZoom = floor(viewport.zoom).toInt()
    val raster by produceState(RasterLevel(layer, emptyMap()), layer, visibleTiles, decodeZoom) {
        val loaded = withContext(Dispatchers.IO) {
            buildMap {
                visibleTiles.forEach { tile ->
                    ensureActive()
                    val desiredPixels = (256.0 * 2.0.pow((decodeZoom - tile.zoom).coerceAtLeast(0))).toInt()
                    var sample = 1
                    while (tile.pixels / (sample * 2) >= desiredPixels) sample *= 2
                    val cacheKey = "${tile.path}:$sample"
                    val bitmap = tileCache.get(cacheKey) ?: runCatching {
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        assets.open(tile.path).use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
                    }.getOrNull()?.also { tileCache.put(cacheKey, it) }
                    if (bitmap != null) put(tile, bitmap)
                }
            }
        }
        value = RasterLevel(layer, loaded)
    }
    LaunchedEffect(focusWorld, following) {
        if (following) viewport = viewport.copy(centerX = focusWorld.first, centerY = focusWorld.second)
    }
    LaunchedEffect(state.selectedMemoryId, state.mode) { following = true; previewId = null }
    LaunchedEffect(points.isEmpty()) { if (points.isNotEmpty() && viewport.zoom == 15.0 && following) viewport = viewport.copy(zoom = 17.0) }
    val latestState by rememberUpdatedState(state)
    val latestMemories by rememberUpdatedState(state.outing.memories)
    val hitRadius = with(density) { 30.dp.toPx() }
    fun screenPoint(point: Pair<Double, Double>): Offset = Offset(
        ((point.first - viewport.centerX) * viewport.worldSize + mapSize.width / 2.0).toFloat(),
        ((point.second - viewport.centerY) * viewport.worldSize + mapSize.height * MAP_ANCHOR_Y).toFloat())
    fun zoomAt(point: Offset, factor: Double) {
        viewport = viewport.transform(point.x.toDouble(), point.y.toDouble(), 0.0, 0.0, factor, mapSize.width.toDouble(), mapSize.height.toDouble())
        following = false
    }
    fun inspectMemory(memory: Memory) {
        memory.point?.let { point ->
            val center = world(point.latitude, point.longitude)
            viewport = viewport.copy(centerX = center.first, centerY = center.second)
            following = false
            previewId = memory.id
        }
    }
    val preview = state.outing.memories.firstOrNull { it.id == previewId }
    val showTiles = !(state.replay && !state.mapAvailable)
    val covered = showTiles && visibleTiles.isNotEmpty()
    val aerialAvailable = "maps-aerial" in mapIndex
    val controlsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (density.fontScale > 1.2f) 126.dp else 110.dp
    var layerMenu by remember { mutableStateOf(false) }
    val latitudeAtCenter = Math.toDegrees(atan(sinh(PI * (1 - 2 * viewport.centerY))))
    val metersPerPixel = 156543.03392 * cos(Math.toRadians(latitudeAtCenter)) / 2.0.pow(viewport.zoom)
    val scaleWidth = with(density) { 72.dp.toPx() }
    val approximateScale = (scaleWidth * metersPerPixel).coerceAtLeast(.1)
    val order = 10.0.pow(floor(log10(approximateScale)))
    val scaleMeters = listOf(1.0, 2.0, 5.0, 10.0).last { it * order <= approximateScale } * order
    val gestures = Modifier
            .semantics {
                contentDescription = "Offline map. Pinch to zoom, drag to explore, or double tap to zoom. Tap a numbered saved place to inspect it."
                stateDescription = "Zoom %.1f. %s".format(java.util.Locale.US, viewport.zoom,
                    if (!following) "Exploring map" else if (state.mode == GuidanceMode.FIND_LANDMARK) "Centered on saved place" else "Following location")
            }
            .pointerInput(state.outing.id) {
                detectTransformGestures { centroid, pan, scale, _ ->
                    if (pan != Offset.Zero || scale != 1f) {
                        viewport = viewport.transform(centroid.x.toDouble(), centroid.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(), scale.toDouble(), size.width.toDouble(), size.height.toDouble())
                        following = false
                        previewId = null
                    }
                }
            }
            .pointerInput(state.outing.id) {
                detectTapGestures(
                    onDoubleTap = { zoomAt(it, 2.0) },
                    onTap = { tap ->
                        val tapped = latestMemories.filter { it.point != null }.minByOrNull { memory ->
                            val at = screenPoint(world(memory.point!!.latitude, memory.point.longitude))
                            (at - tap).getDistance()
                        }?.takeIf { memory ->
                            (screenPoint(world(memory.point!!.latitude, memory.point.longitude)) - tap).getDistance() <= hitRadius
                        }
                        if (tapped == null) previewId = null else inspectMemory(tapped)
                    },
                )
            }
    Box(modifier.background(palette.surfaceContainer).clipToBounds().onSizeChanged { mapSize = it }.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            fun at(point: Pair<Double, Double>) = Offset(
                ((point.first - viewport.centerX) * viewport.worldSize + size.width / 2).toFloat(),
                ((point.second - viewport.centerY) * viewport.worldSize + size.height * MAP_ANCHOR_Y).toFloat())
            fun at(point: TrailPoint) = at(world(point.latitude, point.longitude))
            val left = viewport.centerX * viewport.worldSize - size.width / 2
            val top = viewport.centerY * viewport.worldSize - size.height * MAP_ANCHOR_Y
            if (showTiles && raster.layer == layer) raster.tiles.forEach { (coordinate, bitmap) ->
                val tileSize = 256.0 * 2.0.pow(viewport.zoom - coordinate.zoom)
                val x = coordinate.x * tileSize - left; val y = coordinate.y * tileSize - top
                if (x + tileSize >= 0 && y + tileSize >= 0 && x <= size.width && y <= size.height) {
                    drawImage(bitmap, dstOffset = IntOffset(x.floorToInt(), y.floorToInt()), dstSize = IntSize(ceil(tileSize + 1).toInt(), ceil(tileSize + 1).toInt()), alpha = 1f, filterQuality = FilterQuality.Low)
                }
            }
            points.zipWithNext().forEachIndexed { index, (a, b) -> if (a.segment == b.segment) {
                val inReturn = state.mode == GuidanceMode.RETRACE && state.returnRouteStartIndex != null && state.returnRouteEndIndex != null && index >= state.returnRouteStartIndex && index < state.returnRouteEndIndex
                val color = if (state.mode != GuidanceMode.RETRACE || inReturn) TravisOrange else TravisOrange.copy(alpha = .28f)
                drawLine(Color.White.copy(alpha = .95f), at(projectedPoints[index]), at(projectedPoints[index + 1]), 7.dp.toPx(), StrokeCap.Round)
                drawLine(color, at(projectedPoints[index]), at(projectedPoints[index + 1]), (if (inReturn) 4.5.dp else 3.5.dp).toPx(), StrokeCap.Round)
            } }
            points.firstOrNull()?.let { drawCircle(TravisGreen, 7.dp.toPx(), at(it)); drawCircle(Color.White, 3.dp.toPx(), at(it)) }
            state.target?.let { drawCircle(TravisOrange, 18.dp.toPx(), at(it), style = Stroke(2.dp.toPx())) }

        }
        state.outing.memories.forEachIndexed { index, memory -> memory.point?.let { point ->
            val screen = screenPoint(world(point.latitude, point.longitude))
            if (screen.x in -40f..mapSize.width + 40f && screen.y in -40f..mapSize.height + 40f) {
                val selected = memory.id == state.selectedMemoryId || memory.id == previewId || memory.id == state.destinationMemoryId
                Surface(onClick = { inspectMemory(memory) }, modifier = Modifier.semantics { contentDescription = "Saved place ${index + 1}: ${memory.title}" }.offset { IntOffset(screen.x.roundToInt() - hitRadius.roundToInt(), screen.y.roundToInt() - hitRadius.roundToInt()) }.size(60.dp), shape = CircleShape, color = Color.Transparent) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(if (selected) 34.dp else 29.dp), shape = CircleShape, color = if (selected) TravisOrange else TravisGreen, border = androidx.compose.foundation.BorderStroke(2.dp, Color.White), shadowElevation = 3.dp) {
                            Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        } }
        // Draw the measured position above saved-place markers; dense nearby
        // memories must not conceal the user's location.
        Canvas(Modifier.fillMaxSize()) {
            state.fix?.let { point ->
                val center = screenPoint(world(point.latitude, point.longitude))
                val metersPerPixel = 156543.03392 * cos(Math.toRadians(point.latitude)) / 2.0.pow(viewport.zoom)
                val fresh = state.fixQuality == FixQuality.USABLE
                drawCircle(TravisBlue.copy(alpha = if (fresh) .16f else .08f), (point.accuracy / metersPerPixel).toFloat().coerceIn(9.dp.toPx(), 160.dp.toPx()), center)
                if (fresh && state.mapHeadingReliable) state.mapHeading?.let { heading ->
                    val radius = 47.dp.toPx()
                    drawArc(TravisBlue.copy(alpha = .28f), heading.toFloat() - 115f, 50f, true,
                        Offset(center.x - radius, center.y - radius), androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
                    val radians = Math.toRadians(heading)
                    val tip = center + Offset(sin(radians).toFloat(), -cos(radians).toFloat()) * 18.dp.toPx()
                    drawLine(Color.White, center, tip, 7.dp.toPx(), StrokeCap.Round)
                    drawLine(TravisBlue, center, tip, 4.dp.toPx(), StrokeCap.Round)
                }
                drawCircle(Color.White, 9.dp.toPx(), center)
                drawCircle(if (fresh) TravisBlue else Color.Gray, 6.dp.toPx(), center)
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = controlsTop, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { zoomAt(Offset(mapSize.width / 2f, mapSize.height * MAP_ANCHOR_Y.toFloat()), 2.0) }, modifier = Modifier.semantics { contentDescription = "Zoom in" }, enabled = viewport.zoom < MAX_ZOOM) { Text("+", style = MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton(onClick = { zoomAt(Offset(mapSize.width / 2f, mapSize.height * MAP_ANCHOR_Y.toFloat()), .5) }, modifier = Modifier.semantics { contentDescription = "Zoom out" }, enabled = viewport.zoom > MIN_ZOOM) { Text("−", style = MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton(onClick = { following = true; viewport = viewport.copy(centerX = focusWorld.first, centerY = focusWorld.second); previewId = null }, modifier = Modifier.semantics { contentDescription = if (!following) "Recenter map" else if (state.mode == GuidanceMode.FIND_LANDMARK) "Map centered on saved place" else "Map following location" }) { TravisGlyph("ar", Modifier.size(20.dp), palette.primary) }
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = controlsTop), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (aerialAvailable) Box {
                FilledTonalButton(onClick = { layerMenu = true }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("${when(layer) { "maps-aerial" -> "Aerial"; "maps-hybrid" -> "Hybrid"; else -> "Terrain" }} · map style", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                    listOf("maps-hybrid" to "Hybrid", "maps" to "Terrain", "maps-aerial" to "Aerial").filter { it.first in mapIndex }.forEach { (path, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { selectedLayer = path; layerMenu = false })
                    }
                }
            }
            Surface(color = palette.surface.copy(alpha = .88f), shape = RoundedCornerShape(7.dp)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(when { mapIndex.isEmpty() -> "Loading saved map…"; !covered -> "No offline map here"; else -> "USGS · offline" }, style = MaterialTheme.typography.labelSmall)
                    Text(distanceLabel(scaleMeters), style = MaterialTheme.typography.labelSmall)
                    Canvas(Modifier.width(with(density) { (scaleMeters / metersPerPixel).toFloat().toDp() }).height(5.dp)) {
                        val color = palette.onSurface
                        drawLine(color, Offset(0f, 0f), Offset(0f, size.height), 1.5.dp.toPx())
                        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx())
                        drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height), 1.5.dp.toPx())
                    }
                }
            }
        }
        preview?.let { memory ->
            Surface(Modifier.align(Alignment.TopCenter).padding(top = controlsTop + 110.dp, start = 12.dp, end = 76.dp), color = palette.surface, shape = RoundedCornerShape(18.dp), shadowElevation = 6.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(memory.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (latestState.replay) "SAMPLE SAVED PLACE" else "YOUR SAVED PLACE", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onMemorySelected != null) TextButton(onClick = { onMemorySelected(memory.id); previewId = null }) { Text("Open place") }
                        TextButton(onClick = { previewId = null }) { Text("Close") }
                    }
                }
            }
        }
    }
}
private fun Double.floorToInt(): Int = floor(this).toInt()
