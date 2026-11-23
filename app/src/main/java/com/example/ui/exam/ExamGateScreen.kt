package com.example.ui.exam

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ExamGateScreen(
    onNavigateBack: () -> Unit
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ExamViewModel = viewModel(
        factory = ExamViewModel.Factory(application = application)
    )
    ExamCenterRoot(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}
