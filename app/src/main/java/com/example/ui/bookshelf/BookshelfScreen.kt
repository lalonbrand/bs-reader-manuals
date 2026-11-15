package com.example.ui.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.BookEntity
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
    var bookPendingDelete by remember { mutableStateOf<BookEntity?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalZip(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is BookshelfEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
                is BookshelfEvent.OpenBook -> onOpenBook(event.bookId)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
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
                    isSyncing = uiState.isSyncingCatalog,
                    onSyncClick = {
                        if (uiState.catalogUrl.isBlank()) {
                            showCatalogDialog = true
                        } else {
                            viewModel.syncCatalog(uiState.catalogUrl)
                        }
                    },
                    onConfigureCatalogClick = { showCatalogDialog = true },
                    onImportZipClick = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    }
                )

                if (uiState.isInitializing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Preparing WebP specimen folio...",
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
                                        shape = MaterialTheme.shapes.medium,
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
                                    onConfigureCatalog = { showCatalogDialog = true },
                                    onImportZip = {
                                        zipPickerLauncher.launch(
                                            arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                                        )
                                    }
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

    bookPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { bookPendingDelete = null },
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
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    onConfigureCatalogClick: () -> Unit,
    onImportZipClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            IconButton(
                onClick = onConfigureCatalogClick,
                modifier = Modifier
                    .testTag("catalog_settings_button")
                    .minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsEthernet,
                    contentDescription = stringResource(R.string.action_catalog_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onSyncClick,
                enabled = !isSyncing,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sync_catalog_button")
                    .minimumInteractiveComponentSize(),
                shape = MaterialTheme.shapes.small
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
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

            OutlinedButton(
                onClick = onImportZipClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("import_zip_button")
                    .minimumInteractiveComponentSize(),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_import_zip), maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyBookshelfNotice(
    hasCatalogUrl: Boolean,
    onConfigureCatalog: () -> Unit,
    onImportZip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = "No Volumes Match This Filter",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (hasCatalogUrl) {
                "Sync your remote catalog.json or import a .zip archive of page_001.webp images from storage."
            } else {
                "Connect a remote catalog.json URL to download .zip editions, or import a local .zip archive."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onConfigureCatalog) {
                Text("Configure Catalog")
            }
            OutlinedButton(onClick = onImportZip) {
                Text("Import .zip")
            }
        }
    }
}
