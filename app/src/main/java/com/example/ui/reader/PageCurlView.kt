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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.local.ReaderSurfaceTone
import kotlinx.coroutines.CoroutineStart
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
    pageAspectRatio: Float,
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

    val latestPageIndex by rememberUpdatedState(currentPageIndex)
    val latestPageCount by rememberUpdatedState(pageCount)
    val latestAspectRatio by rememberUpdatedState(pageAspectRatio.coerceIn(0.4f, 1.8f))
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)

    // Interactive curl state (0f = current page flat, 1f = target page turned completely into view)
    var turnDirection by remember { mutableStateOf(TurnDirection.NONE) }
    var cornerAnchorY by remember { mutableFloatStateOf(0f) }

    val curlProgress = remember { Animatable(0f) }
    val curlYRatio = remember { Animatable(0.86f) }

    // Full-width landscape vertical scroll & pinch/double-tap zoom state
    var scrollY by remember { mutableFloatStateOf(0f) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    fun clampZoomAndPan(
        candidateScale: Float,
        candidatePan: Offset,
        candidateScrollY: Float
    ): Triple<Float, Offset, Float> {
        val w = viewportSize.width.toFloat().coerceAtLeast(1f)
        val h = viewportSize.height.toFloat().coerceAtLeast(1f)
        val aspect = latestAspectRatio
        val isLandscapeFitWidth = w > h && (w / aspect) > h + 2f
        val contentH = if (isLandscapeFitWidth) (w / aspect) else h
        val minScrollY = -(contentH - h).coerceAtLeast(0f)

        val clampedScale = candidateScale.coerceIn(1f, 4.5f)
        if (clampedScale <= 1.02f) {
            // Fold any residual Y pan back into scrollY so returning to 1x keeps reading position
            val mergedScroll = (candidateScrollY + candidatePan.y).coerceIn(minScrollY, 0f)
            return Triple(1f, Offset.Zero, mergedScroll)
        }

        val clampedScroll = candidateScrollY.coerceIn(minScrollY, 0f)
        val maxPanX = (w * (clampedScale - 1f)) / 2f
        val maxPanY = ((h * 0.5f - clampedScroll) * clampedScale - h * 0.5f).coerceAtLeast(0f)
        val minPanY = (h * 0.5f - (clampedScroll + contentH - h * 0.5f) * clampedScale).coerceAtMost(0f)

        val clampedPan = Offset(
            x = candidatePan.x.coerceIn(-maxPanX, maxPanX),
            y = candidatePan.y.coerceIn(minPanY, maxPanY)
        )
        return Triple(clampedScale, clampedPan, clampedScroll)
    }

    // Reset zoom & vertical page scroll when moving to a different page
    LaunchedEffect(currentPageIndex) {
        zoomScale = 1f
        panOffset = Offset.Zero
        scrollY = 0f
    }

    // Re-clamp offsets whenever orientation or viewport dimensions change
    LaunchedEffect(viewportSize, pageAspectRatio) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val (s, p, sy) = clampZoomAndPan(zoomScale, panOffset, scrollY)
            zoomScale = s
            panOffset = p
            scrollY = sy
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

    fun triggerAnimatedTurn(direction: TurnDirection) {
        val idx = latestPageIndex
        val total = latestPageCount
        if (viewportSize.width <= 0 || turnDirection != TurnDirection.NONE) return
        if (direction == TurnDirection.FORWARD && idx >= total - 1) return
        if (direction == TurnDirection.BACKWARD && idx <= 0) return

        val h = viewportSize.height.toFloat()
        turnDirection = direction
        cornerAnchorY = h

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            curlProgress.snapTo(0.02f)
            curlYRatio.snapTo(0.88f)
            val yAnim = launch {
                curlYRatio.animateTo(
                    targetValue = 0.68f,
                    animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
                )
            }
            curlProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 430, easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f))
            )
            yAnim.cancel()
            val nextIdx = if (direction == TurnDirection.FORWARD) idx + 1 else idx - 1
            latestOnPageChanged(nextIdx)
            turnDirection = TurnDirection.NONE
            curlProgress.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackdropColor)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            // Primary multi-touch pinch-to-zoom, zoomed pan, landscape vertical scroll, and single-finger page curl
            .pointerInput(viewportSize) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var dragOrigin = firstDown.position
                    var activeDragDir = TurnDirection.NONE
                    var isVerticalScrolling = false
                    var isZoomPanning = false
                    var isPinching = false
                    var edgeStartMode = false
                    var smoothVx = 0f
                    var dragProgress = 0f
                    var passedSlop = false

                    fun mapDragProgress(dir: TurnDirection, posX: Float, w: Float): Float {
                        return when (dir) {
                            TurnDirection.FORWARD -> {
                                if (edgeStartMode) {
                                    ((w - posX) / w).coerceIn(0.005f, 0.995f)
                                } else {
                                    val span = dragOrigin.x.coerceAtLeast(w * 0.45f)
                                    ((dragOrigin.x - posX) / span).coerceIn(0.005f, 0.995f)
                                }
                            }
                            TurnDirection.BACKWARD -> {
                                if (edgeStartMode) {
                                    (posX / w).coerceIn(0.005f, 0.995f)
                                } else {
                                    val span = (w - dragOrigin.x).coerceAtLeast(w * 0.45f)
                                    ((posX - dragOrigin.x) / span).coerceIn(0.005f, 0.995f)
                                }
                            }
                            TurnDirection.NONE -> 0f
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }

                        if (pressedChanges.isEmpty()) {
                            if (!isPinching && activeDragDir != TurnDirection.NONE) {
                                val dir = activeDragDir
                                val finalProgress = dragProgress
                                val vx = smoothVx
                                activeDragDir = TurnDirection.NONE

                                val shouldComplete = when (dir) {
                                    TurnDirection.FORWARD -> finalProgress > 0.28f || vx < -6f
                                    TurnDirection.BACKWARD -> finalProgress > 0.28f || vx > 6f
                                    TurnDirection.NONE -> false
                                }

                                val idx = latestPageIndex
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    curlProgress.snapTo(finalProgress)
                                    val target = if (shouldComplete) 1f else 0f
                                    val settleSpec = spring(
                                        dampingRatio = 0.90f,
                                        stiffness = if (shouldComplete) Spring.StiffnessMediumLow else Spring.StiffnessMedium,
                                        visibilityThreshold = 0.001f
                                    )
                                    val isTopCorner = cornerAnchorY < viewportSize.height * 0.5f
                                    val yTarget = when {
                                        isTopCorner && shouldComplete -> 0.22f
                                        isTopCorner -> 0.10f
                                        shouldComplete -> 0.78f
                                        else -> 0.90f
                                    }
                                    val yAnim = launch {
                                        curlYRatio.animateTo(
                                            targetValue = yTarget,
                                            animationSpec = tween(durationMillis = 280)
                                        )
                                    }
                                    curlProgress.animateTo(targetValue = target, animationSpec = settleSpec)
                                    yAnim.cancel()

                                    if (shouldComplete) {
                                        val nextIdx = if (dir == TurnDirection.FORWARD) idx + 1 else idx - 1
                                        latestOnPageChanged(nextIdx)
                                    }
                                    turnDirection = TurnDirection.NONE
                                    curlProgress.snapTo(0f)
                                }
                            }
                            break
                        }

                        // Handle 2+ fingers: standard image pinch-to-zoom around finger centroid + pan
                        if (pressedChanges.size >= 2) {
                            if (!isPinching) {
                                isPinching = true
                                passedSlop = true
                                isVerticalScrolling = false
                                isZoomPanning = false
                                if (activeDragDir != TurnDirection.NONE) {
                                    activeDragDir = TurnDirection.NONE
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        curlProgress.snapTo(0f)
                                        turnDirection = TurnDirection.NONE
                                    }
                                }
                            }

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid != Offset.Unspecified) {
                                val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                                val h = viewportSize.height.toFloat().coerceAtLeast(1f)
                                val center = Offset(w / 2f, h / 2f)
                                val oldZoom = zoomScale
                                val newZoom = (oldZoom * zoomChange).coerceIn(1f, 4.5f)
                                val zoomFactor = if (oldZoom > 0f) newZoom / oldZoom else 1f
                                val c = centroid - center
                                val rawPan = panOffset * zoomFactor + panChange + c * (1f - zoomFactor)

                                val (clampedZoom, clampedPan, clampedScroll) = clampZoomAndPan(
                                    candidateScale = newZoom,
                                    candidatePan = rawPan,
                                    candidateScrollY = scrollY
                                )
                                zoomScale = clampedZoom
                                panOffset = clampedPan
                                scrollY = clampedScroll
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // Ignore trailing single-finger lift right after pinching so it doesn't turn a page
                        if (isPinching) {
                            continue
                        }

                        val change = pressedChanges.first()
                        val delta = change.positionChange()
                        val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                        val h = viewportSize.height.toFloat().coerceAtLeast(1f)
                        val aspect = latestAspectRatio
                        val isLandscapeFitWidth = w > h && (w / aspect) > h + 2f

                        if (!passedSlop) {
                            val totalMove = (change.position - firstDown.position).getDistance()
                            if (totalMove < touchSlop) {
                                continue
                            }
                            passedSlop = true

                            if (zoomScale > 1.02f) {
                                isZoomPanning = true
                            } else {
                                val dxTotal = change.position.x - firstDown.position.x
                                val dyTotal = change.position.y - firstDown.position.y
                                val inSideMargin = firstDown.position.x < w * 0.14f || firstDown.position.x > w * 0.86f

                                if (isLandscapeFitWidth && !inSideMargin && abs(dyTotal) > abs(dxTotal) * 1.1f) {
                                    isVerticalScrolling = true
                                } else if (turnDirection == TurnDirection.NONE) {
                                    val idx = latestPageIndex
                                    val total = latestPageCount
                                    val detectedDir = when {
                                        firstDown.position.x < w * 0.16f && idx > 0 -> {
                                            edgeStartMode = true
                                            TurnDirection.BACKWARD
                                        }
                                        firstDown.position.x > w * 0.84f && idx < total - 1 -> {
                                            edgeStartMode = true
                                            TurnDirection.FORWARD
                                        }
                                        dxTotal < -4f && idx < total - 1 -> {
                                            edgeStartMode = false
                                            dragOrigin = change.position
                                            TurnDirection.FORWARD
                                        }
                                        dxTotal > 4f && idx > 0 -> {
                                            edgeStartMode = false
                                            dragOrigin = change.position
                                            TurnDirection.BACKWARD
                                        }
                                        else -> TurnDirection.NONE
                                    }

                                    if (detectedDir != TurnDirection.NONE) {
                                        activeDragDir = detectedDir
                                        turnDirection = detectedDir
                                        cornerAnchorY = if (firstDown.position.y < h * 0.42f) 0f else h
                                        smoothVx = delta.x
                                    }
                                }
                            }
                        }

                        if (isZoomPanning) {
                            change.consume()
                            val (_, clampedPan, clampedScroll) = clampZoomAndPan(
                                candidateScale = zoomScale,
                                candidatePan = panOffset + delta,
                                candidateScrollY = scrollY
                            )
                            panOffset = clampedPan
                            scrollY = clampedScroll
                        } else if (isVerticalScrolling) {
                            change.consume()
                            val (_, _, clampedScroll) = clampZoomAndPan(
                                candidateScale = 1f,
                                candidatePan = Offset.Zero,
                                candidateScrollY = scrollY + delta.y
                            )
                            scrollY = clampedScroll
                        } else if (activeDragDir != TurnDirection.NONE) {
                            change.consume()
                            smoothVx = smoothVx * 0.55f + delta.x * 0.45f
                            dragProgress = mapDragProgress(activeDragDir, change.position.x, w)
                            val yRatio = (change.position.y / h).coerceIn(0.06f, 0.94f)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                curlProgress.snapTo(dragProgress)
                                curlYRatio.snapTo(yRatio)
                            }
                        }
                    }
                }
            }
            // Secondary tap/double-tap detector (only fires when pointer wasn't consumed by pinch/drag)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (zoomScale > 1.05f) {
                            val (s, p, sy) = clampZoomAndPan(1f, panOffset, scrollY)
                            zoomScale = s
                            panOffset = p
                            scrollY = sy
                        } else {
                            val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                            val h = viewportSize.height.toFloat().coerceAtLeast(1f)
                            val targetZoom = 2.2f
                            val cx = w / 2f
                            val cy = h / 2f
                            val rawPan = Offset(
                                x = (cx - tapOffset.x) * (targetZoom - 1f),
                                y = (cy - tapOffset.y) * (targetZoom - 1f)
                            )
                            val (s, p, sy) = clampZoomAndPan(targetZoom, rawPan, scrollY)
                            zoomScale = s
                            panOffset = p
                            scrollY = sy
                        }
                    },
                    onTap = { tapOffset ->
                        if (zoomScale > 1.05f) {
                            latestOnCenterTap()
                            return@detectTapGestures
                        }
                        val w = viewportSize.width.toFloat().coerceAtLeast(1f)
                        val relX = tapOffset.x / w
                        when {
                            relX > 0.72f -> triggerAnimatedTurn(TurnDirection.FORWARD)
                            relX < 0.28f -> triggerAnimatedTurn(TurnDirection.BACKWARD)
                            else -> latestOnCenterTap()
                        }
                    }
                )
            }
    ) {
        if (currentBitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            val flatSheet: ImageBitmap = currentBitmap
            val revealedSheet: ImageBitmap? = when (turnDirection) {
                TurnDirection.BACKWARD -> prevBitmap
                TurnDirection.FORWARD -> nextBitmap
                TurnDirection.NONE -> null
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

                val aspect = latestAspectRatio
                val isLandscapeFitWidth = w > h && (w / aspect) > h + 2f
                val contentH = if (isLandscapeFitWidth) (w / aspect) else h
                val activeScrollY = if (isLandscapeFitWidth) scrollY else 0f

                val effectiveProgress = curlProgress.value.coerceIn(0f, 1f)
                val effectiveY = (curlYRatio.value * h).coerceIn(0f, h)

                if (turnDirection == TurnDirection.NONE || effectiveProgress <= 0.003f || revealedSheet == null) {
                    drawImageFitted(flatSheet, w, contentH, activeScrollY, colorFilter)
                    drawSpineBindingShadow(w, h)
                    return@Canvas
                }

                if (effectiveProgress >= 0.997f) {
                    drawImageFitted(revealedSheet, w, contentH, activeScrollY, colorFilter)
                    drawSpineBindingShadow(w, h)
                    return@Canvas
                }

                val isBackward = turnDirection == TurnDirection.BACKWARD
                val corner = Point2D(if (isBackward) 0f else w, cornerAnchorY)
                val pivotX = if (isBackward) w else 0f

                val rawPullX = if (isBackward) {
                    w * (2f * effectiveProgress)
                } else {
                    w * (1f - 2f * effectiveProgress)
                }
                val rawPullY = if (abs(effectiveY - cornerAnchorY) < 4f) {
                    if (cornerAnchorY >= h * 0.5f) h - 6f else 6f
                } else {
                    effectiveY
                }

                val clampedPull = clampPullPointToEdge(
                    rawX = rawPullX,
                    rawY = rawPullY,
                    width = w,
                    height = h,
                    pivotX = pivotX,
                    cornerY = cornerAnchorY
                )

                val dx = clampedPull.x - corner.x
                val dy = clampedPull.y - corner.y
                val dist = hypot(dx, dy)

                if (dist < 2f) {
                    drawImageFitted(flatSheet, w, contentH, activeScrollY, colorFilter)
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

                val flatFrontPoly = clipPolygonHalfPlane(pageRect, midX, midY, nx, ny, keepPositive = true)
                val exposedUnderPoly = clipPolygonHalfPlane(pageRect, midX, midY, nx, ny, keepPositive = false)
                val backFacePoly = exposedUnderPoly.map { pt ->
                    val signed = (pt.x - midX) * nx + (pt.y - midY) * ny
                    Point2D(pt.x - 2f * signed * nx, pt.y - 2f * signed * ny)
                }

                val liftFactor = sin(PI * effectiveProgress).toFloat().coerceIn(0f, 1f)
                val bleedSource = if (isBackward) revealedSheet else flatSheet

                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas

                    // 1. Draw revealed page (next on forward, prev on backward)
                    if (exposedUnderPoly.size >= 3) {
                        val underClipPath = exposedUnderPoly.toAndroidPath()
                        nc.save()
                        nc.clipPath(underClipPath)
                        drawNativeBitmapFitted(nc, revealedSheet, w, contentH, activeScrollY, colorFilter)

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

                    // 2. Draw flat remaining portion of current page
                    if (flatFrontPoly.size >= 3) {
                        val frontClipPath = flatFrontPoly.toAndroidPath()
                        nc.save()
                        nc.clipPath(frontClipPath)
                        drawNativeBitmapFitted(nc, flatSheet, w, contentH, activeScrollY, colorFilter)

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

                        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = backPaperTint
                            style = Paint.Style.FILL
                        }
                        nc.drawPath(backFacePath, paperPaint)

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
                        val androidBmp = bleedSource.asAndroidBitmap()
                        nc.drawBitmap(
                            androidBmp,
                            Rect(0, 0, androidBmp.width, androidBmp.height),
                            Rect(0, activeScrollY.toInt(), w.toInt(), (activeScrollY + contentH).toInt()),
                            bleedPaint
                        )
                        nc.restore()

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

private fun clampPullPointToEdge(
    rawX: Float,
    rawY: Float,
    width: Float,
    height: Float,
    pivotX: Float,
    cornerY: Float
): Point2D {
    var px = rawX
    var py = rawY

    val dPivotX = px - pivotX
    val dPivotY = py - cornerY
    val distPivot = hypot(dPivotX, dPivotY)
    if (distPivot > width && distPivot > 0.001f) {
        val scale = width / distPivot
        px = pivotX + dPivotX * scale
        py = cornerY + dPivotY * scale
    }

    val oppY = height - cornerY
    val maxDiag = hypot(width, height)
    val dOppX = px - pivotX
    val dOppY = py - oppY
    val distOpp = hypot(dOppX, dOppY)
    if (distOpp > maxDiag && distOpp > 0.001f) {
        val scale = maxDiag / distOpp
        px = pivotX + dOppX * scale
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
    contentH: Float,
    scrollY: Float,
    colorFilter: ColorFilter?
) {
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(0, scrollY.toInt()),
        dstSize = IntSize(w.toInt(), contentH.toInt()),
        colorFilter = colorFilter
    )
}

private fun drawNativeBitmapFitted(
    canvas: android.graphics.Canvas,
    bitmap: ImageBitmap,
    w: Float,
    contentH: Float,
    scrollY: Float,
    colorFilter: ColorFilter?
) {
    val bmp = bitmap.asAndroidBitmap()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.colorFilter = colorFilter?.asAndroidColorFilter()
    }
    canvas.drawBitmap(
        bmp,
        Rect(0, 0, bmp.width, bmp.height),
        Rect(0, scrollY.toInt(), w.toInt(), (scrollY + contentH).toInt()),
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
