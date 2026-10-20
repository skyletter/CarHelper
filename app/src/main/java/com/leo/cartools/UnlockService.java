package com.leo.cartools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * 音源解锁：调用车机 fce_misc_service_source_ctrl.requestSource("app")，
 * 解除第三方播放器的功放静音。
 *
 * AIDL: com.fce.misc.proxy.source.ISourceCtrl
 * 事务码 requestSource = 1
 */
public class UnlockService extends Service {

    private static final String TAG = "CarHelper";

    private static final String SVC_NAME = "fce_misc_service_source_ctrl";
    private static final String SVC_DESC = "com.fce.misc.proxy.source.ISourceCtrl";
    private static final int TRANSACTION_requestSource = 1;
    private static final String TARGET_SOURCE = "app";

    private static final long FIRST_DELAY_MS = 8000L;
    private static final long RETRY_DELAY_MS = 5000L;
    private static final int MAX_ATTEMPTS = 6;

    private static final String PREF = "cartools";
    private static final String KEY_UNLOCKED_AT = "unlocked_elapsed";

    private static volatile boolean sRunning = false;

    /**
     * 本次开机是否已解锁过。
     * 用 elapsedRealtime 记录解锁时刻；重启后该时钟归零重计，因此
     * “当前值 < 记录值”即代表车机重启过，需要重新解锁。
     * <p>
     * 用途：避免用户每次点图标都重跑一次 requestSource，打断正在播放的音源。
     */
    public static boolean isUnlocked(Context ctx) {
        long at = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(KEY_UNLOCKED_AT, -1L);
        return at >= 0 && SystemClock.elapsedRealtime() >= at;
    }

    /** 开机时清掉标记，保证重启后一定会重新解锁。 */
    public static void clearUnlocked(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_UNLOCKED_AT).apply();
    }

    private void markUnlocked() {
        getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_UNLOCKED_AT, SystemClock.elapsedRealtime()).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (sRunning) {
            Log.i(TAG, "unlock already running");
            return START_NOT_STICKY;
        }
        sRunning = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    unlock();
                } finally {
                    sRunning = false;
                    stopSelf();
                }
            }
        }, "unlock-worker");
        t.setDaemon(true);
        t.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void unlock() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            sleep(attempt == 1 ? FIRST_DELAY_MS : RETRY_DELAY_MS);

            IBinder binder = getServiceBinder();
            if (binder == null) {
                Log.w(TAG, "unlock attempt " + attempt + ": service not ready");
                continue;
            }

            int ret = callRequestSource(binder, TARGET_SOURCE);
            Log.i(TAG, "unlock attempt " + attempt + ": requestSource(app) -> " + ret);

            if (ret == -1) {
                Log.w(TAG, "binder failed (attempt " + attempt + ")");
                if (attempt < MAX_ATTEMPTS) continue;
                Log.w(TAG, "all attempts failed, root fallback");
                rootFallback();
                return;
            }
            // ret == 1 成功；ret == 0 表示音源已是目标值（幂等），同样算解锁完成
            markUnlocked();
            Log.i(TAG, "unlock done (ret=" + ret + ")");
            return;
        }
        Log.w(TAG, "unlock give up");
    }

    private IBinder getServiceBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object obj = sm.getMethod("getService", String.class).invoke(null, SVC_NAME);
            if (obj instanceof IBinder) return (IBinder) obj;
        } catch (Throwable t) {
            Log.e(TAG, "getService fail: " + t);
        }
        return null;
    }

    private int callRequestSource(IBinder binder, String sourceId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC_DESC);
            data.writeString(sourceId);
            boolean ok = binder.transact(TRANSACTION_requestSource, data, reply, 0);
            if (!ok) return -1;                 // binder 调用未完成，按失败重试
            reply.readException();
            return reply.readInt() != 0 ? 1 : 0;
        } catch (Throwable t) {
            Log.e(TAG, "transact fail: " + t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void rootFallback() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "0", "service", "call", SVC_NAME, "1", "s16", TARGET_SOURCE
            });
            if (!p.waitFor(6, TimeUnit.SECONDS)) {
                Log.w(TAG, "root fallback timeout");
                p.destroy();
                return;
            }
            int exit = p.exitValue();
            Log.i(TAG, "root fallback exit=" + exit);
            if (exit == 0) markUnlocked();
        } catch (Throwable t) {
            Log.e(TAG, "root fallback fail: " + t);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
