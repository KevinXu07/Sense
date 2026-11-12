package com.zephiel.sense.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.zephiel.sense.util.SenseLog;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.zephiel.sense.RESTART_SERVICE".equals(action)) {

            SenseLog.subI(TAG, "Starting service after action=" + action);
            Intent serviceIntent = new Intent(context, SenseService.class);
            serviceIntent.setAction("UPDATE_SETTINGS");
            try {
                context.startService(serviceIntent);
            } catch (Throwable t) {
                SenseLog.subE(TAG, "Failed to start service from action=" + action, t);
            }
        }
    }
}
