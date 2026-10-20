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
    private static final long FAST_DELAY_MS = 600L;
    private static final long RETRY_DELAY_MS = 5000L;
    private static final int MAX_ATTEMPTS = 6;

    /** 面板手动触发时置 true，跳过开机那段 8 秒等待 */
    public static final String EXTRA_FAST = "fast";

    private static final String PREF = "cartools";
    private static final String KEY_BOOT_TIME = "unlock_boot_time";
    /** 开机时刻允许的误差 */
    private static final long BOOT_TIME_TOLERANCE_MS = 5000L;

    private static volatile boolean sRunning = false;

    /** 当前这次开机的时刻；重启后该值会变化 */
    private static long currentBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /**
     * 本次开机是否已解锁过。
     * 记录的是「解锁时的开机时刻」，重启后开机时刻不同 → 自动判定为未解锁。
     * 比用 elapsedRealtime 直接比较更稳（后者在 BOOT_COMPLETED 未送达时会误判）。
     */
    public static boolean isUnlocked(Context ctx) {
        long boot = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(KEY_BOOT_TIME, Long.MIN_VALUE);
        if (boot != Long.MIN_VALUE
                && Math.abs(currentBootTime() - boot) < BOOT_TIME_TOLERANCE_MS) {
            return true;
        }
        // 没有本次开机的标记（例如刚覆盖安装、或旧版本解锁的）时，
        // 直接问车机当前音源，避免状态显示错误
        if (AudioSource.isSourceUnmuted()) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_BOOT_TIME, currentBootTime()).apply();
            return true;
        }
        return false;
    }

    /** 开机时清掉标记，保证重启后一定会重新解锁。 */
    public static void clearUnlocked(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_BOOT_TIME).apply();
    }

    private void markUnlocked() {
        getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_BOOT_TIME, currentBootTime()).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (sRunning) {
            Log.i(TAG, "unlock already running");
            return START_NOT_STICKY;
        }
        sRunning = true;

        final boolean fast = intent != null && intent.getBooleanExtra(EXTRA_FAST, false);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    unlock(fast);
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

    private void unlock(boolean fast) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            sleep(attempt == 1 ? (fast ? FAST_DELAY_MS : FIRST_DELAY_MS) : RETRY_DELAY_MS);

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
            data.writeInt(0);                   // AIDL: requestSource(String, boolean force)
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
