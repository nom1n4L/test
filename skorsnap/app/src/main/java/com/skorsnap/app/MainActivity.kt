package com.skorsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.skorsnap.app.ui.App
import com.skorsnap.app.ui.AppViewModel
import com.skorsnap.app.ui.SkorsnapTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SkorsnapTheme {
                App(viewModel)
            }
        }
    }
}
