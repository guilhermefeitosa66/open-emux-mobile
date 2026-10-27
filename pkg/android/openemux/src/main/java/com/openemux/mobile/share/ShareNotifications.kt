package com.openemux.mobile.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openemux.mobile.AppLocale
import com.openemux.mobile.MainActivity
import com.openemux.mobile.ui.share.sizeLabel
import com.retroarch.R

/**
 * The two channels of Share nearby and what goes on them: the transfer's
 * ongoing progress (the foreground service's notification), the incoming
 * offer when the app is not in front, and how a transfer ended then. Without
 * the notification permission nothing shows and nothing breaks.
 */
class ShareNotifications(private val app: Context) : ShareNotifier {
    private val manager = NotificationManagerCompat.from(app)
    private val context: Context get() = AppLocale.wrap(app)

    fun createChannels() {
        val c = context
        val system = app.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, c.getString(R.string.share_notif_channel_transfers), NotificationManager.IMPORTANCE_LOW)
                .apply { description = c.getString(R.string.share_notif_channel_transfers_desc) },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFERS, c.getString(R.string.share_notif_channel_offers), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = c.getString(R.string.share_notif_channel_offers_desc) },
        )
    }

    /** The foreground service's notification for [state]: heading, bytes, a bar and Cancel. */
    fun progress(state: TransferState): Notification {
        val c = context
        val notice = TransferNotice.of(state)
        val builder = NotificationCompat.Builder(c, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(0))
            .addAction(0, c.getString(R.string.share_notif_cancel), cancelIntent())
        if (notice == null) return builder.setContentTitle(c.getString(R.string.share_title)).build()
        val title = when {
            notice.waiting -> c.getString(R.string.share_wait_title, notice.peer)
            notice.outgoing -> c.getString(R.string.share_progress_sending, notice.peer)
            else -> c.getString(R.string.share_progress_receiving, notice.peer)
        }
        builder.setContentTitle(title)
        if (!notice.waiting) {
            builder.setContentText(
                c.resources.getQuantityString(
                    R.plurals.share_notif_progress_text, notice.games, notice.games,
                    sizeLabel(c, notice.done), sizeLabel(c, notice.total),
                ),
            )
        }
        val percent = notice.percent
        return builder.setProgress(100, percent ?: 0, percent == null).build()
    }

    override fun offer(sender: String, code: String) {
        val c = context
        post(
            ID_OFFER,
            NotificationCompat.Builder(c, CHANNEL_OFFERS)
                .setSmallIcon(R.drawable.ic_stat_share)
                .setContentTitle(c.getString(R.string.share_notif_offer_title, sender))
                .setContentText(c.getString(R.string.share_notif_offer_text, ShareFormat.formatCode(code)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setTimeoutAfter(ShareProtocol.OFFER_TTL_MS)
                .setContentIntent(openApp(1))
                .build(),
        )
    }

    override fun clearOffer() = manager.cancel(ID_OFFER)

    override fun finished(result: ShareResult) {
        val c = context
        val title = when (result.kind) {
            ResultKind.RECEIVED -> withSkipped(c.resources.getQuantityString(R.plurals.share_notif_done_received, result.games, result.games), result)
            ResultKind.SENT -> withSkipped(c.resources.getQuantityString(R.plurals.share_notif_done_sent, result.games, result.games), result)
            ResultKind.CANCELLED -> c.getString(R.string.share_result_cancelled_title)
            ResultKind.DECLINED -> c.getString(R.string.share_result_declined_title)
            ResultKind.EXPIRED -> c.getString(R.string.share_result_expired_title)
            ResultKind.CONNECTION_LOST -> c.getString(R.string.share_result_lost_title)
            ResultKind.FAILED -> c.getString(R.string.share_notif_failed)
        }
        post(
            ID_DONE,
            NotificationCompat.Builder(c, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_stat_share)
                .setContentTitle(title)
                .setContentText(result.peer)
                .setAutoCancel(true)
                .setContentIntent(openApp(2))
                .build(),
        )
    }

    /** "2 games received, 1 skipped": the games that did not arrive are never left out of the count (#615). */
    private fun withSkipped(done: String, result: ShareResult): String {
        val skipped = result.missing.size
        if (skipped == 0) return done
        return context.getString(R.string.share_notif_done_skipped, done, context.resources.getQuantityString(R.plurals.share_notif_skipped, skipped, skipped))
    }

    private fun post(id: Int, notification: Notification) {
        try {
            if (manager.areNotificationsEnabled()) manager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was taken back between the check and the post.
        }
    }

    private fun openApp(request: Int): PendingIntent = PendingIntent.getActivity(
        app, request,
        // Brings the library forward without closing a game above it.
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_OPEN_OFFER, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        app, 0, Intent(app, TransferService::class.java).setAction(TransferService.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_TRANSFERS = "share_transfers"
        const val CHANNEL_OFFERS = "share_offers"
        const val ID_PROGRESS = 5301
        const val ID_OFFER = 5302
        const val ID_DONE = 5303
        const val EXTRA_OPEN_OFFER = "open_offer"
    }
}
