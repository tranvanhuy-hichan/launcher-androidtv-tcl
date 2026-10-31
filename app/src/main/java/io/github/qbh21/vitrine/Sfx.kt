package io.github.qbh21.vitrine

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The TV's own interface sounds (the same ones Settings and the stock launcher use): a navigation sound in the
 * direction you pressed when focus moves, and the click sound on OK. They follow the TV's "system sounds" setting.
 */
object Sfx {
    private var audio: AudioManager? = null
    private var direction = AudioManager.FX_FOCUS_NAVIGATION_RIGHT
    private var lastMove = 0L
    private var readyAt = 0L
    var enabled = true
        private set

    fun init(context: Context) {
        audio = context.getSystemService(AudioManager::class.java)
        enabled = context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("sfx", true)
        readyAt = SystemClock.elapsedRealtime() + 1_500   // no sound for the focus the screen starts with
    }

    /** Remembers which way the last D-pad press went, so the focus sound matches it. */
    fun key(keyCode: Int) {
        direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            else -> return
        }
    }

    /** Turns the sounds on or off and remembers the choice. Returns the new state. */
    fun toggle(context: Context): Boolean {
        set(context, !enabled)
        if (enabled) select()
        return enabled
    }

    fun set(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("sfx", on).apply()
    }

    fun move() {
        val now = SystemClock.elapsedRealtime()
        if (!enabled || now < readyAt || now - lastMove < 50) return
        lastMove = now
        audio?.playSoundEffect(direction)
    }

    fun select() { if (enabled) audio?.playSoundEffect(AudioManager.FX_KEY_CLICK) }
}
