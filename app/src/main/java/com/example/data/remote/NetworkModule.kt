package com.example.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(logging)
            .build()
    }

    val catalogApi: CatalogApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CatalogApi::class.java)
    }

    fun parseCatalogJson(rawJson: String): CatalogResponse {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("[")) {
            val listType = Types.newParameterizedType(List::class.java, CatalogBookDto::class.java)
            val adapter = moshi.adapter<List<CatalogBookDto>>(listType)
            val books = adapter.fromJson(trimmed) ?: emptyList()
            return CatalogResponse(catalogTitle = "Remote Catalog", books = books)
        }
        val adapter = moshi.adapter(CatalogResponse::class.java)
        return adapter.fromJson(trimmed) ?: CatalogResponse()
    }
}
