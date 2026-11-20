package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object BookshelfRoute

@Serializable
data class ReaderRoute(val bookId: String)

@Serializable
object ExamRoute
