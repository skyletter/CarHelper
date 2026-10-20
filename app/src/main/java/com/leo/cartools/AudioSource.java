package com.leo.cartools;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * 只读音源状态，用于判断「音源解锁」是否真的生效。
 *
 * 服务：fce_misc_service_source_msg
 * AIDL：com.fce.misc.proxy.source.ISourceMsg
 * 事务码：1 = getCurrentSource() -> String
 *
 * 实测：解锁后返回 "app"（未解锁时返回其它源，如 "usb"/"navi"）。
 */
public class AudioSource {

    private static final String SVC = "fce_misc_service_source_msg";
    private static final String DESC = "com.fce.misc.proxy.source.ISourceMsg";
    private static final int TX_CURRENT = 1;

    private static final long CACHE_MS = 2000;

    private static IBinder binder;
    private static String cached;
    private static long cachedAt;

    /** 当前音源，失败返回 null */
    public static String current() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;

        IBinder b = binder();
        if (b == null) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            if (!b.transact(TX_CURRENT, data, reply, 0)) return null;
            reply.readException();
            String s = reply.readString();
            cached = s;
            cachedAt = now;
            return s;
        } catch (Throwable t) {
            binder = null;
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 当前是否已经是 app 音源（即已解锁） */
    public static boolean isAppActive() {
        String s = current();
        return s != null && "app".equalsIgnoreCase(s.trim());
    }

    /**
     * 功放是否已经放行：app（第三方播放器）或 navi（导航播报）都说明没有静音。
     * 导航进行中音源会临时切到 navi，此时不应误判为「未解锁」。
     */
    public static boolean isSourceUnmuted() {
        String s = current();
        if (s == null) return false;
        s = s.trim();
        return "app".equalsIgnoreCase(s) || "navi".equalsIgnoreCase(s);
    }

    private static IBinder binder() {
        if (binder != null && binder.isBinderAlive()) return binder;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            Object o = m.invoke(null, SVC);
            if (o instanceof IBinder) {
                binder = (IBinder) o;
                return binder;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
