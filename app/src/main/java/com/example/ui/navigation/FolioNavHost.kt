package com.example.ui.navigation

import android.app.Application
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.bookshelf.BookshelfScreen
import com.example.ui.bookshelf.BookshelfViewModel
import com.example.ui.exam.ExamGateScreen
import com.example.ui.reader.ReaderScreen
import com.example.ui.reader.ReaderViewModel

@Composable
fun FolioNavHost() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    var showLaunchSignature by rememberSaveable { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = BookshelfRoute,
            enterTransition = { fadeIn() + slideInHorizontally { it / 10 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 10 } }
        ) {
            composable<BookshelfRoute> {
                val viewModel: BookshelfViewModel = viewModel(
                    factory = BookshelfViewModel.Factory(application)
                )
                BookshelfScreen(
                    viewModel = viewModel,
                    onOpenBook = { bookId ->
                        navController.navigate(ReaderRoute(bookId = bookId))
                    },
                    onOpenExam = {
                        navController.navigate(ExamRoute)
                    }
                )
            }

            composable<ReaderRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ReaderRoute>()
                val viewModel: ReaderViewModel = viewModel(
                    key = "reader_${route.bookId}",
                    factory = ReaderViewModel.Factory(application, route.bookId)
                )
                ReaderScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<ExamRoute> {
                ExamGateScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        LaunchSignatureOverlay(
            visible = showLaunchSignature,
            onDismissed = { showLaunchSignature = false }
        )
    }
}
