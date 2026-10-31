package io.github.qbh21.vitrine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread

/** The one phone server for the whole app, shared by the home screen and the background service. */
object PhoneHub {
    @Volatile private var server: PhoneServer? = null
    fun get(context: Context): PhoneServer =
        server ?: synchronized(this) { server ?: PhoneServer(context.applicationContext).also { server = it } }
}

/**
 * Keeps the phone server running while other apps are in front (YouTube, VTVGo...), so the phone stays
 * connected. It shows a small, silent notification, as Android requires for anything that stays running.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "keepalive"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "Điều khiển từ xa", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_home)
            .setContentTitle("Launcher TCL").setContentText("Đang chờ điện thoại kết nối").setOngoing(true).build()
        startForeground(1, notification)
        thread(isDaemon = true) { PhoneHub.get(this).start() }
        return START_STICKY   // Android restarts it if it ever ends the process
    }

    override fun onDestroy() {
        PhoneHub.get(this).stop()
        super.onDestroy()
    }
}
