package com.firebirdberlin.nightdream.receivers;


import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.app.NotificationCompat;

import com.firebirdberlin.nightdream.Config;
import com.firebirdberlin.nightdream.NightDreamActivity;
import com.firebirdberlin.nightdream.R;
import com.firebirdberlin.nightdream.Settings;
import com.firebirdberlin.nightdream.Utility;
import com.firebirdberlin.nightdream.models.SimpleTime;
import com.firebirdberlin.nightdream.services.AlarmHandlerService;
import com.firebirdberlin.nightdream.services.AlarmService;
import com.firebirdberlin.nightdream.services.RadioStreamService;

import java.text.SimpleDateFormat;
import java.util.Date;

public class WakeUpReceiver extends BroadcastReceiver {
    private final static String TAG = "WakeUpReceiver";
    private Settings settings;

    public static void schedule(Context context) {
        Settings settings = new Settings(context);
        if (settings.nextAlarmTimeMinutes == 0) return;
        setAlarm(context, settings.nextAlarmTimeMinutes);
    }

    public static void cancelAlarm(Context context) {
        PendingIntent pI = WakeUpReceiver.getPendingIntent(context);
        AlarmManager am = (AlarmManager) (context.getSystemService(Context.ALARM_SERVICE));
        am.cancel(pI);
    }

    public static PendingIntent getPendingIntent(Context context) {
        Intent intent = new Intent("com.firebirdberlin.nightdream.WAKEUP");
        intent.putExtra("action", "start alarm");
        //return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        // PendingIntent.FLAG_CANCEL_CURRENT seems to confuse AlarmManager.cancel() on certain
        // Android devices, e.g. HTC One m7, i.e. AlarmManager.getNextAlarmClock() still returns
        // already cancelled alarm times afterwards.
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    private static void setAlarm(Context context, int nextAlarmTimeMinutes) {
        PendingIntent pI = WakeUpReceiver.getPendingIntent(context);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pI);

        long nextAlarmTime = new SimpleTime(nextAlarmTimeMinutes).getMillis();
        if (Build.VERSION.SDK_INT >= 21) {
            AlarmManager.AlarmClockInfo info =
                    new AlarmManager.AlarmClockInfo(nextAlarmTime, pI);
            am.setAlarmClock(info, pI);
        } else if (Build.VERSION.SDK_INT >= 19) {
            am.setExact(AlarmManager.RTC_WAKEUP, nextAlarmTime, pI);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, nextAlarmTime, pI);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        settings = new Settings(context);
        if ( !settings.useInternalAlarm ) return;

        if ( settings.useRadioAlarmClock && Utility.hasFastNetworkConnection(context) ) {
            RadioStreamService.start(context);
        } else {
            if ( RadioStreamService.streamingMode != RadioStreamService.StreamingMode.INACTIVE ) {
                RadioStreamService.stop(context);
            }
            AlarmService.startAlarm(context);
        }

        buildNotification(context);
        NightDreamActivity.start(context);
    }

    private Notification buildNotification(Context context) {
        String text = dateAsString(settings.getTimeFormat());
        String textActionSnooze = context.getString(R.string.action_snooze);
        String textActionStop = context.getString(R.string.action_stop);

        Intent stopIntent = AlarmHandlerService.getStopIntent(context);
        PendingIntent pStopIntent = PendingIntent.getService(context, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder note = new NotificationCompat.Builder(context)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.alarm))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_audio)
            .setPriority(NotificationCompat.PRIORITY_MAX);

        NotificationCompat.WearableExtender wearableExtender =
            new NotificationCompat.WearableExtender().setHintHideIcon(true);

        NotificationCompat.Action stopAction =
            new NotificationCompat.Action.Builder(0, textActionStop, pStopIntent).build();
        note.addAction(stopAction);
        wearableExtender.addAction(stopAction);

        Intent snoozeIntent = AlarmHandlerService.getSnoozeIntent(context);
        PendingIntent pSnoozeIntent = PendingIntent.getService(context, 0, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Action snoozeAction =
            new NotificationCompat.Action.Builder(0, textActionSnooze, pSnoozeIntent)
                                         .build();
        note.addAction(snoozeAction);
        wearableExtender.addAction(snoozeAction);

        NotificationCompat.BigTextStyle bigStyle = new NotificationCompat.BigTextStyle();
        bigStyle.bigText(text);
        note.setStyle(bigStyle);

        note.extend(wearableExtender);

        Notification notification = note.build();
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(Config.NOTIFICATION_ID_DISMISS_ALARMS, notification);

        return notification;
    }

    private String dateAsString(String format) {
        Date date = new Date();
        return dateAsString(format, date);
    }

    private String dateAsString(String format, Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return sdf.format(date);
    }
}
