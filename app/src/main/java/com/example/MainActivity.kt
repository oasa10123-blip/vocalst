package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.VocalStageApp
import com.example.ui.theme.VocalStageTheme

class MainActivity : ComponentActivity() {
    private val stageViewModel: StageViewModel by viewModels {
        val app = application as VocalStageApplication
        StageViewModel.Factory(app.container.stageController)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            VocalStageTheme {
                VocalStageApp(viewModel = stageViewModel)
            }
        }
    }
}
