package com.example.ui.bookshelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.BookEntity
import com.example.ui.theme.CosmicGlassBackdrop
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.glassPanel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onOpenBook: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCatalogDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var bookPendingDelete by remember { mutableStateOf<BookEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is BookshelfEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
                is BookshelfEvent.OpenBook -> onOpenBook(event.bookId)
            }
        }
    }

    CosmicGlassBackdrop {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val columnCount = when {
                    maxWidth >= 900.dp -> 4
                    maxWidth >= 600.dp -> 3
                    else -> 2
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.TopCenter)
                ) {
                    BookshelfHeader(
                        catalogUrl = uiState.catalogUrl,
                        unreadNotificationCount = uiState.unreadNotificationCount,
                        totalNotificationCount = uiState.notifications.size,
                        isSyncing = uiState.isSyncingCatalog,
                        onNotificationsClick = {
                            showNotificationsSheet = true
                            viewModel.markAllNotificationsRead()
                        },
                        onSyncClick = {
                            if (uiState.catalogUrl.isBlank()) {
                                showCatalogDialog = true
                            } else {
                                viewModel.syncCatalog(uiState.catalogUrl)
                            }
                        },
                        onCreditsClick = { showCreditsDialog = true }
                    )

                    if (uiState.isInitializing) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                                Text(
                                    text = "Loading library...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columnCount),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Spotlight currently reading card
                            uiState.featuredBook?.let { featured ->
                                item(span = { GridItemSpan(maxLineSpan) }, key = "featured_${featured.id}") {
                                    FeaturedBookSpotlightCard(
                                        book = featured,
                                        onOpenBook = { onOpenBook(featured.id) }
                                    )
                                }
                            }

                            // Filter chips + Search
                            item(span = { GridItemSpan(maxLineSpan) }, key = "filters_header") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(BookshelfFilter.entries) { filter ->
                                            FilterChip(
                                                selected = uiState.selectedFilter == filter,
                                                onClick = { viewModel.setFilter(filter) },
                                                label = { Text(filter.label) },
                                                shape = RoundedCornerShape(50),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    containerColor = Color.White.copy(alpha = 0.08f),
                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                                ),
                                                border = FilterChipDefaults.filterChipBorder(
                                                    enabled = true,
                                                    selected = uiState.selectedFilter == filter,
                                                    borderColor = Color.White.copy(alpha = 0.20f),
                                                    selectedBorderColor = Color.Transparent
                                                ),
                                                modifier = Modifier.testTag("filter_chip_${filter.name}")
                                            )
                                        }
                                    }

                                    if (uiState.books.size > 2 || uiState.searchQuery.isNotBlank()) {
                                        OutlinedTextField(
                                            value = uiState.searchQuery,
                                            onValueChange = viewModel::setSearchQuery,
                                            placeholder = { Text("Filter titles, authors, or categories...") },
                                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                            trailingIcon = {
                                                if (uiState.searchQuery.isNotEmpty()) {
                                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                                    }
                                                }
                                            },
                                            singleLine = true,
                                            shape = RoundedCornerShape(18.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                                unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
                                                focusedBorderColor = MaterialTheme.colorScheme.secondary,
                                                unfocusedBorderColor = Color.White.copy(alpha = 0.20f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("bookshelf_search_input")
                                        )
                                    }
                                }
                            }

                            if (uiState.books.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "empty_shelf") {
                                    EmptyBookshelfNotice(
                                        hasCatalogUrl = uiState.catalogUrl.isNotBlank(),
                                        onConfigureCatalog = { showCatalogDialog = true }
                                    )
                                }
                            } else {
                                items(uiState.books, key = { it.id }) { book ->
                                    BookshelfGridCard(
                                        book = book,
                                        transfer = uiState.transfers[book.id],
                                        onOpen = { onOpenBook(book.id) },
                                        onDownload = { viewModel.downloadBook(book) },
                                        onCancelDownload = { viewModel.cancelDownload(book.id) },
                                        onRequestDelete = { bookPendingDelete = book }
                                    )
                                }
                            }

                            item(span = { GridItemSpan(maxLineSpan) }, key = "app_credit_footer") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .glassPanel(shape = RoundedCornerShape(18.dp))
                                        .testTag("app_credit_footer")
                                        .minimumInteractiveComponentSize()
                                        .clickable { showCreditsDialog = true }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.credit_footer_title),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = stringResource(R.string.credit_footer_subtitle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
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

    if (showCatalogDialog) {
        CatalogSyncDialog(
            initialUrl = uiState.catalogUrl,
            isSyncing = uiState.isSyncingCatalog,
            onDismiss = { showCatalogDialog = false },
            onConfirmSync = { url ->
                showCatalogDialog = false
                viewModel.syncCatalog(url)
            }
        )
    }

    if (showNotificationsSheet) {
        NotificationsBottomSheet(
            notifications = uiState.notifications,
            onDeleteNotification = viewModel::deleteNotification,
            onClearAll = viewModel::clearAllNotifications,
            onDismiss = { showNotificationsSheet = false }
        )
    }

    if (showCreditsDialog) {
        AppCreditsBottomSheet(onDismiss = { showCreditsDialog = false })
    }

    bookPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { bookPendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("Remove Extracted Pages?") },
            text = {
                Text(
                    "This deletes the local WebP pages for \"${target.title}\" from device storage. " +
                        if (target.zipUrl != null) "You can re-download it from the catalog anytime."
                        else "This local import will be removed from your shelf."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLocalBook(target)
                        bookPendingDelete = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookPendingDelete = null }) {
                    Text("Keep")
                }
            }
        )
    }
}

@Composable
private fun BookshelfHeader(
    catalogUrl: String,
    unreadNotificationCount: Int,
    totalNotificationCount: Int,
    isSyncing: Boolean,
    onNotificationsClick: () -> Unit,
    onSyncClick: () -> Unit,
    onCreditsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .glassPanel(shape = RoundedCornerShape(15.dp))
                        .testTag("header_credits_button")
                        .clickable(onClick = onCreditsClick),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.bs_reader_logo),
                        contentDescription = stringResource(R.string.app_name),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bookshelf_title),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.bookshelf_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box {
                val hasActiveAlerts = unreadNotificationCount > 0 || totalNotificationCount > 0
                Surface(
                    shape = CircleShape,
                    color = if (unreadNotificationCount > 0) {
                        SoftCyan.copy(alpha = 0.16f)
                    } else {
                        Color.White.copy(alpha = 0.10f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (unreadNotificationCount > 0) {
                            SoftCyan.copy(alpha = 0.60f)
                        } else {
                            Color.White.copy(alpha = 0.22f)
                        }
                    )
                ) {
                    IconButton(
                        onClick = onNotificationsClick,
                        modifier = Modifier
                            .testTag("notification_button")
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (hasActiveAlerts) {
                                Icons.Default.Notifications
                            } else {
                                Icons.Default.NotificationsNone
                            },
                            contentDescription = stringResource(R.string.cd_notifications),
                            tint = if (unreadNotificationCount > 0) SoftCyan else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (unreadNotificationCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = ErrorCoral,
                        border = BorderStroke(1.5.dp, Color(0xFF131836)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 2.dp)
                    ) {
                        Text(
                            text = if (unreadNotificationCount > 9) "9+" else unreadNotificationCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (totalNotificationCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(8.dp)
                            .background(SoftCyan, CircleShape)
                    )
                }
            }
        }

        Button(
            onClick = onSyncClick,
            enabled = !isSyncing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sync_catalog_button")
                .minimumInteractiveComponentSize(),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (catalogUrl.isBlank()) "Set catalog.json" else stringResource(R.string.action_fetch_catalog),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EmptyBookshelfNotice(
    hasCatalogUrl: Boolean,
    onConfigureCatalog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(24.dp))
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = "No Volumes Match This Filter",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (hasCatalogUrl) {
                "Sync your remote catalog.json to fetch new manuals from your library."
            } else {
                "Connect a remote catalog.json URL to download manual editions."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onConfigureCatalog,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Configure Catalog")
        }
    }
}
