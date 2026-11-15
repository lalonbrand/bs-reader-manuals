package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Query("UPDATE books SET lastReadPage = :pageIndex, updatedAt = :updatedAt WHERE id = :bookId")
    suspend fun updateLastReadPage(bookId: String, pageIndex: Int, updatedAt: Long)

    @Query(
        """
        UPDATE books 
        SET localDirPath = :localDirPath,
            localCoverPath = :localCoverPath,
            pageCount = :pageCount,
            fileSizeBytes = :fileSizeBytes,
            status = :status,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :bookId
        """
    )
    suspend fun markBookExtracted(
        bookId: String,
        localDirPath: String,
        localCoverPath: String?,
        pageCount: Int,
        fileSizeBytes: Long,
        status: String = BookStatus.READY.name,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE books SET status = :status, errorMessage = :errorMessage WHERE id = :bookId")
    suspend fun updateStatus(bookId: String, status: String, errorMessage: String? = null)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)
}
