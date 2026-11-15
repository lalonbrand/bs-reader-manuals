package com.example.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ReaderSurfaceTone
import com.example.ui.theme.PearlWhite
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.glassPanel
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showChrome by rememberSaveable { mutableStateOf(true) }
    var showCurlHint by rememberSaveable { mutableStateOf(true) }
    var showPageGrid by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Soft real paper turn sound player
    val soundScope = rememberCoroutineScope()
    val soundPlayer = remember(context) { PageTurnSoundPlayer(context, soundScope) }
    DisposableEffect(soundPlayer) {
        onDispose { soundPlayer.release() }
    }

    var lastObservedPage by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(uiState.currentPageIndex, uiState.isLoading, uiState.pageFiles.size) {
        if (uiState.isLoading || uiState.pageFiles.isEmpty()) return@LaunchedEffect
        val curr = uiState.currentPageIndex
        if (lastObservedPage == -1) {
            lastObservedPage = curr
        } else if (curr != lastObservedPage) {
            if (uiState.pageSoundEnabled) {
                soundPlayer.play(isForward = curr > lastObservedPage)
            }
            lastObservedPage = curr
        }
    }

    // Immersive full-screen mode when reading in landscape
    LaunchedEffect(isLandscape, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Restore default orientation and system bars when exiting the reader
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val toggleOrientation = {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    val deskColor = when (uiState.surfaceTone) {
        ReaderSurfaceTone.PAPER -> Color(0xFFE5DDD0)
        ReaderSurfaceTone.SEPIA -> Color(0xFFD6C7A7)
        ReaderSurfaceTone.NIGHT -> Color(0xFF0D1128)
    }

    val chromeBackdrop = Color(0xDE13193A)
    val chromeOnSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(deskColor)
    ) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = uiState.errorMessage!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = chromeOnSurface
                    )
                    Button(onClick = onNavigateBack) {
                        Text("Back to Bookshelf")
                    }
                }
            }

            else -> {
                // In landscape, occupy 100% full screen edge-to-edge; in portrait, center the codex sheet
                BoxWithConstraints(
                    modifier = if (isLandscape) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    val containerRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 0.7f
                    val sheetModifier = when {
                        isLandscape -> Modifier.fillMaxSize()
                        containerRatio > uiState.pageAspectRatio -> Modifier
                            .fillMaxHeight()
                            .aspectRatio(uiState.pageAspectRatio)
                        else -> Modifier
                            .fillMaxWidth()
                            .aspectRatio(uiState.pageAspectRatio)
                    }

                    val framedModifier = if (isLandscape) {
                        sheetModifier
                    } else {
                        sheetModifier.shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(4.dp),
                            ambientColor = Color.Black.copy(alpha = 0.35f),
                            spotColor = Color.Black.copy(alpha = 0.45f)
                        )
                    }

                    Box(modifier = framedModifier) {
                        PageCurlView(
                            currentPageIndex = uiState.currentPageIndex,
                            pageCount = uiState.pageFiles.size,
                            pageAspectRatio = uiState.pageAspectRatio,
                            currentBitmap = uiState.currentBitmap,
                            nextBitmap = uiState.nextBitmap,
                            prevBitmap = uiState.prevBitmap,
                            surfaceTone = uiState.surfaceTone,
                            onPageChanged = { newIdx ->
                                showCurlHint = false
                                showChrome = false
                                viewModel.goToPage(newIdx)
                            },
                            onCenterTap = {
                                showCurlHint = false
                                showChrome = !showChrome
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Subtle bottom corner interaction hint before first turn
                AnimatedVisibility(
                    visible = showCurlHint && !showChrome && uiState.pageFiles.size > 1,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = chromeBackdrop,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                    ) {
                        Text(
                            text = "Swipe to curl • Pinch to zoom",
                            style = MaterialTheme.typography.labelSmall,
                            color = chromeOnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Persistent subtle telemetry pill when chrome is hidden
                if (!showChrome && uiState.showPageOverlay) {
                    val activeFile = uiState.pageFiles.getOrNull(uiState.currentPageIndex)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(50),
                        color = chromeBackdrop.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                    ) {
                        Text(
                            text = "${uiState.currentPageIndex + 1} / ${uiState.pageFiles.size}" +
                                if (activeFile != null) " • ${activeFile.name}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = chromeOnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }

                // Floating Cosmic Glass Top Bar
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chromeBackdrop)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                            ) {
                                IconButton(
                                    onClick = onNavigateBack,
                                    modifier = Modifier
                                        .testTag("reader_back_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back),
                                        tint = chromeOnSurface
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = uiState.book?.title ?: "Codex",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = chromeOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Page ${uiState.currentPageIndex + 1} of ${uiState.pageFiles.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                            ) {
                                IconButton(
                                    onClick = { showPageGrid = true },
                                    modifier = Modifier
                                        .testTag("page_grid_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = stringResource(R.string.cd_page_grid),
                                        tint = chromeOnSurface
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                            ) {
                                IconButton(
                                    onClick = { showAppearanceSheet = true },
                                    modifier = Modifier
                                        .testTag("reader_settings_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = stringResource(R.string.cd_reader_settings),
                                        tint = chromeOnSurface
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                    }
                }

                // Floating Premium Cosmic-Glass Bottom Page Dock
                AnimatedVisibility(
                    visible = showChrome && uiState.pageFiles.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    val totalPages = uiState.pageFiles.size
                    val lastIdx = uiState.pageFiles.lastIndex.coerceAtLeast(1)
                    var sliderPos by remember(uiState.currentPageIndex) {
                        mutableFloatStateOf(uiState.currentPageIndex.toFloat())
                    }
                    val displayPageNum = (sliderPos.roundToInt() + 1).coerceIn(1, totalPages)
                    val progressPct = ((displayPageNum.toFloat() / totalPages.toFloat()) * 100f)
                        .roundToInt()
                        .coerceIn(0, 100)
                    val dockShape = RoundedCornerShape(26.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 580.dp)
                            .shadow(
                                elevation = 18.dp,
                                shape = dockShape,
                                ambientColor = Color.Black.copy(alpha = 0.45f),
                                spotColor = Color.Black.copy(alpha = 0.60f)
                            )
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xEB222A56),
                                        Color(0xEB131836)
                                    )
                                ),
                                shape = dockShape
                            )
                            .glassPanel(shape = dockShape)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Top pill row: Page counter badge + quick sound & rotation pills
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.11f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(SoftCyan, CircleShape)
                                        )
                                        Text(
                                            text = "Page $displayPageNum / $totalPages",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = PearlWhite
                                        )
                                        Text(
                                            text = "• $progressPct%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (uiState.pageSoundEnabled) {
                                            Color.White.copy(alpha = 0.14f)
                                        } else {
                                            Color.White.copy(alpha = 0.06f)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (uiState.pageSoundEnabled) {
                                                SoftCyan.copy(alpha = 0.45f)
                                            } else {
                                                Color.White.copy(alpha = 0.16f)
                                            }
                                        ),
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .testTag("quick_sound_toggle_button")
                                            .minimumInteractiveComponentSize()
                                            .clickable {
                                                viewModel.setPageSoundEnabled(!uiState.pageSoundEnabled)
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.size(36.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.pageSoundEnabled) {
                                                    Icons.AutoMirrored.Filled.VolumeUp
                                                } else {
                                                    Icons.AutoMirrored.Filled.VolumeOff
                                                },
                                                contentDescription = if (uiState.pageSoundEnabled) {
                                                    "Mute page turn sound"
                                                } else {
                                                    "Enable page turn sound"
                                                },
                                                tint = if (uiState.pageSoundEnabled) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.White.copy(alpha = 0.13f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .testTag("rotation_toggle_button")
                                            .minimumInteractiveComponentSize()
                                            .clickable(onClick = toggleOrientation)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ScreenRotation,
                                                contentDescription = stringResource(R.string.cd_toggle_rotation),
                                                tint = PearlWhite,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = if (isLandscape) {
                                                    stringResource(R.string.action_rotate_portrait)
                                                } else {
                                                    stringResource(R.string.action_rotate_landscape)
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                color = PearlWhite
                                            )
                                        }
                                    }
                                }
                            }

                            // Sleek continuous page scrubber with frosted circular step controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val canGoPrev = uiState.currentPageIndex > 0
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .testTag("prev_page_button")
                                        .clickable(enabled = canGoPrev) {
                                            viewModel.goToPage(uiState.currentPageIndex - 1)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(
                                                color = Color.White.copy(alpha = if (canGoPrev) 0.14f else 0.05f),
                                                shape = CircleShape
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = Color.White.copy(alpha = if (canGoPrev) 0.26f else 0.10f),
                                                shape = CircleShape
                                            )
                                            .alpha(if (canGoPrev) 1f else 0.4f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "Previous page",
                                            tint = PearlWhite,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Slider(
                                    value = sliderPos,
                                    onValueChange = { sliderPos = it },
                                    onValueChangeFinished = {
                                        viewModel.goToPage(sliderPos.roundToInt())
                                    },
                                    enabled = totalPages > 1,
                                    valueRange = 0f..lastIdx.toFloat(),
                                    steps = 0,
                                    colors = SliderDefaults.colors(
                                        thumbColor = PearlWhite,
                                        activeTrackColor = SoftCyan,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                                        activeTickColor = Color.Transparent,
                                        inactiveTickColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("page_scrubber_slider")
                                )

                                val canGoNext = uiState.currentPageIndex < uiState.pageFiles.lastIndex
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .testTag("next_page_button")
                                        .clickable(enabled = canGoNext) {
                                            viewModel.goToPage(uiState.currentPageIndex + 1)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(
                                                color = Color.White.copy(alpha = if (canGoNext) 0.92f else 0.08f),
                                                shape = CircleShape
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = Color.White.copy(alpha = if (canGoNext) 0.35f else 0.10f),
                                                shape = CircleShape
                                            )
                                            .alpha(if (canGoNext) 1f else 0.4f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Next page",
                                            tint = if (canGoNext) Color(0xFF12162E) else PearlWhite,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPageGrid) {
        PageGridBottomSheet(
            pageFiles = uiState.pageFiles,
            currentPageIndex = uiState.currentPageIndex,
            pageAspectRatio = uiState.pageAspectRatio,
            loadThumbnail = viewModel::loadThumbnail,
            onSelectPage = viewModel::goToPage,
            onDismiss = { showPageGrid = false }
        )
    }

    if (showAppearanceSheet) {
        ReaderAppearanceBottomSheet(
            book = uiState.book,
            surfaceTone = uiState.surfaceTone,
            showPageOverlay = uiState.showPageOverlay,
            pageSoundEnabled = uiState.pageSoundEnabled,
            currentPageFile = uiState.pageFiles.getOrNull(uiState.currentPageIndex),
            onSelectTone = viewModel::setSurfaceTone,
            onTogglePageOverlay = viewModel::setShowPageOverlay,
            onTogglePageSound = viewModel::setPageSoundEnabled,
            onDismiss = { showAppearanceSheet = false }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
