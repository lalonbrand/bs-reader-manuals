package com.example.ui.bookshelf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.BookEntity
import com.example.data.local.BookStatus
import com.example.data.local.FolioDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.BookRepository
import com.example.data.repository.TransferProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BookshelfFilter(val label: String) {
    ALL("All Folios"),
    READY("On Device"),
    CATALOG("Remote Catalog")
}

data class BookshelfUiState(
    val books: List<BookEntity> = emptyList(),
    val featuredBook: BookEntity? = null,
    val transfers: Map<String, TransferProgress> = emptyMap(),
    val selectedFilter: BookshelfFilter = BookshelfFilter.ALL,
    val searchQuery: String = "",
    val catalogUrl: String = com.example.data.local.DEFAULT_CATALOG_URL,
    val isSyncingCatalog: Boolean = false,
    val isInitializing: Boolean = true
)

sealed interface BookshelfEvent {
    data class ShowMessage(val text: String) : BookshelfEvent
    data class OpenBook(val bookId: String) : BookshelfEvent
}

class BookshelfViewModel(
    application: Application,
    private val repository: BookRepository,
    private val settingsStore: SettingsDataStore
) : AndroidViewModel(application) {

    private val selectedFilter = MutableStateFlow(BookshelfFilter.ALL)
    private val searchQuery = MutableStateFlow("")
    private val isSyncingCatalog = MutableStateFlow(false)
    private val isInitializing = MutableStateFlow(true)

    private val _events = MutableSharedFlow<BookshelfEvent>()
    val events = _events.asSharedFlow()

    val uiState: StateFlow<BookshelfUiState> = combine(
        repository.booksFlow,
        repository.transfers,
        selectedFilter,
        searchQuery,
        settingsStore.preferences,
        isSyncingCatalog,
        isInitializing
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allBooks = values[0] as List<BookEntity>
        @Suppress("UNCHECKED_CAST")
        val transfers = values[1] as Map<String, TransferProgress>
        val filter = values[2] as BookshelfFilter
        val query = values[3] as String
        val prefs = values[4] as com.example.data.local.ReaderPreferences
        val syncing = values[5] as Boolean
        val initializing = values[6] as Boolean

        val filtered = allBooks.filter { book ->
            val matchesFilter = when (filter) {
                BookshelfFilter.ALL -> true
                BookshelfFilter.READY -> book.isReady
                BookshelfFilter.CATALOG -> !book.zipUrl.isNullOrBlank()
            }
            val matchesQuery = query.isBlank() ||
                book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true) ||
                book.category.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }

        val featured = allBooks.firstOrNull { it.isReady }

        BookshelfUiState(
            books = filtered,
            featuredBook = featured,
            transfers = transfers,
            selectedFilter = filter,
            searchQuery = query,
            catalogUrl = prefs.catalogUrl,
            isSyncingCatalog = syncing,
            isInitializing = initializing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BookshelfUiState()
    )

    init {
        viewModelScope.launch {
            runCatching { repository.removeStarterBookIfPresent() }
            isInitializing.value = false

            val url = runCatching { settingsStore.preferences.first().catalogUrl }.getOrDefault("")
            if (url.isNotBlank()) {
                isSyncingCatalog.value = true
                runCatching { repository.syncRemoteCatalog(url) }
                isSyncingCatalog.value = false
            }
        }
    }

    fun setFilter(filter: BookshelfFilter) {
        selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun syncCatalog(urlInput: String) {
        val trimmed = urlInput.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch {
                _events.emit(BookshelfEvent.ShowMessage("Enter a valid catalog.json URL first."))
            }
            return
        }
        viewModelScope.launch {
            settingsStore.setCatalogUrl(trimmed)
            isSyncingCatalog.value = true
            val result = repository.syncRemoteCatalog(trimmed)
            isSyncingCatalog.value = false
            result.fold(
                onSuccess = { count ->
                    _events.emit(
                        BookshelfEvent.ShowMessage(
                            if (count > 0) "Synced $count book(s) from catalog."
                            else "Catalog reached, but no books were listed."
                        )
                    )
                },
                onFailure = { err ->
                    _events.emit(
                        BookshelfEvent.ShowMessage("Catalog sync failed: ${err.localizedMessage ?: "Network error"}")
                    )
                }
            )
        }
    }

    fun downloadBook(book: BookEntity) {
        viewModelScope.launch {
            repository.downloadAndExtractBook(book)
        }
    }

    fun cancelDownload(bookId: String) {
        repository.cancelDownload(bookId)
    }

    fun importLocalZip(uri: Uri) {
        viewModelScope.launch {
            val result = repository.importLocalZip(uri)
            result.fold(
                onSuccess = { imported ->
                    _events.emit(BookshelfEvent.ShowMessage("Extracted ${imported.pageCount} pages into '${imported.title}'."))
                    _events.emit(BookshelfEvent.OpenBook(imported.id))
                },
                onFailure = { err ->
                    _events.emit(
                        BookshelfEvent.ShowMessage("Import failed: ${err.localizedMessage ?: "Invalid .zip archive"}")
                    )
                }
            )
        }
    }

    fun deleteLocalBook(book: BookEntity) {
        viewModelScope.launch {
            repository.deleteLocalFiles(book)
            _events.emit(BookshelfEvent.ShowMessage("Removed local files for '${book.title}'."))
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val db = FolioDatabase.getInstance(application)
            val repo = BookRepository(application, db.bookDao())
            val settings = SettingsDataStore(application)
            return BookshelfViewModel(application, repo, settings) as T
        }
    }
}
