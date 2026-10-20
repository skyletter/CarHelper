package com.leo.cartools;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

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

    private static volatile boolean sRunning = false;

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
                Log.w(TAG, "binder failed, root fallback");
                rootFallback();
                return;
            }
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
            if (!ok) return 0;
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
            Log.i(TAG, "root fallback exit=" + p.waitFor());
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
