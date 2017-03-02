package com.firebirdberlin.nightdream.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

import com.firebirdberlin.nightdream.R;
import com.firebirdberlin.nightdream.HttpStatusCheckTask;
import com.firebirdberlin.nightdream.Config;
import com.firebirdberlin.nightdream.Settings;
import com.firebirdberlin.nightdream.Utility;

public class RadioStreamService extends Service implements MediaPlayer.OnErrorListener,
                                                           MediaPlayer.OnBufferingUpdateListener,
                                                           MediaPlayer.OnCompletionListener,
                                                           MediaPlayer.OnPreparedListener,
                                                           HttpStatusCheckTask.AsyncResponse {
    private static String TAG = "NightDream.RadioStreamService";
    private static String ACTION_START = "start";
    private static String ACTION_START_STREAM = "start stream";
    private static String ACTION_STOP = "stop";
    final private Handler handler = new Handler();

    static public boolean isRunning = false;
    static public boolean alarmIsRunning = false;
    private MediaPlayer mMediaPlayer = null;
    private Settings settings = null;
    private float currentVolume = 0.f;
    private int currentStreamType = AudioManager.STREAM_ALARM;
    private String streamURL = "";

    public enum StreamingMode {INACTIVE, ALARM, RADIO}
    public static StreamingMode streamingMode = StreamingMode.INACTIVE;


    @Override
    public void onCreate(){
        Log.d(TAG,"onCreate() called.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG,"onStartCommand() called.");

        settings = new Settings(this);
        isRunning = true;

        String action = intent.getAction();
        if ( ACTION_START.equals(action) ) {
            Intent i = getStopIntent(this);
            i.setAction(ACTION_STOP);
            PendingIntent pi = PendingIntent.getService(this, 0, i, 0);

            Notification note = new NotificationCompat.Builder(this)
                .setContentTitle("Radio")
                .setContentText(getString(R.string.notification_alarm))
                .setSmallIcon(R.drawable.ic_radio)
                .setContentIntent(pi)
                .build();

            note.flags |= Notification.FLAG_NO_CLEAR;
            note.flags |= Notification.FLAG_FOREGROUND_SERVICE;

            startForeground(1337, note);
            alarmIsRunning = true;
            streamingMode = StreamingMode.ALARM;
            currentStreamType = AudioManager.STREAM_ALARM;
            streamURL = settings.radioStreamURL;

            setAlarmVolume(settings.alarmVolume);
            new HttpStatusCheckTask(this).execute(streamURL);
        } else
        if ( ACTION_START_STREAM.equals(action) ) {
            sendBroadcast( new Intent(Config.ACTION_RADIO_STREAM_STARTED) );

            streamingMode = StreamingMode.RADIO;
            currentStreamType = AudioManager.STREAM_MUSIC;
            streamURL = settings.radioStreamURLUI;
            new HttpStatusCheckTask(this).execute(streamURL);
        } else
        if ( ACTION_STOP.equals(action) ) {
            stopSelf();
        }

        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy(){
        Log.d(TAG,"onDestroy() called.");

        handler.removeCallbacks(fadeIn);
        stopPlaying();
        stopForeground(false); // bool: true = remove Notification
        isRunning = false;
        alarmIsRunning = false;
        streamingMode = StreamingMode.INACTIVE;

        Intent intent = new Intent(Config.ACTION_RADIO_STREAM_STOPPED);
        sendBroadcast(intent);
    }

    private Runnable fadeIn = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(fadeIn);
            if ( mMediaPlayer == null ) return;
            currentVolume += 0.01;
            if ( currentVolume < 1. ) {
                mMediaPlayer.setVolume(currentVolume, currentVolume);
                handler.postDelayed(fadeIn, 50);
            }
        }
    };

    public void setAlarmVolume(int volume) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0);
    }

    public void onStatusCheckFinished(Boolean success) {
        if ( success ) {
            playStream();
            return;
        } else if ( alarmIsRunning ) {
            AlarmService.startAlarm(this);
        }

        Toast.makeText(this, "Radio stream failure ", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void playStream() {
        Log.i(TAG, "playStream()");

        stopPlaying();
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setAudioStreamType(currentStreamType);
        mMediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

        try {
            mMediaPlayer.setDataSource(streamURL);
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaPlayer.setDataSource() failed", e);
        } catch (IOException e) {
            Log.e(TAG, "MediaPlayer.setDataSource() failed", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "MediaPlayer.setDataSource() failed", e);
        } catch (SecurityException e) {
            Log.e(TAG, "MediaPlayer.setDataSource() failed", e);
        }

        try {
            mMediaPlayer.prepareAsync();
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaPlayer.prepare() failed", e);
        }

    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer.error: " + String.valueOf(what) + " " + String.valueOf(extra));
        if ( alarmIsRunning ) {
            AlarmService.startAlarm(this);
            stopSelf();
            return true;
        }
        return false;
    }


    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        Log.e(TAG, "onBufferingUpdate " + String.valueOf(percent));
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if ( settings.alarmFadeIn ) {
            currentVolume = 0.f;
            handler.post(fadeIn);
        };
        try {
            mp.start();
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaPlayer.start() failed", e);
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.i(TAG, "onCompletion");
        playStream();
    }

    public void stopPlaying(){
        if (mMediaPlayer != null){
            if(mMediaPlayer.isPlaying()) {
                Log.i(TAG, "stopPlaying()");
                mMediaPlayer.stop();
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    public static void start(Context context) {
        if ( ! Utility.hasNetworkConnection(context) ) {
            Toast.makeText(context, "No network connection.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(context, RadioStreamService.class);
        i.setAction(ACTION_START);
        context.startService(i);
    }

    public static void startStream(Context context) {
        if ( ! Utility.hasNetworkConnection(context) ) {
            Toast.makeText(context, "No network connection.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(context, RadioStreamService.class);
        i.setAction(ACTION_START_STREAM);
        context.startService(i);
    }

    public static void stop(Context context) {
        Intent i = getStopIntent(context);
        context.stopService(i);
    }

    private static Intent getStopIntent(Context context) {
        Intent i = new Intent(context, RadioStreamService.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }
}
