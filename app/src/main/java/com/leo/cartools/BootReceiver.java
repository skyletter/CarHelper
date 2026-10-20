package com.leo.cartools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent bridge = new Intent(context, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(bridge);
        } else {
            context.startService(bridge);
        }
        context.startService(new Intent(context, UnlockService.class));
    }
}
