package com.leo.cartools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 把高德 8.x 的标准导航广播，转换成长安仪表的 OEM 协议广播。
 *
 * 数据来源：AUTONAVI_STANDARD_BROADCAST_SEND / KEY_TYPE=10001
 *   ICON(转向ID) / SEG_REMAIN_DIS(到路口距离)
 * 目标：com.autonavi.navigation.NAVIGATION_UPDATES
 *   navigation_id(仪表图标ID) / navigation_dis("米,m")
 */
public class BridgeService extends Service {
    private static final String TAG = "CarHelper";
    private static final String CH = "navbridge";

    private static final String ACTION_AMAP = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_CLUSTER = "com.autonavi.navigation.NAVIGATION_UPDATES";

    private static final int KEY_NAVI_INFO = 10001;
    private static final long RX_TIMEOUT_MS = 9000;   // 超过 9 秒没数据视为导航结束
    private static final long MIN_SEND_INTERVAL = 800;

    private BroadcastReceiver rx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 供界面直接读取运行状态 */
    public class LocalBinder extends Binder {
        public BridgeService service() {
            return BridgeService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();

    private int lastIcon = -1;
    private String lastDisStr = "";
    private long lastSendAt = 0;
    private long lastRxAt = 0;
    private boolean active = false;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (active && now - lastRxAt > RX_TIMEOUT_MS) {
                Log.i(TAG, "nav timeout, clear cluster");
                stopNav();
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service onCreate");

        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                if (ACTION_AMAP.equals(i.getAction())) {
                    onAmap(i);
                }
            }
        };

        IntentFilter f = new IntentFilter(ACTION_AMAP);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(rx, f);

        startForeground(1, buildNotification());
        handler.postDelayed(watchdog, 2000);
    }

    private void onAmap(Intent i) {
        int type;
        try {
            type = i.getIntExtra("KEY_TYPE", -1);
        } catch (Throwable t) {
            return;
        }
        if (type != KEY_NAVI_INFO) return;

        int icon = i.getIntExtra("ICON", -1);
        if (icon <= 0) icon = i.getIntExtra("NEW_ICON", -1);
        int dis = i.getIntExtra("SEG_REMAIN_DIS", -1);
        String disAuto = i.getStringExtra("SEG_REMAIN_DIS_AUTO");

        int clusterIcon = IconMap.toCluster(icon);

        // 先续命再看图标：映射表里没有的新图标只跳过本次更新，
        // 不应让 watchdog 误判导航结束而清空仪表
        lastRxAt = System.currentTimeMillis();
        active = true;

        if (clusterIcon <= 0) return;          // 未知图标：保留上一次

        String disStr = formatDis(disAuto, dis);
        if (disStr == null) return;

        long now = lastRxAt;
        boolean iconChanged = clusterIcon != lastIcon;
        boolean disChanged = !disStr.equals(lastDisStr);
        boolean timeUp = now - lastSendAt >= MIN_SEND_INTERVAL;

        if (iconChanged || (disChanged && (timeUp || lastSendAt == 0))) {
            send(clusterIcon, disStr);
        }
    }

    /**
     * 优先用高德自己的显示文本（例如 "1.7公里" / "500米"），
     * 避免"1790 米"被仪表四舍五入成 1.8km。
     */
    private String formatDis(String disAuto, int meters) {
        if (disAuto != null) {
            disAuto = disAuto.trim();
            try {
                if (disAuto.endsWith("公里")) {
                    String num = disAuto.substring(0, disAuto.length() - 2).trim();
                    if (num.length() > 0) return num + ",km";
                } else if (disAuto.endsWith("米")) {
                    String num = disAuto.substring(0, disAuto.length() - 1).trim();
                    if (num.length() > 0) return Integer.parseInt(num) + ",m";
                }
            } catch (Throwable ignored) {
            }
        }
        if (meters < 0) return null;
        return meters + ",m";
    }

    private void send(int clusterIcon, String disStr) {
        Intent it = new Intent(ACTION_CLUSTER);
        it.putExtra("navigation_id", clusterIcon);
        it.putExtra("navigation_dis", disStr);
        sendBroadcast(it);

        lastIcon = clusterIcon;
        lastDisStr = disStr;
        lastSendAt = System.currentTimeMillis();
        Log.i(TAG, "-> cluster icon=" + clusterIcon + " dis=" + disStr);
    }

    private void stopNav() {
        if (!active) return;
        active = false;
        lastIcon = -1;
        lastDisStr = "";
        lastSendAt = 0;

        Intent it = new Intent(ACTION_CLUSTER);
        it.putExtra("navigation_id", 0);
        it.putExtra("navigation_dis", "0,m");
        sendBroadcast(it);
        Log.i(TAG, "-> cluster clear");
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "NavBridge", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CH)
                .setContentTitle("车机助手")
                .setContentText("导航仪表桥接运行中")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (rx != null) {
            try {
                unregisterReceiver(rx);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /* ---------- 界面只读状态 ---------- */

    public boolean isNavActive() {
        return active;
    }

    /** 返回 "1.7 公里" / "500 米" 这样的可读距离，无导航时返回 null */
    public String getNavDistance() {
        if (!active || lastDisStr == null || lastDisStr.isEmpty()) return null;
        if (lastDisStr.endsWith(",km")) {
            return lastDisStr.substring(0, lastDisStr.length() - 3) + " 公里";
        }
        if (lastDisStr.endsWith(",m")) {
            return lastDisStr.substring(0, lastDisStr.length() - 2) + " 米";
        }
        return lastDisStr;
    }

    public long getLastRxAgoMs() {
        return lastRxAt == 0 ? -1 : System.currentTimeMillis() - lastRxAt;
    }
}
