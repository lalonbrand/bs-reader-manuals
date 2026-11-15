package com.example.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface CatalogApi {
    @GET
    suspend fun fetchCatalogRaw(@Url catalogUrl: String): ResponseBody

    @Streaming
    @GET
    suspend fun downloadZip(@Url zipUrl: String): ResponseBody
}
