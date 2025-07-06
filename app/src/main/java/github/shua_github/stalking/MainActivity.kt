package github.shua_github.stalking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import github.shua_github.stalking.ui.theme.StalkingTheme


import github.shua_github.stalking.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StalkingTheme {
                MainScreen()
            }
        }
    }
}

// ...已移除 Greeting 相关内容...