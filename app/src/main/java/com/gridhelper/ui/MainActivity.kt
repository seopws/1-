package com.gridhelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so permission states are re-read after returning from Settings. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GridHelperTheme {
                AppRoot(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private enum class Screen { MAIN, STATIC_TEST }

@Composable
private fun AppRoot(resumeTick: Int) {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
    when (screen) {
        Screen.MAIN -> MainScreen(resumeTick = resumeTick, onOpenStaticTest = { screen = Screen.STATIC_TEST })
        Screen.STATIC_TEST -> StaticTestScreen(onBack = { screen = Screen.MAIN })
    }
}
