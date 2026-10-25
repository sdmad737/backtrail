package com.backtrail.app.device

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.backtrail.app.MainActivity
import com.backtrail.app.R

/** A persisted outing/checkpoint is the destination; opening it never confirms arrival. */
data class NotificationDestination(val outingId: String, val memoryId: String? = null, val kind: String)

object TravisNotifications {
    const val TRACKING = "travis-recording"
    const val REMINDERS = "travis-reminders"
    const val EXTRA_OUTING_ID = "travis.outingId"
    const val EXTRA_MEMORY_ID = "travis.memoryId"
    const val EXTRA_REMINDER_KIND = "travis.reminderKind"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRACKING, "Active outing", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(REMINDERS, "Requested trail reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(REMINDERS)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun Intent.destination(destination: NotificationDestination): Intent = apply {
        putExtra(EXTRA_OUTING_ID, destination.outingId)
        putExtra(EXTRA_MEMORY_ID, destination.memoryId)
        putExtra(EXTRA_REMINDER_KIND, destination.kind)
    }
    fun readDestination(intent: Intent?): NotificationDestination? {
        val outing = intent?.getStringExtra(EXTRA_OUTING_ID)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val memory = intent.getStringExtra(EXTRA_MEMORY_ID)?.takeIf { it.isNotBlank() && it.length <= 200 }
        val kind = intent.getStringExtra(EXTRA_REMINDER_KIND)?.takeIf { it in setOf("pickup", "arrival", "time", "departure", "recording") } ?: return null
        return NotificationDestination(outing, memory, kind)
    }
    fun notification(context: Context, title: String, body: String, ongoing: Boolean = false, destination: NotificationDestination? = null): Notification {
        channels(context)
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (destination != null) {
            launch.destination(destination)
            // Extras do not distinguish PendingIntents. Unique data prevents a later
            // checkpoint notification from silently replacing an earlier tap's target.
            launch.data = Uri.Builder().scheme("travis").authority("outing").appendPath(destination.outingId)
                .appendQueryParameter("memory", destination.memoryId ?: "").appendQueryParameter("kind", destination.kind).build()
        }
        val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, if (ongoing) TRACKING else REMINDERS)
            .setSmallIcon(R.drawable.ic_backtrail).setContentTitle(title).setContentText(body)
            .setContentIntent(pending).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }
    fun post(context: Context, id: Int, title: String, body: String, destination: NotificationDestination? = null): Boolean {
        if (!allowed(context)) return false
        return try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification(context, title, body, destination = destination))
            true
        } catch (_: SecurityException) {
            // Permission may be revoked between the gate and notify. A reminder
            // delivery failure must never stop the independent GPS recording loop.
            false
        }
    }
    fun cancelPickup(context: Context, memoryId:String) {
        context.getSystemService(NotificationManager::class.java).cancel(memoryId.hashCode())
    }
    fun cancelMemoryAlerts(context: Context, memoryId:String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancel(memoryId.hashCode());manager.cancel(("arrival:"+memoryId).hashCode())
    }
    fun cancelOuting(context: Context, outing: com.backtrail.app.contracts.Outing) {
        val manager=context.getSystemService(NotificationManager::class.java)
        outing.memories.forEach { memory -> manager.cancel(memory.id.hashCode());manager.cancel(("arrival:"+memory.id).hashCode()) }
        val preferences=context.getSharedPreferences("reminders",0)
        if(preferences.getString("outingId",null)==outing.id) {
            PendingIntent.getBroadcast(context,900,Intent(context,TimeReminderReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                context.getSystemService(AlarmManager::class.java).cancel(it);it.cancel()
            }
            preferences.edit().clear().apply();manager.cancel(900)
        }
    }
    fun schedule(context: Context, minutes: Int, outingId: String = "", memoryId: String? = null) {
        require(outingId.isNotBlank()) { "A time reminder must belong to a saved outing" }
        val safe = minutes.coerceIn(1, 1440)
        val destination = NotificationDestination(outingId, memoryId, "time")
        val pending = PendingIntent.getBroadcast(context, 900, Intent(context, TimeReminderReceiver::class.java).destination(destination), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val due = System.currentTimeMillis() + safe * 60_000L
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        context.getSharedPreferences("reminders", 0).edit().putLong("due", due).putString("outingId", outingId).putString("memoryId", memoryId).apply()
    }
}
class TimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val destination = TravisNotifications.readDestination(intent) ?: return
        TravisNotifications.post(context, 900, "Your Travis reminder", "Time to check in on your saved outing.", destination)
        context.getSharedPreferences("reminders", 0).edit().remove("due").remove("outingId").remove("memoryId").apply()
    }
}
