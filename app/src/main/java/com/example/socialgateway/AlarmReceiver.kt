package com.example.socialgateway

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val question = context.resources.getString(R.string.check_in_question_value)

        val checkInIntent = Intent(context, MainActivity::class.java).let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            it.putExtra("intentCategory", IntentCategory.CheckIn)
            it.putExtra("question", question)

            PendingIntent.getActivity(context, "check-in".hashCode(), it, PendingIntent.FLAG_CANCEL_CURRENT)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.placeholder)
            .setContentTitle(context.resources.getString(R.string.check_in_question))
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(checkInIntent)
            .setAutoCancel(true)

        // notificationId is a unique int for each notification that you must define
        val notificationId = System.currentTimeMillis() % Int.MAX_VALUE
        NotificationManagerCompat.from(context).notify(notificationId.toInt(), builder.build())
    }
}