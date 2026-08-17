package com.garfbargle.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.garfbargle.library.ui.LibraryApp
import com.garfbargle.library.ui.theme.Ink
import com.garfbargle.library.ui.theme.LibraryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibraryTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Ink)
                        .statusBarsPadding()
                ) {
                    LibraryApp()
                }
            }
        }
    }
}