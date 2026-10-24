package com.backtrail.app.device

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.backtrail.app.MainActivity
import com.backtrail.app.R

object TravisNotifications {
    const val TRACKING="travis-recording"
    const val REMINDERS="travis-reminders"
    fun channels(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRACKING,"Active outing",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(REMINDERS,"Requested trail reminders",NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun allowed(context:Context)= (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun notification(context:Context, title:String, body:String, ongoing:Boolean=false):Notification {
        channels(context)
        val intent=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context,if(ongoing) TRACKING else REMINDERS)
            .setSmallIcon(R.drawable.ic_backtrail).setContentTitle(title).setContentText(body)
            .setContentIntent(intent).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }
    fun post(context:Context, id:Int,title:String,body:String):Boolean {
        if(!allowed(context)) return false
        context.getSystemService(NotificationManager::class.java).notify(id,notification(context,title,body)); return true
    }
    fun schedule(context:Context, minutes:Int) {
        val safe=minutes.coerceIn(1,1440)
        val intent=PendingIntent.getBroadcast(context,900,Intent(context,TimeReminderReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+safe*60_000L,intent)
        context.getSharedPreferences("reminders",0).edit().putLong("due",System.currentTimeMillis()+safe*60_000L).apply()
    }
}
class TimeReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        TravisNotifications.post(context,900,"Your Travis reminder","Time to check in on your outing.")
        context.getSharedPreferences("reminders",0).edit().remove("due").apply()
    }
}
