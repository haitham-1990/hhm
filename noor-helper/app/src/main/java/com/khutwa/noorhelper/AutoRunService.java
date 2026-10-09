package com.khutwa.noorhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class AutoRunService extends Service {
    private static final String CHANNEL_ID = "noor_auto_run";
    private static final int NOTIFICATION_ID = 6040;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("نور الذكي يعمل")
                .setContentText("جاري تجهيز قاعدة التحضير أو نشر الدروس تلقائيًا.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "تشغيل نور الذكي",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("يبقي تجهيز القاعدة والتشغيل التلقائي نشطين.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
