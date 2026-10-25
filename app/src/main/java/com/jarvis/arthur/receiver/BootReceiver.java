package com.jarvis.arthur.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.service.VoiceListenerService;

/**
 * Ensures Jarvis starts and runs in the background automatically across device boots
 * once installed and opened for the first time.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "BootReceiver received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SettingsManager settings = new SettingsManager(context);
            if (settings.isBackgroundServiceEnabled()) {
                Log.i(TAG, "Starting VoiceListenerService in background after boot...");
                try {
                    Intent serviceIntent = new Intent(context, VoiceListenerService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start background VoiceListenerService on boot: " + e.getMessage());
                }
            }
        }
    }
}
