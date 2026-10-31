package io.github.qbh21.vitrine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Makes Vitrine the home screen without disabling the stock launcher.
 *
 * On this TV the stock launcher's HOME filter has priority 2, which Android picks before any saved
 * default, and an app outside the system image can't match it. So this service watches for the stock
 * launcher's window (it only receives events from that one package, and can't read screen content)
 * and brings Vitrine up in its place: after the Home button, at startup, or when an app exits to home.
 *
 * Safety: if the stock launcher keeps coming back right after we switched away from it (Vitrine
 * crashing, say), stop redirecting for a while so the stock launcher stays usable as a fallback.
 */
class HomeRedirectService : AccessibilityService() {
    private var lastRedirect = 0L
    private var quickReturns = 0
    private var pausedUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != STOCK_LAUNCHER) return
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return

        quickReturns = if (now - lastRedirect < QUICK_RETURN_MS) quickReturns + 1 else 0
        if (quickReturns >= MAX_QUICK_RETURNS) {
            pausedUntil = now + PAUSE_MS   // Vitrine isn't staying up; leave the stock launcher alone
            quickReturns = 0
            return
        }
        lastRedirect = now
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(home)
        } catch (e: Exception) {
            pausedUntil = now + PAUSE_MS
        }
    }

    override fun onInterrupt() {}

    // ---- Typing from the phone and Back/Home from anywhere (used by the phone server) ----

    /** Depth-first search for an editable node that has focus. (`findFocus` misses some fields, e.g. in Compose.) */
    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.isEditable && (n.isFocused || n.isAccessibilityFocused)) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** The text field that has input focus in any app window, if there is one. */
    private fun inputNode(): AccessibilityNodeInfo? {
        findEditable(rootInActiveWindow)?.let { return it }
        for (w in windows) findEditable(w.root)?.let { return it }
        return null
    }

    /** (is a text field focused, what it currently says). */
    fun focusedField(): Pair<Boolean, String> {
        val node = inputNode() ?: return false to ""
        return true to if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
    }

    fun setText(text: String): Boolean {
        val node = inputNode() ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean = inputNode()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun lockScreen() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** The service connects right after the TV boots; show our home screen instead of whatever starts first. */
    override fun onServiceConnected() {
        instance = this
        if (SystemClock.elapsedRealtime() > 3 * 60_000L) return   // enabled later on, not a boot
        val handler = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(0L, 8_000L)) handler.postDelayed({ showHome() }, delay)
    }

    private fun showHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(home) } catch (e: Exception) { /* leave the stock launcher alone */ }
    }

    companion object {
        /** The running service, so the phone server (same process) can use it. */
        @Volatile var instance: HomeRedirectService? = null

        const val STOCK_LAUNCHER = "com.google.android.tvlauncher"
        const val QUICK_RETURN_MS = 3_000L
        const val MAX_QUICK_RETURNS = 3
        const val PAUSE_MS = 10 * 60_000L
    }
}
