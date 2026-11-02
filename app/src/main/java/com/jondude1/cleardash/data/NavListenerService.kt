package com.jondude1.cleardash.data

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads Google Maps' (and Waze's) turn-by-turn notification so the dash can
 * show the next turn while Maps does the actual navigating.
 */
class NavListenerService : NotificationListenerService() {

    private val navApps = setOf("com.google.android.apps.maps", "com.waze")

    override fun onListenerConnected() {
        if (DataHub.isRunning) MediaSource.start(this)
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in navApps && isNav(sbn.notification)) {
            DataHub.update { it.copy(nav = null) }
        }
    }

    private fun isNav(n: Notification): Boolean =
        n.category == Notification.CATEGORY_NAVIGATION ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in navApps) return
        val n = sbn.notification
        if (!isNav(n)) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // Maps usually puts the distance in the title ("0.3 mi") and the
        // instruction in the text; sometimes it is the other way round.
        val looksLikeDistance = Regex("""^\d+(\.\d+)?\s?(ft|mi|m|km|yd)\b""", RegexOption.IGNORE_CASE)
        val (distance, instruction) = when {
            looksLikeDistance.containsMatchIn(title) -> title to text
            looksLikeDistance.containsMatchIn(text) -> text to title
            else -> "" to listOf(title, text).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val icon = loadIcon(n)
        DataHub.update {
            it.copy(nav = NavInfo(distance, instruction, sub, icon, System.currentTimeMillis()))
        }
    }

    private fun loadIcon(n: Notification): Bitmap? = try {
        val ic: Icon? = n.getLargeIcon()
        val d = ic?.loadDrawable(this)
        when (d) {
            null -> null
            is BitmapDrawable -> d.bitmap
            else -> {
                val w = d.intrinsicWidth.coerceAtLeast(64)
                val h = d.intrinsicHeight.coerceAtLeast(64)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    val c = Canvas(b)
                    d.setBounds(0, 0, w, h)
                    d.draw(c)
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
