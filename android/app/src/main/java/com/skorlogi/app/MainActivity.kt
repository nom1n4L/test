package com.skorlogi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.skorlogi.app.ui.App
import com.skorlogi.app.ui.AppViewModel
import com.skorlogi.app.ui.SkorlogiTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SkorlogiTheme {
                App(viewModel)
            }
        }
    }
}
