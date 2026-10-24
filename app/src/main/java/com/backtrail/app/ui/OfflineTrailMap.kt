package com.backtrail.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import com.backtrail.app.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

private fun world(lat:Double,lon:Double,z:Int):Pair<Double,Double> {
    val n=256.0*2.0.pow(z)
    val y=(1.0-asinh(tan(Math.toRadians(lat.coerceIn(-85.0,85.0))))/PI)/2*n
    return (lon+180)/360*n to y
}
/** Raster tiles, labels and attribution are packaged. No SDK or network fallback. */
@Composable fun OfflineTrailMap(state:TravisUiState,modifier:Modifier=Modifier) {
    val assets=LocalContext.current.assets
    var zoom by remember(state.outing.id) { mutableIntStateOf(15) }
    var pan by remember(state.outing.id) { mutableStateOf(Offset.Zero) }
    val tiles by produceState<Map<Pair<Int,Int>,ImageBitmap>>(emptyMap(),zoom) {
        value=withContext(Dispatchers.IO) {
            buildMap {
                try { assets.list("maps/$zoom")?.forEach { x-> assets.list("maps/$zoom/$x")?.filter { it.endsWith(".png") }?.forEach { y->
                    assets.open("maps/$zoom/$x/$y").use { stream-> BitmapFactory.decodeStream(stream)?.let { put(x.toInt() to y.removeSuffix(".png").toInt(),it.asImageBitmap()) } }
                } } } catch(_:Exception) { /* Missing coverage remains explicit in UI. */ }
            }
        }
    }
    val points=state.outing.points
    val lat=if(points.isNotEmpty())(points.minOf { it.latitude }+points.maxOf { it.latitude })/2 else state.fix?.latitude?:37.566
    val lon=if(points.isNotEmpty())(points.minOf { it.longitude }+points.maxOf { it.longitude })/2 else state.fix?.longitude?:-122.369
    val center=world(lat,lon,zoom)
    val background=MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.background(background)) {
        Canvas(Modifier.fillMaxSize().pointerInput(zoom) { detectTransformGestures { _,delta,scale,_->
            pan+=delta
            if(scale>1.08f && zoom<16) { zoom++;pan=Offset.Zero }
            if(scale<0.92f && zoom>12) { zoom--;pan=Offset.Zero }
        } }) {
            val left=center.first-size.width/2-pan.x
            val top=center.second-size.height*0.34-pan.y
            fun offset(point:TrailPoint):Offset { val p=world(point.latitude,point.longitude,zoom);return Offset((p.first-left).toFloat(),(p.second-top).toFloat()) }
            if(state.mapAvailable || state.fix==null) tiles.forEach { (coord,bitmap)->
                val x=(coord.first*256-left).roundToInt();val y=(coord.second*256-top).roundToInt()
                if(x in -256..size.width.toInt() && y in -256..size.height.toInt()) drawImage(bitmap,dstOffset=IntOffset(x,y),dstSize=IntSize(256,256),alpha=.83f)
            }
            points.zipWithNext().forEach { (a,b)-> if(a.segment==b.segment) {
                drawLine(Color.White.copy(alpha=.88f),offset(a),offset(b),12f,StrokeCap.Round)
                drawLine(TravisOrange,offset(a),offset(b),7f,StrokeCap.Round)
            } }
            points.firstOrNull()?.let { drawCircle(TravisGreen,9f,offset(it));drawCircle(Color.White,4f,offset(it)) }
            state.outing.memories.forEach { m->m.point?.let { p->
                val selected=m.id==state.selectedMemoryId
                drawCircle(Color.White,if(selected)15f else 12f,offset(p));drawCircle(if(selected)TravisOrange else TravisGreen,if(selected)11f else 8f,offset(p))
            } }
            state.target?.let { p->drawCircle(TravisOrange,23f,offset(p),style=Stroke(3f)) }
            state.fix?.let { p->
                val at=offset(p);val metersPerPixel=156543.03392*cos(Math.toRadians(p.latitude))/2.0.pow(zoom)
                drawCircle(TravisBlue.copy(alpha=.14f),(p.accuracy/metersPerPixel).toFloat().coerceIn(8f,250f),at)
                drawCircle(Color.White,12f,at);drawCircle(if(state.fixQuality==FixQuality.USABLE)TravisBlue else Color.Gray,8f,at)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(14.dp)) {
            FilledTonalIconButton(onClick={zoom=(zoom+1).coerceAtMost(16);pan=Offset.Zero}) { Text("+",style=MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton(onClick={zoom=(zoom-1).coerceAtLeast(12);pan=Offset.Zero}) { Text("−",style=MaterialTheme.typography.titleLarge) }
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(top=130.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),shape=MaterialTheme.shapes.small) {
            Text(if(!state.mapAvailable && state.fix!=null)"Map coverage unavailable · recorded trace only" else "USGS · The National Map · offline z$zoom",Modifier.padding(horizontal=9.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)
        }

    }
}
