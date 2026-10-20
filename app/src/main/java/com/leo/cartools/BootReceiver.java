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
        // 重启后功放会重新静音，清标记保证一定会再解锁一次
        UnlockService.clearUnlocked(context);
        context.startService(new Intent(context, UnlockService.class));
    }
}
