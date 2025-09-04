package com.example.lanscreenstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {
    public static final String CHANNEL_ID = "stream_channel";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(ctx.getString(R.string.notif_channel_desc));
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    public static Notification buildForeground(Context ctx, String url) {
        ensureChannel(ctx);
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(String.format(ctx.getString(R.string.notif_text), url))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}