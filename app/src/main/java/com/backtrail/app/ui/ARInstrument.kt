package com.backtrail.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** A camera-relative instrument, not a world anchor. Repaints only when supplied
 * sensor state changes; no frame timer, extra sensors, model calls or 3D engine. */
@Composable
internal fun ARDirectionalInstrument(relativeDegrees: Double, headingDegrees: Double, modifier: Modifier = Modifier) {
    var previous by remember { mutableDoubleStateOf(relativeDegrees) }
    var unwrapped by remember { mutableFloatStateOf(relativeDegrees.toFloat()) }
    LaunchedEffect(relativeDegrees) {
        unwrapped += (((relativeDegrees - previous + 540.0) % 360.0) - 180.0).toFloat()
        previous = relativeDegrees
    }
    val animated by animateFloatAsState(unwrapped, tween(110), label = "Camera direction")
    Canvas(modifier.semantics { contentDescription = "Approximate camera compass toward the next target" }) {
        val radius = min(size.width * .42f, size.height * .64f)
        val center = Offset(size.width / 2, size.height * .61f)
        fun project(x: Float, forward: Float, height: Float = 0f): Offset {
            val perspective = 1f + forward * .12f
            return Offset(center.x + radius * x / perspective,
                center.y - radius * (forward * .47f + height) / perspective)
        }
        fun point(angle: Double, scale: Float): Offset = project(
            (sin(Math.toRadians(angle)) * scale).toFloat(),
            (cos(Math.toRadians(angle)) * scale).toFloat())
        fun polygon(points: List<Offset>) = Path().apply {
            points.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        val rim = (0..72).map { point(it * 5.0, 1f) }
        // A restrained projected disk and rim establish depth without implying a
        // surface in the camera image has been detected or tracked.
        drawOval(Color.Black.copy(alpha = .28f), Offset(center.x - radius, center.y - radius * .33f), Size(radius * 2, radius * .9f))
        drawPath(polygon(rim), Color(0xFF132D26).copy(alpha = .55f))
        drawPath(polygon(rim), Color(0xFFE7EEE5).copy(alpha = .62f), style = Stroke(1.2.dp.toPx()))
        drawPath(polygon((0..72).map { point(it * 5.0, .78f) }), Color.White.copy(alpha = .12f), style = Stroke(.7.dp.toPx()))
        for (tick in 0 until 36) {
            val angle = tick * 10.0 - headingDegrees
            val major = tick % 9 == 0
            drawLine(if (major) Color.White.copy(alpha = .9f) else Color.White.copy(alpha = .26f),
                point(angle, if (major) .77f else .86f), point(angle, .94f),
                if (major) 1.6.dp.toPx() else .8.dp.toPx(), StrokeCap.Round)
        }
        // North marker is driven by true-north camera heading, distinct from the
        // orange destination arrow. No guessed heading reaches this composable.
        drawCircle(TravisBlue, 2.8.dp.toPx(), point(-headingDegrees, .87f))
        drawLine(Color.White.copy(alpha = .85f), project(0f, 1.08f), project(0f, 1.23f), 2.dp.toPx(), StrokeCap.Round)

        val theta = Math.toRadians(animated.toDouble())
        fun arrowPoint(x: Float, forward: Float, height: Float): Offset = project(
            (x * cos(theta) + forward * sin(theta)).toFloat(),
            (forward * cos(theta) - x * sin(theta)).toFloat(), height)
        val shadow = listOf(arrowPoint(0f,.76f,0f),arrowPoint(-.28f,-.48f,0f),
            arrowPoint(0f,-.24f,0f),arrowPoint(.28f,-.48f,0f))
        drawPath(polygon(shadow), Color.Black.copy(alpha = .55f))
        val tip=arrowPoint(0f,.76f,.18f)
        val left=arrowPoint(-.28f,-.48f,.14f)
        val right=arrowPoint(.28f,-.48f,.14f)
        val ridge=arrowPoint(0f,-.14f,.35f)
        val lowerLeft=arrowPoint(-.28f,-.48f,.02f)
        val lowerRight=arrowPoint(.28f,-.48f,.02f)
        drawPath(polygon(listOf(left,lowerLeft,shadow[2],ridge)),Color(0xFF934324))
        drawPath(polygon(listOf(right,lowerRight,shadow[2],ridge)),Color(0xFFB85028))
        drawPath(polygon(listOf(tip,left,ridge)),Color(0xFFFFBE89))
        drawPath(polygon(listOf(tip,ridge,right)),TravisOrange)
        drawLine(Color(0xFFFFE6CC),tip,ridge,1.4.dp.toPx(),StrokeCap.Round)
        drawCircle(Color.White,2.4.dp.toPx(),project(0f,-1.02f))
    }
}
