package com.simplesound.app.playback

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Player
import com.simplesound.app.data.model.EdgeBarSide

/**
 * Decides when the lock-screen "edge control bar" should be shown and launches
 * it (see Settings > Edge control bar). Owned and driven entirely by
 * [PlaybackService], mirroring [EdgeControlBarOverlay]'s old role -- but this
 * launches a real `Activity` (`LockScreenControlActivity`, in :app) via a
 * full-screen notification intent, not directly.
 *
 * Getting here took two dead ends, both confirmed by testing on a real device
 * (a stock Pixel), not just reasoned about:
 *
 * 1. A plain `WindowManager` overlay (this feature's first design). Android
 *    silently refuses to composite *any* third-party overlay window -- even
 *    one holding `SYSTEM_ALERT_WINDOW` with `FLAG_SHOW_WHEN_LOCKED` -- once a
 *    *secure* keyguard (PIN/pattern/biometric) is showing. Closed off since
 *    around Android 9/10 specifically to stop overlay apps from tapjacking
 *    PIN entry; device-independent, not an OEM restriction.
 * 2. Calling `context.startActivity()` directly from here (this feature's
 *    second design), targeting `LockScreenControlActivity`
 *    (`Activity.setShowWhenLocked()` is the mechanism alarm/camera "quick
 *    launch" apps use to draw over a secure keyguard, and still works *once
 *    an activity is actually asked to start*). But a background service
 *    calling `startActivity()` with no visible window of its own is exactly
 *    what Android's Background Activity Launch (BAL) policy exists to block
 *    -- logcat showed `Background activity launch blocked!` /
 *    `resultIfPiCreatorAllowsBal: BAL_BLOCK` even though this service is a
 *    genuine active foreground service (`callingUidProcState:
 *    FOREGROUND_SERVICE` alone isn't one of the exemptions).
 *
 * A full-screen *notification* intent is one of the few BAL-exempt paths
 * left: the exact mechanism incoming-call and alarm apps use to pop an
 * activity over the lock screen. It needs the `USE_FULL_SCREEN_INTENT`
 * manifest permission (declared in :app) and a HIGH-importance channel; the
 * Activity cancels the triggering notification itself the moment it's shown
 * (see `LockScreenControlActivity`).
 *
 * :playback can't reference the Activity class directly (it lives in :app,
 * which depends on :playback -- the reverse dependency would be circular), so
 * the notification's intent targets [PlaybackService.ACTION_SHOW_LOCK_CONTROL],
 * matched by an intent-filter on the Activity in the manifest.
 */
class LockScreenControlLauncher(
    private val context: Context,
    private val player: Player,
) {
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val notificationManager = NotificationManagerCompat.from(context)

    private var enabled: Boolean = true
    private var side: EdgeBarSide = EdgeBarSide.Default

    /** Whether this screen-on/lock cycle has already fired the control screen once.
     *  Reset whenever [refresh] finds the bar shouldn't be showing -- see [refresh] --
     *  so dismissing it (tapping through to the real keyguard) doesn't immediately
     *  relaunch it while nothing else has changed, but the *next* genuine screen-on
     *  while still locked gets a fresh chance. */
    private var firedThisCycle = false

    /** Tracked from the SCREEN_ON/SCREEN_OFF receiver registered in [PlaybackService] --
     *  cheaper than querying display state, and the natural point to reset
     *  [firedThisCycle] for the next screen-on. */
    var screenOn: Boolean = true
        set(value) {
            field = value
            refresh()
        }

    init {
        ensureChannel()
    }

    fun setSettings(
        enabled: Boolean,
        side: EdgeBarSide,
    ) {
        this.enabled = enabled
        this.side = side
        refresh()
    }

    /** Re-evaluate whether the control screen should be triggered. Cheap to call from
     *  every relevant event -- there's no per-call system work beyond the checks below,
     *  and it never fires more than once per screen-on/lock cycle (see [firedThisCycle]). */
    fun refresh() {
        val shouldShow =
            enabled &&
                screenOn &&
                keyguardManager.isKeyguardLocked &&
                player.mediaItemCount > 0
        if (!shouldShow) {
            firedThisCycle = false
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        if (firedThisCycle) return
        firedThisCycle = true
        postFullScreenNotification()
    }

    private fun postFullScreenNotification() {
        val contentIntent =
            Intent(PlaybackService.ACTION_SHOW_LOCK_CONTROL)
                .setPackage(context.packageName)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Edge control bar")
                .setContentText("Tap to show playback controls")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        // IMPORTANCE_HIGH is required for a full-screen intent to actually fire rather
        // than just sit as a normal notification -- see the class doc comment.
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Edge control bar",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Triggers the lock-screen edge control bar"
                setShowBadge(false)
            }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "edge_control_bar"
        private const val NOTIFICATION_ID = 421
        private const val REQUEST_CODE = 421

        /** Cancels the triggering notification. `LockScreenControlActivity` calls this from
         *  its onCreate so the notification never lingers in the shade once the screen it
         *  points to is actually showing. */
        fun cancelNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
