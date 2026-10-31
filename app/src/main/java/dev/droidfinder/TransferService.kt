package dev.droidfinder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout

object TransferRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    var label: String? = null
        private set

    @Volatile
    var progress = TransferProgress()
        private set

    @Volatile private var control: TransferControl? = null
    private var lastNotification = 0L
    @Volatile private var paused = false
    @Volatile private var pausable = false
    private var foregroundReady: CompletableDeferred<Unit>? = null

    suspend fun begin(
        context: Context,
        title: String,
        token: TransferControl,
        allowPause: Boolean = false,
    ) {
        label = title
        paused = false
        pausable = allowPause
        progress = TransferProgress()
        control = token
        val ready = CompletableDeferred<Unit>()
        foregroundReady = ready
        context.startForegroundService(Intent(context, TransferService::class.java))
        // Short operations must not stop the service before Android has promoted it.
        withTimeout(4_000) { ready.await() }
    }

    @SuppressLint(
        "MissingPermission",
    ) // A foreground-service notification is required even without POST_NOTIFICATIONS.
    fun report(context: Context, value: TransferProgress) {
        val phaseChanged = progress.phase != value.phase
        progress = value
        val now = android.os.SystemClock.elapsedRealtime()
        if (label != null && (phaseChanged || now - lastNotification > 500)) {
            lastNotification = now
            context
                .getSystemService(NotificationManager::class.java)
                .notify(TRANSFER_NOTIFICATION, notification(context))
        }
    }

    fun setPaused(context: Context, value: Boolean) {
        paused = value
        context
            .getSystemService(NotificationManager::class.java)
            .notify(TRANSFER_NOTIFICATION, notification(context))
    }

    fun cancel() {
        control?.cancel()
    }

    fun finish(context: Context) {
        label = null
        control = null
        foregroundReady = null
        context.stopService(Intent(context, TransferService::class.java))
    }

    private const val TRANSFER_NOTIFICATION = 24
    private const val CHANNEL = "file-transfers"

    fun notification(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancel =
            PendingIntent.getService(
                context,
                1,
                Intent(context, TransferService::class.java).setAction("cancel"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val toggle =
            PendingIntent.getService(
                context,
                2,
                Intent(context, TransferService::class.java).setAction("toggle-pause"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle((if (paused) "Paused · " else "") + (label ?: "DroidFinder"))
            .setContentText(
                listOf(progress.phase, transferPhaseAmount(progress), progress.current)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                1000,
                ((progress.fraction ?: 0f) * 1000).toInt(),
                progress.fraction == null,
            )
            .apply {
                if (pausable)
                    addAction(
                        if (paused) android.R.drawable.ic_media_play
                        else android.R.drawable.ic_media_pause,
                        if (paused) "Resume" else "Pause",
                        toggle,
                    )
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    fun foreground(service: Service) {
        ServiceCompat.startForeground(
            service,
            TRANSFER_NOTIFICATION,
            notification(service),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundReady?.complete(Unit)
    }
}

class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransferRuntime.foreground(this)
        if (intent?.action == "cancel") (application as FinderApplication).model.cancelTransfer()
        if (intent?.action == "toggle-pause") {
            val model = (application as FinderApplication).model
            model.activeTransferId?.let {
                if (model.activeTransfer?.state == TransferState.PAUSED) model.resumeTransfer(it)
                else model.pauseTransfer(it)
            }
        }
        if (TransferRuntime.label == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferRuntime.cancel()
        stopSelf()
    }
}
