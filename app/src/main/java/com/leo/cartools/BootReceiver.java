package com.leo.cartools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "?" : intent.getAction();
        Log.i(TAG, "boot action=" + action);

        startSvc(context, new Intent(context, BridgeService.class));
        startSvc(context, new Intent(context, ClimateService.class));
        // 重启后功放会重新静音，清标记保证一定会再解锁一次
        UnlockService.clearUnlocked(context);
        context.startService(new Intent(context, UnlockService.class));

        tryRootSetup();
    }

    private static void startSvc(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /**
     * root 增强（车机自用，失败静默）：
     *  1. 静默授予定位权限——避免重装/清数据后开机循环因无权限空转
     *  2. 加入电池优化白名单——防 Doze/App Standby 回收前台服务
     */
    private static void tryRootSetup() {
        new Thread(() -> {
            String[] cmds = {
                "pm grant " + "com.leo.cartools" + " android.permission.ACCESS_FINE_LOCATION",
                "dumpsys deviceidle whitelist +" + "com.leo.cartools",
            };
            for (String c : cmds) {
                runSu(c);
            }
            Log.i(TAG, "root setup done");
        }, "boot-root").start();
    }

    private static void runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", cmd});
            long deadline = SystemClock.elapsedRealtime() + 8000L;
            while (true) {
                try {
                    p.exitValue();
                    return;
                } catch (IllegalThreadStateException still) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        p.destroy();
                        return;
                    }
                    Thread.sleep(200L);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "root setup fail: " + cmd);
            if (p != null) p.destroy();
        }
    }
}
