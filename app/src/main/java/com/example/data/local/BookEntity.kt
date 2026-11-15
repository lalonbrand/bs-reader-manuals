package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val description: String,
    val category: String,
    val coverUrl: String? = null,
    val localCoverPath: String? = null,
    val zipUrl: String? = null,
    val localDirPath: String? = null,
    val pageCount: Int = 0,
    val lastReadPage: Int = 0,
    val fileSizeBytes: Long = 0L,
    val status: String = BookStatus.NOT_DOWNLOADED.name,
    val errorMessage: String? = null,
    val isStarterBundled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun parsedStatus(): BookStatus =
        runCatching { BookStatus.valueOf(status) }.getOrDefault(BookStatus.NOT_DOWNLOADED)

    val isReady: Boolean
        get() = parsedStatus() == BookStatus.READY && !localDirPath.isNullOrBlank()
}
