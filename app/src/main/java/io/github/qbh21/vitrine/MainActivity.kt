package io.github.qbh21.vitrine

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The launcher's only activity. It's the HOME screen once you pick it, and also a normal app in the
 * TV's app list so it can be tried first. Opened as a normal app, Back closes it. As the home screen,
 * Back has nothing to go back to, and Home always brings you back to the main row.
 */
class MainActivity : ComponentActivity() {
    private var asHome by mutableStateOf(false)
    private var refresh by mutableIntStateOf(0)   // bumped on resume: reload apps, re-sync the clock
    private var reset by mutableIntStateOf(0)     // bumped when Home is pressed while we're open
    private var resumed by mutableStateOf(false)  // live tiles only animate while we're on screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asHome = intent.hasCategory(Intent.CATEGORY_HOME)
        setContent { LauncherScreen(refresh, reset, asHome, resumed, onExit = ::finish) }
        // Keeps the phone connection alive while other apps are open.
        runCatching { startForegroundService(Intent(this, KeepAliveService::class.java)) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        asHome = intent.hasCategory(Intent.CATEGORY_HOME)
        reset++
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh++
    }
}
