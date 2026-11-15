package com.example.ui.reader

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.local.ReaderSurfaceTone
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

private enum class TurnDirection {
    NONE,
    FORWARD,
    BACKWARD
}

private data class Point2D(val x: Float, val y: Float)

@Composable
fun PageCurlView(
    currentPageIndex: Int,
    pageCount: Int,
    currentBitmap: ImageBitmap?,
    nextBitmap: ImageBitmap?,
    prevBitmap: ImageBitmap?,
    surfaceTone: ReaderSurfaceTone,
    onPageChanged: (Int) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Interactive curl state
    var turnDirection by remember { mutableStateOf(TurnDirection.NONE) }
    var cornerAnchorY by remember { mutableFloatStateOf(0f) }
    var dragFingerPos by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    // Animated progress for programmatic tap curls & release snap (0f = top sheet flat, 1f = top sheet turned away)
    val curlProgress = remember { Animatable(0f) }
    val curlYRatio = remember { Animatable(0.85f) }

    // Zoom state (double-tap or pinch)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom when page changes
    LaunchedEffect(currentPageIndex) {
        if (zoomScale != 1f) {
            zoomScale = 1f
            panOffset = Offset.Zero
        }
    }

    val colorFilter = remember(surfaceTone) {
        when (surfaceTone) {
            ReaderSurfaceTone.PAPER -> null
            ReaderSurfaceTone.SEPIA -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.96f, 0.04f, 0f, 0f, 4f,
                        0f, 0.92f, 0.03f, 0f, 0f,
                        0f, 0.04f, 0.80f, 0f, -6f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            ReaderSurfaceTone.NIGHT -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.72f, 0f, 0f, 0f, 0f,
                        0f, 0.67f, 0f, 0f, 0f,
                        0f, 0f, 0.58f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    val pageBackdropColor = when (surfaceTone) {
        ReaderSurfaceTone.PAPER -> Color(0xFFFAF4E6)
        ReaderSurfaceTone.SEPIA -> Color(0xFFF0E4C8)
        ReaderSurfaceTone.NIGHT -> Color(0xFF191512)
    }

    val backPaperTint = when (surfaceTone) {
        ReaderSurfaceTone.PAPER -> android.graphics.Color.parseColor("#F5EEDB")
        ReaderSurfaceTone.SEPIA -> android.graphics.Color.parseColor("#E9DAC0")
        ReaderSurfaceTone.NIGHT -> android.graphics.Color.parseColor("#B5AA96")
    }

    fun triggerAnimatedForwardTurn() {
        if (currentPageIndex >= pageCount - 1 || turnDirection != TurnDirection.NONE || viewportSize.width <= 0) return
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        turnDirection = TurnDirection.FORWARD
        cornerAnchorY = h
        scope.launch {
            curlProgress.snapTo(0.02f)
            curlYRatio.snapTo(0.86f)
            launch {
                curlYRatio.animateTo(
                    targetValue = 0.68f,
                    animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
                )
            }
            curlProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 440, easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f))
            )
            onPageChanged(currentPageIndex + 1)
            turnDirection = TurnDirection.NONE
            curlProgress.snapTo(0f)
        }
    }

    fun triggerAnimatedBackwardTurn() {
        if (currentPageIndex <= 0 || turnDirection != TurnDirection.NONE || viewportSize.width <= 0) return
        val h = viewportSize.height.toFloat()
        turnDirection = TurnDirection.BACKWARD
        cornerAnchorY = h
        scope.launch {
            curlProgress.snapTo(0.98f)
            curlYRatio.snapTo(0.75f)
            launch {
                curlYRatio.animateTo(
                    targetValue = 0.90f,
                    animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
                )
            }
            curlProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 440, easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f))
            )
            onPageChanged(currentPageIndex - 1)
            turnDirection = TurnDirection.NONE
            curlProgress.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackdropColor)
            .onSizeChanged { viewportSize = it }
            .pointerInput(currentPageIndex, pageCount, zoomScale) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (zoomScale > 1.05f) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        } else {
                            zoomScale = 2.1f
                            val cx = viewportSize.width / 2f
                            val cy = viewportSize.height / 2f
                            panOffset = Offset(
                                x = (cx - tapOffset.x) * 0.9f,
                                y = (cy - tapOffset.y) * 0.9f
                            )
                        }
                    },
                    onTap = { tapOffset ->
                        if (zoomScale > 1.05f) {
                            onCenterTap()
                            return@detectTapGestures
                        }
                        val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                        val relX = tapOffset.x / w
                        when {
                            relX > 0.72f -> triggerAnimatedForwardTurn()
                            relX < 0.28f -> triggerAnimatedBackwardTurn()
                            else -> onCenterTap()
                        }
                    }
                )
            }
            .then(
                if (zoomScale > 1.05f) {
                    Modifier.pointerInput(zoomScale) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (zoomScale * zoom).coerceIn(1f, 3.5f)
                            zoomScale = nextScale
                            if (nextScale <= 1.02f) {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            } else {
                                val maxPanX = (viewportSize.width * (nextScale - 1f)) / 2f
                                val maxPanY = (viewportSize.height * (nextScale - 1f)) / 2f
                                panOffset = Offset(
                                    x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                )
                            }
                        }
                    }
                } else {
                    Modifier.pointerInput(currentPageIndex, pageCount, viewportSize) {
                        var activeDragDir = TurnDirection.NONE
                        var lastDx = 0f

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                if (viewportSize.width <= 0 || turnDirection != TurnDirection.NONE) return@detectDragGestures
                                val w = viewportSize.width.toFloat()
                                val h = viewportSize.height.toFloat()

                                activeDragDir = when {
                                    startOffset.x < w * 0.34f && currentPageIndex > 0 -> TurnDirection.BACKWARD
                                    startOffset.x >= w * 0.34f && currentPageIndex < pageCount - 1 -> TurnDirection.FORWARD
                                    else -> TurnDirection.NONE
                                }

                                if (activeDragDir != TurnDirection.NONE) {
                                    isDragging = true
                                    turnDirection = activeDragDir
                                    cornerAnchorY = if (startOffset.y < h * 0.42f) 0f else h
                                    dragFingerPos = startOffset
                                    lastDx = 0f
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (activeDragDir == TurnDirection.NONE) return@detectDragGestures
                                change.consume()
                                lastDx = dragAmount.x
                                dragFingerPos = change.position
                            },
                            onDragEnd = {
                                if (activeDragDir == TurnDirection.NONE) {
                                    isDragging = false
                                    return@detectDragGestures
                                }
                                val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                                val h = viewportSize.height.toFloat().coerceAtLeast(1f)
                                val currentProgress = ((w - dragFingerPos.x) / w).coerceIn(0f, 1f)
                                val currentYRatio = (dragFingerPos.y / h).coerceIn(0.05f, 0.95f)
                                val dir = activeDragDir
                                activeDragDir = TurnDirection.NONE
                                isDragging = false

                                scope.launch {
                                    curlProgress.snapTo(currentProgress)
                                    curlYRatio.snapTo(currentYRatio)

                                    val shouldComplete = when (dir) {
                                        TurnDirection.FORWARD -> currentProgress > 0.30f || lastDx < -14f
                                        TurnDirection.BACKWARD -> currentProgress < 0.70f || lastDx > 14f
                                        TurnDirection.NONE -> false
                                    }

                                    when (dir) {
                                        TurnDirection.FORWARD -> {
                                            if (shouldComplete) {
                                                curlProgress.animateTo(
                                                    1f,
                                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                                                )
                                                onPageChanged(currentPageIndex + 1)
                                            } else {
                                                curlProgress.animateTo(
                                                    0f,
                                                    spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
                                                )
                                            }
                                        }
                                        TurnDirection.BACKWARD -> {
                                            if (shouldComplete) {
                                                curlProgress.animateTo(
                                                    0f,
                                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                                                )
                                                onPageChanged(currentPageIndex - 1)
                                            } else {
                                                curlProgress.animateTo(
                                                    1f,
                                                    spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
                                                )
                                            }
                                        }
                                        TurnDirection.NONE -> Unit
                                    }
                                    turnDirection = TurnDirection.NONE
                                    curlProgress.snapTo(0f)
                                }
                            },
                            onDragCancel = {
                                activeDragDir = TurnDirection.NONE
                                isDragging = false
                                turnDirection = TurnDirection.NONE
                            }
                        )
                    }
                }
            )
    ) {
        if (currentBitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            val activeFront: ImageBitmap
            val activeBack: ImageBitmap?

            when (turnDirection) {
                TurnDirection.BACKWARD -> {
                    activeFront = prevBitmap ?: currentBitmap
                    activeBack = currentBitmap
                }
                else -> {
                    activeFront = currentBitmap
                    activeBack = nextBitmap
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                val effectiveProgress = if (isDragging) {
                    ((w - dragFingerPos.x) / w).coerceIn(0.001f, 0.999f)
                } else {
                    curlProgress.value
                }

                val effectiveY = if (isDragging) {
                    dragFingerPos.y.coerceIn(0f, h)
                } else {
                    (curlYRatio.value * h).coerceIn(0f, h)
                }

                if (turnDirection == TurnDirection.NONE || effectiveProgress <= 0.003f || activeBack == null) {
                    drawImageFitted(activeFront, w, h, colorFilter)
                    drawSpineBindingShadow(w, h)
                    return@Canvas
                }

                if (effectiveProgress >= 0.997f) {
                    drawImageFitted(activeBack, w, h, colorFilter)
                    drawSpineBindingShadow(w, h)
                    return@Canvas
                }

                // Map progress 0..1 to pullPoint.x from W down to -W so the crease sweeps across the entire sheet
                val rawPullX = w * (1f - 2f * effectiveProgress)
                val rawPullY = if (abs(effectiveY - cornerAnchorY) < 4f) {
                    if (cornerAnchorY >= h * 0.5f) h - 6f else 6f
                } else {
                    effectiveY
                }

                val clampedPull = clampPullPointToSpine(
                    rawX = rawPullX,
                    rawY = rawPullY,
                    width = w,
                    height = h,
                    cornerY = cornerAnchorY
                )

                val corner = Point2D(w, cornerAnchorY)
                val dx = clampedPull.x - corner.x
                val dy = clampedPull.y - corner.y
                val dist = hypot(dx, dy)

                if (dist < 2f) {
                    drawImageFitted(activeFront, w, h, colorFilter)
                    drawSpineBindingShadow(w, h)
                    return@Canvas
                }

                val nx = dx / dist
                val ny = dy / dist
                val midX = (corner.x + clampedPull.x) * 0.5f
                val midY = (corner.y + clampedPull.y) * 0.5f

                val pageRect = listOf(
                    Point2D(0f, 0f),
                    Point2D(w, 0f),
                    Point2D(w, h),
                    Point2D(0f, h)
                )

                // Flat remaining region of front page: (P - M) . n >= 0
                val flatFrontPoly = clipPolygonHalfPlane(pageRect, midX, midY, nx, ny, keepPositive = true)
                // Cut-away corner exposing underlying back page: (P - M) . n <= 0
                val exposedBackPoly = clipPolygonHalfPlane(pageRect, midX, midY, nx, ny, keepPositive = false)
                // Reflected curled back-face polygon
                val backFacePoly = exposedBackPoly.map { pt ->
                    val signed = (pt.x - midX) * nx + (pt.y - midY) * ny
                    Point2D(pt.x - 2f * signed * nx, pt.y - 2f * signed * ny)
                }

                val liftFactor = sin(PI * effectiveProgress).toFloat().coerceIn(0f, 1f)

                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas

                    // 1. Draw exposed underlying page (next/current) clipped to cut-away corner
                    if (exposedBackPoly.size >= 3) {
                        val backClipPath = exposedBackPoly.toAndroidPath()
                        nc.save()
                        nc.clipPath(backClipPath)
                        drawNativeBitmapFitted(nc, activeBack, w, h, colorFilter)

                        // Cast shadow from the lifted fold onto the newly exposed underlying page
                        val shadowDepth = (32.dp.toPx() + 56.dp.toPx() * liftFactor)
                        drawCreaseDirectionalShadow(
                            canvas = nc,
                            originX = midX,
                            originY = midY,
                            dirX = -nx,
                            dirY = -ny,
                            span = shadowDepth,
                            startAlpha = (0.42f * (0.4f + 0.6f * liftFactor)),
                            endAlpha = 0f,
                            boundsW = w,
                            boundsH = h
                        )
                        nc.restore()
                    }

                    // 2. Draw flat remaining portion of the front page
                    if (flatFrontPoly.size >= 3) {
                        val frontClipPath = flatFrontPoly.toAndroidPath()
                        nc.save()
                        nc.clipPath(frontClipPath)
                        drawNativeBitmapFitted(nc, activeFront, w, h, colorFilter)

                        // Ambient occlusion drop shadow cast by the curled flap onto the flat front page
                        if (backFacePoly.size >= 3) {
                            val flapShadowDepth = (22.dp.toPx() + 42.dp.toPx() * liftFactor)
                            drawCreaseDirectionalShadow(
                                canvas = nc,
                                originX = midX,
                                originY = midY,
                                dirX = nx,
                                dirY = ny,
                                span = flapShadowDepth,
                                startAlpha = (0.36f * (0.35f + 0.65f * liftFactor)),
                                endAlpha = 0f,
                                boundsW = w,
                                boundsH = h
                            )
                        }
                        nc.restore()
                    }

                    // 3. Draw the curled-over back face of the turning sheet
                    if (backFacePoly.size >= 3) {
                        val backFacePath = backFacePoly.toAndroidPath()
                        nc.save()
                        nc.clipPath(backFacePath)

                        // Translucent warm vellum paper base
                        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = backPaperTint
                            style = Paint.Style.FILL
                        }
                        nc.drawPath(backFacePath, paperPaint)

                        // Reflected front-page ink bleed-through (mirror reflection matrix across fold line)
                        val dot = midX * nx + midY * ny
                        val reflectMatrix = Matrix().apply {
                            setValues(
                                floatArrayOf(
                                    1f - 2f * nx * nx, -2f * nx * ny, 2f * nx * dot,
                                    -2f * nx * ny, 1f - 2f * ny * ny, 2f * ny * dot,
                                    0f, 0f, 1f
                                )
                            )
                        }
                        nc.save()
                        nc.concat(reflectMatrix)
                        val bleedPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                            alpha = if (surfaceTone == ReaderSurfaceTone.NIGHT) 38 else 46
                        }
                        val androidBmp = activeFront.asAndroidBitmap()
                        nc.drawBitmap(
                            androidBmp,
                            Rect(0, 0, androidBmp.width, androidBmp.height),
                            Rect(0, 0, w.toInt(), h.toInt()),
                            bleedPaint
                        )
                        nc.restore()

                        // Cylindrical specular ridge highlight + crease valley shadow across curled flap
                        val curlCylinderWidth = (44.dp.toPx() + 68.dp.toPx() * liftFactor)
                        drawCylinderCurlHighlight(
                            canvas = nc,
                            originX = midX,
                            originY = midY,
                            dirX = nx,
                            dirY = ny,
                            span = curlCylinderWidth,
                            liftFactor = liftFactor,
                            boundsW = w,
                            boundsH = h
                        )

                        // Crisp subtle edge rim along curled flap border
                        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f
                            color = android.graphics.Color.argb(65, 110, 92, 70)
                        }
                        nc.drawPath(backFacePath, rimPaint)
                        nc.restore()
                    }
                }

                drawSpineBindingShadow(w, h)
            }
        }
    }
}

private fun clampPullPointToSpine(
    rawX: Float,
    rawY: Float,
    width: Float,
    height: Float,
    cornerY: Float
): Point2D {
    var px = rawX
    var py = rawY

    // Constraint 1: distance from spine pivot (0, cornerY) <= width
    val dPivotX = px - 0f
    val dPivotY = py - cornerY
    val distPivot = hypot(dPivotX, dPivotY)
    if (distPivot > width && distPivot > 0.001f) {
        val scale = width / distPivot
        px = dPivotX * scale
        py = cornerY + dPivotY * scale
    }

    // Constraint 2: distance from opposite spine corner (0, height - cornerY) <= diagonal
    val oppY = height - cornerY
    val maxDiag = hypot(width, height)
    val dOppX = px - 0f
    val dOppY = py - oppY
    val distOpp = hypot(dOppX, dOppY)
    if (distOpp > maxDiag && distOpp > 0.001f) {
        val scale = maxDiag / distOpp
        px = dOppX * scale
        py = oppY + dOppY * scale
    }

    return Point2D(px, py)
}

private fun clipPolygonHalfPlane(
    polygon: List<Point2D>,
    mx: Float,
    my: Float,
    nx: Float,
    ny: Float,
    keepPositive: Boolean
): List<Point2D> {
    if (polygon.isEmpty()) return emptyList()
    val sign = if (keepPositive) 1f else -1f
    val output = ArrayList<Point2D>(polygon.size + 2)

    fun eval(p: Point2D): Float = ((p.x - mx) * nx + (p.y - my) * ny) * sign

    var prev = polygon.last()
    var prevVal = eval(prev)

    for (curr in polygon) {
        val currVal = eval(curr)
        val currInside = currVal >= -0.01f
        val prevInside = prevVal >= -0.01f

        if (currInside != prevInside) {
            val denom = prevVal - currVal
            val t = if (abs(denom) > 1e-5f) (prevVal / denom).coerceIn(0f, 1f) else 0.5f
            output.add(
                Point2D(
                    x = prev.x + t * (curr.x - prev.x),
                    y = prev.y + t * (curr.y - prev.y)
                )
            )
        }
        if (currInside) {
            output.add(curr)
        }
        prev = curr
        prevVal = currVal
    }
    return output
}

private fun List<Point2D>.toAndroidPath(): Path {
    val path = Path()
    if (isEmpty()) return path
    path.moveTo(this[0].x, this[0].y)
    for (i in 1 until size) {
        path.lineTo(this[i].x, this[i].y)
    }
    path.close()
    return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageFitted(
    bitmap: ImageBitmap,
    w: Float,
    h: Float,
    colorFilter: ColorFilter?
) {
    drawImage(
        image = bitmap,
        dstSize = IntSize(w.toInt(), h.toInt()),
        colorFilter = colorFilter
    )
}

private fun drawNativeBitmapFitted(
    canvas: android.graphics.Canvas,
    bitmap: ImageBitmap,
    w: Float,
    h: Float,
    colorFilter: ColorFilter?
) {
    val bmp = bitmap.asAndroidBitmap()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.colorFilter = colorFilter?.asAndroidColorFilter()
    }
    canvas.drawBitmap(
        bmp,
        Rect(0, 0, bmp.width, bmp.height),
        Rect(0, 0, w.toInt(), h.toInt()),
        paint
    )
}

private fun drawCreaseDirectionalShadow(
    canvas: android.graphics.Canvas,
    originX: Float,
    originY: Float,
    dirX: Float,
    dirY: Float,
    span: Float,
    startAlpha: Float,
    endAlpha: Float,
    boundsW: Float,
    boundsH: Float
) {
    val endX = originX + dirX * span
    val endY = originY + dirY * span
    val c0 = android.graphics.Color.argb((startAlpha.coerceIn(0f, 1f) * 255).toInt(), 20, 14, 10)
    val cMid = android.graphics.Color.argb(((startAlpha * 0.35f).coerceIn(0f, 1f) * 255).toInt(), 20, 14, 10)
    val c1 = android.graphics.Color.argb((endAlpha.coerceIn(0f, 1f) * 255).toInt(), 20, 14, 10)

    val shader = android.graphics.LinearGradient(
        originX,
        originY,
        endX,
        endY,
        intArrayOf(c0, cMid, c1),
        floatArrayOf(0f, 0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP
    )
    val paint = Paint().apply { this.shader = shader }
    canvas.drawRect(0f, 0f, boundsW, boundsH, paint)
}

private fun drawCylinderCurlHighlight(
    canvas: android.graphics.Canvas,
    originX: Float,
    originY: Float,
    dirX: Float,
    dirY: Float,
    span: Float,
    liftFactor: Float,
    boundsW: Float,
    boundsH: Float
) {
    val endX = originX + dirX * span
    val endY = originY + dirY * span

    val valleyShadow = android.graphics.Color.argb((65 + (45 * liftFactor)).toInt(), 30, 20, 12)
    val softMid = android.graphics.Color.argb(12, 60, 45, 30)
    val crestHighlight = android.graphics.Color.argb((115 + (60 * liftFactor)).toInt(), 255, 250, 240)
    val fadeClear = android.graphics.Color.argb(0, 255, 250, 240)

    val shader = android.graphics.LinearGradient(
        originX,
        originY,
        endX,
        endY,
        intArrayOf(valleyShadow, softMid, crestHighlight, fadeClear),
        floatArrayOf(0f, 0.16f, 0.42f, 1f),
        android.graphics.Shader.TileMode.CLAMP
    )
    val paint = Paint().apply { this.shader = shader }
    canvas.drawRect(0f, 0f, boundsW, boundsH, paint)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpineBindingShadow(w: Float, h: Float) {
    val spineWidth = 14.dp.toPx().coerceAtMost(w * 0.06f)
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.16f),
                Color.Black.copy(alpha = 0.05f),
                Color.Transparent
            ),
            startX = 0f,
            endX = spineWidth
        ),
        size = androidx.compose.ui.geometry.Size(spineWidth, h)
    )
}
