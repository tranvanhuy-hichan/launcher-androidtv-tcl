package io.github.qbh21.vitrine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager

/** What the remote's power, mute and input keys do, for the home screen and the phone page. */
object TvControl {
    /** Turns the screen off (standby). Needs the accessibility service, which is also what puts the Home button on this launcher. */
    fun power(): Boolean = HomeRedirectService.instance?.lockScreen() ?: false

    fun isMuted(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).let { it.isStreamMute(AudioManager.STREAM_MUSIC) || it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }

    /** Toggles mute and returns the new state. */
    fun toggleMute(context: Context): Boolean {
        context.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        return isMuted(context)
    }

    /** HDMI and other pass-through inputs this TV reports, as (label, intent that switches to it). */
    fun inputs(context: Context): List<Pair<String, Intent>> = runCatching {
        context.getSystemService(TvInputManager::class.java).tvInputList.filter { it.isPassthroughInput }.map { info ->
            info.loadLabel(context).toString() to Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
        }
    }.getOrDefault(emptyList())

    fun switchInput(context: Context, index: Int): Boolean {
        val intent = inputs(context).getOrNull(index)?.second ?: return false
        return open(context, intent)
    }
}
