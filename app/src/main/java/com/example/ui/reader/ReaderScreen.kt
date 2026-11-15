package com.example.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ReaderSurfaceTone
import com.example.ui.bookshelf.formatBytes
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showChrome by remember { mutableStateOf(true) }
    var showCurlHint by remember { mutableStateOf(true) }
    var showPageGrid by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }

    val deskColor = when (uiState.surfaceTone) {
        ReaderSurfaceTone.PAPER -> Color(0xFFE5DDD0)
        ReaderSurfaceTone.SEPIA -> Color(0xFFD6C7A7)
        ReaderSurfaceTone.NIGHT -> Color(0xFF0D1128)
    }

    val chromeSurface = Color(0xEB181F44)
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
                // Aspect-ratio-preserving codex sheet centered on reading desk
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val containerRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 0.7f
                    val sheetModifier = if (containerRatio > uiState.pageAspectRatio) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(uiState.pageAspectRatio)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(uiState.pageAspectRatio)
                    }

                    Box(
                        modifier = sheetModifier
                            .shadow(
                                elevation = 14.dp,
                                shape = RoundedCornerShape(4.dp),
                                ambientColor = Color.Black.copy(alpha = 0.35f),
                                spotColor = Color.Black.copy(alpha = 0.45f)
                            )
                    ) {
                        PageCurlView(
                            currentPageIndex = uiState.currentPageIndex,
                            pageCount = uiState.pageFiles.size,
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
                        color = chromeSurface,
                        tonalElevation = 4.dp
                    ) {
                        Text(
                            text = "Drag corner to curl page",
                            style = MaterialTheme.typography.labelSmall,
                            color = chromeOnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Persistent subtle file telemetry badge when chrome is hidden
                if (!showChrome && uiState.showPageOverlay) {
                    val activeFile = uiState.pageFiles.getOrNull(uiState.currentPageIndex)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp),
                        shape = RoundedCornerShape(50),
                        color = chromeSurface.copy(alpha = 0.78f)
                    ) {
                        Text(
                            text = "${uiState.currentPageIndex + 1} / ${uiState.pageFiles.size}" +
                                if (activeFile != null) " • ${activeFile.name}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = chromeOnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                // Floating Top Bar
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Surface(
                        color = chromeSurface,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
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

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
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
                                    color = chromeOnSurface.copy(alpha = 0.72f)
                                )
                            }

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
                }

                // Floating Bottom Scrubber Bar
                AnimatedVisibility(
                    visible = showChrome && uiState.pageFiles.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val lastIdx = uiState.pageFiles.lastIndex.coerceAtLeast(1)
                    var sliderPos by remember(uiState.currentPageIndex) {
                        mutableFloatStateOf(uiState.currentPageIndex.toFloat())
                    }
                    val activeFile = uiState.pageFiles.getOrNull(uiState.currentPageIndex)

                    Surface(
                        color = chromeSurface,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 680.dp)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { viewModel.goToPage(uiState.currentPageIndex - 1) },
                                    enabled = uiState.currentPageIndex > 0,
                                    modifier = Modifier
                                        .testTag("prev_page_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Previous page",
                                        tint = chromeOnSurface
                                    )
                                }

                                Slider(
                                    value = sliderPos,
                                    onValueChange = { sliderPos = it },
                                    onValueChangeFinished = {
                                        viewModel.goToPage(sliderPos.roundToInt())
                                    },
                                    valueRange = 0f..lastIdx.toFloat(),
                                    steps = (uiState.pageFiles.size - 2).coerceAtLeast(0),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("page_scrubber_slider")
                                )

                                IconButton(
                                    onClick = { viewModel.goToPage(uiState.currentPageIndex + 1) },
                                    enabled = uiState.currentPageIndex < uiState.pageFiles.lastIndex,
                                    modifier = Modifier
                                        .testTag("next_page_button")
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Next page",
                                        tint = chromeOnSurface
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (activeFile != null && uiState.showPageOverlay) {
                                        "${activeFile.name} • ${formatBytes(activeFile.length())}"
                                    } else {
                                        "Drag edges to curl"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chromeOnSurface.copy(alpha = 0.72f)
                                )
                                Text(
                                    text = "Double-tap to zoom",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chromeOnSurface.copy(alpha = 0.72f)
                                )
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
            currentPageFile = uiState.pageFiles.getOrNull(uiState.currentPageIndex),
            onSelectTone = viewModel::setSurfaceTone,
            onTogglePageOverlay = viewModel::setShowPageOverlay,
            onDismiss = { showAppearanceSheet = false }
        )
    }
}
