package com.garfbargle.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.garfbargle.library.ui.LibraryApp
import com.garfbargle.library.ui.theme.LibraryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibraryTheme {
                LibraryApp()
            }
        }
    }
}
