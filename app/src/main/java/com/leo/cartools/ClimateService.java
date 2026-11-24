package com.leo.cartools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 自动内外循环：根据 GPS 速度自动切换空调内外循环。
 *
 * 停车/堵车 → 内循环（防尾气）；跑起来 → 外循环（空气清新）。
 * 只改 HVAC_RECIRC_ON 一个字段，不碰温度、风量、AC、AUTO。
 *
 * 让行（暂停自动、不改变当前模式）：总开关关闭、AUTO 开、除霜开、P/R/N 档、GPS 失效。
 */
public class ClimateService extends Service {

    private static final String TAG = "CarHelper";
    private static final String CH = "climate";

    /* 属性 ID（实测确认） */
    /** 内循环：0=外循环 1=内循环 */
    private static final int PROP_RECIRC = 0x15400508;
    /** AUTO：1=关 2=开 */
    private static final int PROP_AUTO = 0x1540050A;
    /** 除霜：1=关 2=开 */
    private static final int PROP_DEFROST = 0x13400504;
    /** 档位：4=P 2=R 1=N 8=D 12=M */
    private static final int PROP_GEAR = 0x11400401;

    private static final int AREA_CLIMATE = 0x4000;
    private static final int AREA_DEFROST = 0x1;
    private static final int AREA_GLOBAL = 0x0;

    /** GPS 超过这个时间没更新，视为无效 */
    private static final long GPS_STALE_MS = 10_000L;
    /** 写入后的一小段时间内不回读覆盖内部状态，等车机生效 */
    private static final long WRITE_SETTLE_MS = 3_000L;

    private final ClimateCore core = new ClimateCore();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager lm;
    private LocationListener locationListener;

    private float speedKmh = 0f;
    private boolean gpsValid = false;
    private long lastLocationAt = 0L;
    private boolean hasPermission = false;

    private long lastWriteAt = 0L;
    private boolean lastWriteSwitched = false;

    /** 供界面读取 */
    private volatile String lastReason = "";
    private volatile int lastGear = -1;
    private volatile boolean lastAuto = false;
    private volatile boolean lastDefrost = false;
    private volatile long lastSwitchAt = 0L;

    public class LocalBinder extends Binder {
        public ClimateService service() {
            return ClimateService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "climate onCreate");

        // 恢复用户设置
        core.setEnabled(prefs().getBoolean(PREF_ENABLED, false));
        core.setThreshold(prefs().getInt(PREF_THRESHOLD, ClimateCore.DEFAULT_THRESHOLD));
        core.setHysteresis(prefs().getInt(PREF_HYST, ClimateCore.DEFAULT_HYSTERESIS));
        core.setConfirmMs(prefs().getLong(PREF_CONFIRM, ClimateCore.DEFAULT_CONFIRM_MS));

        startForeground(2, buildNotification());
        startLocation();
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        stopLocation();
        super.onDestroy();
    }

    // ---------- GPS ----------

    private void startLocation() {
        hasPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasPermission) {
            Log.w(TAG, "no location permission, auto climate idle");
            return;
        }
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (loc == null) return;
                lastLocationAt = SystemClock.elapsedRealtime();
                gpsValid = true;
                speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
                gpsValid = false;
            }
        };

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener,
                    Looper.getMainLooper());
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && last.hasSpeed()
                    && SystemClock.elapsedRealtime() - last.getTime() < 60_000L) {
                speedKmh = last.getSpeed() * 3.6f;
                lastLocationAt = SystemClock.elapsedRealtime();
                gpsValid = true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "requestLocationUpdates failed", t);
        }
    }

    private void stopLocation() {
        if (lm != null && locationListener != null) {
            try {
                lm.removeUpdates(locationListener);
            } catch (Throwable ignored) {
            }
        }
        locationListener = null;
    }

    // ---------- 主循环 ----------

    private void step() {
        // 定位权限可能在服务启动后才授予（面板先起服务再弹授权框），
        // 每秒复查一次，授予后自动启动 GPS，无需重启服务
        if (!hasPermission && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }

        long now = SystemClock.elapsedRealtime();

        Integer auto = CarProp.readInt(PROP_AUTO, AREA_CLIMATE);
        Integer defrost = CarProp.readInt(PROP_DEFROST, AREA_DEFROST);
        Integer gear = CarProp.readInt(PROP_GEAR, AREA_GLOBAL);
        Integer recirc = CarProp.readInt(PROP_RECIRC, AREA_CLIMATE);

        lastAuto = auto != null && auto == 2;
        lastDefrost = defrost != null && defrost == 2;
        lastGear = gear == null ? -1 : gear;

        core.setAutoOn(lastAuto);
        core.setDefrostOn(lastDefrost);
        core.setGear(lastGear);

        // 同步车机实际循环状态（用户手动改过时保持一致），写入后 3 秒内跳过。
        // 车机约定与 AAOS 直觉相反：0=内循环 1=外循环（实车目视确认）
        if (recirc != null && (now - lastWriteAt) > WRITE_SETTLE_MS) {
            core.seedMode(recirc == 0 ? ClimateCore.Mode.INSIDE : ClimateCore.Mode.OUTSIDE);
        }

        boolean gpsOk = gpsValid && (now - lastLocationAt) < GPS_STALE_MS;
        ClimateCore.Decision d = core.update(now, speedKmh, gpsOk);
        lastReason = reasonText(d.reason);

        if (d.shouldSwitch()) {
            // 车机约定：0=内循环 1=外循环（与 AAOS HVAC_RECIRC_ON 相反，实车确认）
            int target = d.mode == ClimateCore.Mode.INSIDE ? 0 : 1;
            boolean ok = CarProp.writeInt(PROP_RECIRC, AREA_CLIMATE, target);
            Log.i(TAG, "climate switch -> " + d.mode + " (" + d.reason + ") ok=" + ok
                    + " speed=" + Math.round(speedKmh));
            if (ok) {
                lastWriteAt = now;
                lastSwitchAt = now;
                lastWriteSwitched = true;
            }
        }
    }

    private static String reasonText(ClimateCore.Reason r) {
        switch (r) {
            case STOPPED: return "停车切内循环";
            case MOVING: return "行驶切外循环";
            case DISABLED: return "自动已关闭";
            case AUTO_ON: return "空调 AUTO 模式中";
            case DEFROST_ON: return "除霜模式中";
            case PARKED: return "P/R/N 档，不切换";
            case GPS_LOST: return "GPS 无信号";
            case MIN_DWELL: return "刚切换过，等待稳定";
            case IN_HYSTERESIS: return "速度处于中间区间";
            case PENDING: return "等待条件满足";
            case ALREADY: return "已处于目标状态";
            default: return "";
        }
    }

    // ---------- 供界面调用 ----------

    public boolean isEnabled() {
        return core.isEnabled();
    }

    public void setEnabled(boolean on) {
        core.setEnabled(on);
        prefs().edit().putBoolean(PREF_ENABLED, on).apply();
        Log.i(TAG, "climate enabled=" + on);
    }

    public int getThreshold() {
        return core.getThreshold();
    }

    public void setThreshold(int kmh) {
        core.setThreshold(kmh);
        prefs().edit().putInt(PREF_THRESHOLD, core.getThreshold()).apply();
    }

    public int getHysteresis() {
        return core.getHysteresis();
    }

    public void setHysteresis(int kmh) {
        core.setHysteresis(kmh);
        prefs().edit().putInt(PREF_HYST, core.getHysteresis()).apply();
    }

    public long getConfirmMs() {
        return core.getConfirmMs();
    }

    public void setConfirmMs(long ms) {
        core.setConfirmMs(ms);
        prefs().edit().putLong(PREF_CONFIRM, core.getConfirmMs()).apply();
    }

    public ClimateCore.Mode getMode() {
        return core.getMode();
    }

    public float getSpeedKmh() {
        return speedKmh;
    }

    public boolean hasGps() {
        return gpsValid;
    }

    public boolean hasPermission() {
        return hasPermission;
    }

    public String getReason() {
        return lastReason;
    }

    public int getGear() {
        return lastGear;
    }

    public boolean isAutoMode() {
        return lastAuto;
    }

    public boolean isDefrostMode() {
        return lastDefrost;
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("cartools", MODE_PRIVATE);
    }

    private static final String PREF_ENABLED = "climate_enabled";
    private static final String PREF_THRESHOLD = "climate_threshold";
    private static final String PREF_HYST = "climate_hysteresis";
    private static final String PREF_CONFIRM = "climate_confirm";

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Auto Climate",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CH)
                .setContentTitle("车机助手")
                .setContentText("自动内外循环运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }
}
