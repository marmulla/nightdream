package com.firebirdberlin.nightdream;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.firebirdberlin.nightdream.events.OnNewLightSensorValue;
import com.firebirdberlin.nightdream.receivers.ScreenReceiver;

import de.greenrobot.event.EventBus;

public class NightModeListener extends Service {
    private static String TAG = "NightModeListener";
    private static String ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED";
    final private Handler handler = new Handler();
    private SoundMeter soundmeter;
    private ReceiverPowerDisconnected pwrReceiver = null;
    private LightSensorEventListener lightSensorEventListener = null;
    private boolean reactivate_on_noise = false;
    private int reactivate_on_ambient_light_value = 30;

    private boolean error_on_microphone = false;
    PowerManager.WakeLock wakelock;


    private double maxAmplitude = Config.NOISE_AMPLITUDE_WAKE;
    private static int measurementMillis = 5000;

    private boolean debug = true;
    private ScreenOnBroadcastReceiver broadcastReceiver = null;

    class ScreenOnBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(TAG, "Screen turned on ... stopSelf();");
                stopService();
            }
        }
    }

    @Override
    public void onCreate(){
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakelock.acquire();
        Settings settings = new Settings(this);
        reactivate_on_noise = settings.reactivateScreenOnNoise();
        maxAmplitude *= settings.sensitivity;
        reactivate_on_ambient_light_value = settings.reactivate_on_ambient_light_value;

        if (debug){
            Log.d(TAG,"onCreate() called.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (debug){
            Log.d(TAG,"onStartCommand() called.");
        }

        pwrReceiver = registerPowerDisconnectionReceiver();
        broadcastReceiver = registerScreenOnBroadcastReceiver();

        Intent i = new Intent(this, NightDreamActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP| Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);

        NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(this)
            .setSmallIcon(R.drawable.ic_nightdream)
            .setOngoing(true)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("The night mode listener is active.")
            .setContentIntent(pi); //Required on Gingerbread and below

        Notification note = mBuilder.build();
        note.flags|=Notification.FLAG_NO_CLEAR;
        note.flags|=Notification.FLAG_FOREGROUND_SERVICE;
        startForeground(1337, note);

        if (reactivate_on_noise ) {
            handler.postDelayed(startRecording, 1000);
        }

        registerEventBus();

        lightSensorEventListener = new LightSensorEventListener(this);
        lightSensorEventListener.register();

        return Service.START_REDELIVER_INTENT;
    }

    private void registerEventBus() {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy(){
        if (debug){
            Log.d(TAG,"onDestroy() called.");
        }

        if (soundmeter != null){
            soundmeter.release();
            soundmeter = null;
        }

        EventBus.getDefault().unregister(this);
        unregisterReceiver(pwrReceiver);
        unregister(broadcastReceiver);
        lightSensorEventListener.unregister();

        if (wakelock.isHeld()){
            wakelock.release();
        }
    }

    private Runnable startRecording = new Runnable() {
        @Override
        public void run() {
            soundmeter = null;
            soundmeter = new SoundMeter(true);
            boolean result = soundmeter.start();
            error_on_microphone = !result;
            handler.postDelayed(getAmplitude, measurementMillis);
        }
    };

    private Runnable getAmplitude = new Runnable() {
        @Override
        public void run() {
            if (error_on_microphone || soundmeter == null) {
                Log.w(TAG,"mic reported error!");
                stopService();
                return;
            }

            double last_amplitude = soundmeter.getAmplitude();
            Log.i(TAG,"last amplitude :" + String.valueOf(last_amplitude));
            if (last_amplitude > maxAmplitude){
                stopService();
                return;
            }

            handler.postDelayed(getAmplitude, measurementMillis);
        }
    };

    private void stopService() {
        Log.i(TAG, "stopService()");

        stopForeground(false); // bool: true = remove Notification
        startApp();
        stopSelf();
    }

    private void startApp(){
        Bundle bundle = new Bundle();
        bundle.putString("action", "start night mode");
        NightDreamActivity.start(this, bundle);
    }

    private void restoreRingerMode() {
        mAudioManager audioManager = new mAudioManager(this);
        audioManager.restoreRingerMode();
    }

    class ReceiverPowerDisconnected extends BroadcastReceiver{
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            Log.d(TAG,"Power was disconnected ... stopSelf();");
            restoreRingerMode();
            stopSelf();
        }
    }

    private ReceiverPowerDisconnected registerPowerDisconnectionReceiver(){
        ReceiverPowerDisconnected pwrReceiver = new ReceiverPowerDisconnected();
        IntentFilter pwrFilter = new IntentFilter(ACTION_POWER_DISCONNECTED);
        registerReceiver(pwrReceiver, pwrFilter);
        return pwrReceiver;
    }

    private ScreenOnBroadcastReceiver registerScreenOnBroadcastReceiver(){
        Log.d(TAG, "registerScreenOnBroadcastReceiver()");
        ScreenOnBroadcastReceiver receiver = new ScreenOnBroadcastReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(receiver, filter);
        return receiver;
    }

    private void unregister(BroadcastReceiver receiver) {
        try {
            unregisterReceiver(receiver);
        } catch ( IllegalArgumentException e ) {

        }
    }

    public void onEvent(OnNewLightSensorValue event){
        if (event.value > (float) reactivate_on_ambient_light_value) {
            Log.d(TAG,"It's getting bright ... stopSelf();");
            stopService();
        }
    }
}
