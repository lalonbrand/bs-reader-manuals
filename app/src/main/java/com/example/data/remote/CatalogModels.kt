package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CatalogResponse(
    @Json(name = "catalogTitle") val catalogTitle: String? = null,
    @Json(name = "books") val books: List<CatalogBookDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CatalogBookDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "author") val author: String = "Unknown Author",
    @Json(name = "description") val description: String = "",
    @Json(name = "category") val category: String = "General",
    @Json(name = "coverUrl") val coverUrl: String? = null,
    @Json(name = "zipUrl") val zipUrl: String,
    @Json(name = "pageCount") val pageCount: Int = 0,
    @Json(name = "fileSizeBytes") val fileSizeBytes: Long = 0L
)
