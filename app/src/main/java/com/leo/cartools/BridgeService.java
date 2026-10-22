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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 把高德 8.x 的标准导航广播，转换成长安仪表的 OEM 协议广播。
 *
 * 数据来源：AUTONAVI_STANDARD_BROADCAST_SEND
 *   KEY_TYPE=10001  NEW_ICON(扩展转向) / ICON / SEG_REMAIN_DIS / NEXT_SEG_REMAIN_DIS
 *   KEY_TYPE=10019  EXTRA_STATE(开始/结束导航、巡航)
 * 目标：com.autonavi.navigation.NAVIGATION_UPDATES
 *   navigation_id(仪表图标ID) / navigation_dis("米,m")
 */
public class BridgeService extends Service {
    private static final String TAG = "CarHelper";
    private static final String CH = "navbridge";

    private static final String ACTION_AMAP = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_CLUSTER = "com.autonavi.navigation.NAVIGATION_UPDATES";

    private static final int KEY_NAVI_INFO = 10001;
    private static final int KEY_NAV_STATE = 10019;

    private BroadcastReceiver rx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BridgeCore core = new BridgeCore();

    /** 供界面直接读取运行状态 */
    public class LocalBinder extends Binder {
        public BridgeService service() {
            return BridgeService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();

    private long lastRxAt = 0;
    /** 避免映射表外的动作码每次广播都重复打日志 */
    private int loggedIcon = Integer.MIN_VALUE;
    private int loggedNewIcon = Integer.MIN_VALUE;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            BridgeCore.Update u = core.tick(System.currentTimeMillis());
            if (u.send) {
                Log.i(TAG, "nav timeout, clear cluster");
                send(u.icon, u.dis);
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

        long now = System.currentTimeMillis();

        // 导航状态：结束导航/进入巡航时立即清屏，不必等 9 秒超时
        if (type == KEY_NAV_STATE) {
            lastRxAt = now;
            BridgeCore.Update u = core.onNavState(now, i.getIntExtra("EXTRA_STATE", -1));
            if (u.send) send(u.icon, u.dis);
            return;
        }

        if (type != KEY_NAVI_INFO) return;

        int icon = i.getIntExtra("ICON", -1);
        int newIcon = i.getIntExtra("NEW_ICON", -1);
        int dis = i.getIntExtra("SEG_REMAIN_DIS", -1);
        int nextDis = i.getIntExtra("NEXT_SEG_REMAIN_DIS", -1);
        String disAuto = i.getStringExtra("SEG_REMAIN_DIS_AUTO");
        String nextDisAuto = i.getStringExtra("NEXT_SEG_REMAIN_DIS_AUTO");

        lastRxAt = now;

        BridgeCore.Update u = core.onGuidance(now, icon, newIcon, dis, disAuto, nextDis, nextDisAuto);
        if (u.send) {
            send(u.icon, u.dis);
        } else if (u.reason == BridgeCore.Reason.UNMAPPED_ICON
                && (icon != loggedIcon || newIcon != loggedNewIcon)) {
            // 把映射表外的动作码原样打出来，方便下一版补齐
            Log.i(TAG, "skip unmapped ICON=" + icon + " NEW_ICON=" + newIcon
                    + " dis=" + dis + "/" + disAuto
                    + " next=" + nextDis + "/" + nextDisAuto);
            loggedIcon = icon;
            loggedNewIcon = newIcon;
        }
    }

    private void send(int clusterIcon, String disStr) {
        Intent it = new Intent(ACTION_CLUSTER);
        it.putExtra("navigation_id", clusterIcon);
        it.putExtra("navigation_dis", disStr);
        sendBroadcast(it);

        Log.i(TAG, "-> cluster icon=" + clusterIcon + " dis=" + disStr);
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
        return core.isActive();
    }

    /** 返回 "1.7 公里" / "500 米" 这样的可读距离，无导航时返回 null */
    public String getNavDistance() {
        String dis = core.getLastDisStr();
        if (!core.isActive() || dis == null || dis.isEmpty()) return null;
        if (dis.endsWith(",km")) {
            return dis.substring(0, dis.length() - 3) + " 公里";
        }
        if (dis.endsWith(",m")) {
            return dis.substring(0, dis.length() - 2) + " 米";
        }
        return dis;
    }

    public long getLastRxAgoMs() {
        return lastRxAt == 0 ? -1 : System.currentTimeMillis() - lastRxAt;
    }
}
