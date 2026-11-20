package com.mikesteve.e621downloadler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mikesteve.e621downloadler.ui.E6DLApp
import com.mikesteve.e621downloadler.ui.theme.E6DLTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            E6DLTheme {
                E6DLApp()
            }
        }
    }
}
