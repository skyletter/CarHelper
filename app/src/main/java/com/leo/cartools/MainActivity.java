package com.leo.cartools;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 控制面板：查看桥接/解锁状态，手动触发解锁或重启桥接。
 * 界面全部用代码构建，不额外引入 AppCompat 或布局资源，保持 APK 体积。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0F1116;
    private static final int CARD = 0xFF1A1E27;
    private static final int TEXT = 0xFFE8EBF0;
    private static final int SUB = 0xFF8A92A3;
    private static final int ACCENT = 0xFF2E63E8;
    private static final int OK = 0xFF35C759;
    private static final int WARN = 0xFFFF9F0A;
    private static final int BAD = 0xFFFF453A;

    private View bridgeDot, unlockDot;
    private TextView bridgeTitle, bridgeDesc;
    private TextView unlockTitle, unlockDesc;
    private Button btnUnlock;

    private BridgeService bridge;
    private boolean bound;
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof BridgeService.LocalBinder) {
                bridge = ((BridgeService.LocalBinder) service).service();
            }
            bound = true;
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            refresh();
        }
    };

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        setContentView(buildUi());

        // 桥接是核心功能，进界面即确保在跑
        startBridge();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startBridge();
        Intent it = new Intent(this, BridgeService.class);
        bound = bindService(it, conn, Context.BIND_AUTO_CREATE);
        ticker.post(refreshTask);
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(refreshTask);
        if (bound) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        bridge = null;
        super.onStop();
    }

    private void startBridge() {
        Intent it = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
    }

    /* ---------------- 状态刷新 ---------------- */

    private void refresh() {
        boolean running = bridge != null;

        if (!running) {
            setDot(bridgeDot, WARN);
            bridgeTitle.setText("未运行");
            bridgeTitle.setTextColor(WARN);
            bridgeDesc.setText("服务未启动，点击下方「重启桥接」");
        } else {
            setDot(bridgeDot, OK);
            bridgeTitle.setText("运行中");
            bridgeTitle.setTextColor(OK);
            if (bridge.isNavActive()) {
                String dis = bridge.getNavDistance();
                bridgeDesc.setText(dis == null
                        ? "正在导航，仪表已同步"
                        : "正在导航 · 距路口 " + dis);
            } else {
                long ago = bridge.getLastRxAgoMs();
                bridgeDesc.setText(ago < 0
                        ? "等待高德开始导航"
                        : "未在导航 · 上次数据 " + Math.max(1, ago / 1000) + " 秒前");
            }
        }

        boolean unlocked = UnlockService.isUnlocked(this);
        if (unlocked) {
            setDot(unlockDot, OK);
            unlockTitle.setText("已解锁");
            unlockTitle.setTextColor(OK);
            unlockDesc.setText("本次开机已解锁，第三方播放器有声音");
            btnUnlock.setText("重新解锁");
            btnUnlock.setBackground(roundRect(0xFF262B36, 12));
            btnUnlock.setTextColor(SUB);
        } else {
            setDot(unlockDot, BAD);
            unlockTitle.setText("未解锁");
            unlockTitle.setTextColor(BAD);
            unlockDesc.setText("第三方播放器可能没有声音");
            btnUnlock.setText("立即解锁音源");
            btnUnlock.setBackground(roundRect(ACCENT, 12));
            btnUnlock.setTextColor(Color.WHITE);
        }
    }

    private void setDot(View v, int color) {
        ((GradientDrawable) v.getBackground()).setColor(color);
    }

    /* ---------------- 交互 ---------------- */

    private void doUnlock() {
        UnlockService.clearUnlocked(this);
        startService(new Intent(this, UnlockService.class));
        Toast.makeText(this, "已发起解锁，约 8 秒后生效", Toast.LENGTH_SHORT).show();
        btnUnlock.setEnabled(false);
        ticker.postDelayed(() -> {
            btnUnlock.setEnabled(true);
            refresh();
        }, 9000);
        refresh();
    }

    private void restartBridge() {
        stopService(new Intent(this, BridgeService.class));
        if (bound) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        bridge = null;
        startBridge();
        Intent it = new Intent(this, BridgeService.class);
        bound = bindService(it, conn, Context.BIND_AUTO_CREATE);
        Toast.makeText(this, "桥接已重启", Toast.LENGTH_SHORT).show();
        ticker.postDelayed(this::refresh, 600);
    }

    /* ---------------- 界面 ---------------- */

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        root.setPadding(p, dp(30), p, dp(30));
        scroll.addView(root);

        // 顶部：图标 + 名称
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(60), dp(60));
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(16);
        titles.setLayoutParams(tlp);
        head.addView(titles);

        TextView name = new TextView(this);
        name.setText("车机助手");
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        titles.addView(name);

        TextView ver = new TextView(this);
        ver.setText("v" + versionName() + " · C211 导航桥接 + 音源解锁");
        ver.setTextColor(SUB);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setPadding(0, dp(4), 0, 0);
        titles.addView(ver);

        // 卡片 1：桥接
        LinearLayout card1 = card();
        root.addView(card1);
        ((LinearLayout.LayoutParams) card1.getLayoutParams()).topMargin = dp(26);

        LinearLayout row1 = row();
        card1.addView(row1);
        bridgeDot = dot();
        row1.addView(bridgeDot);
        TextView t1 = label("导航仪表桥接");
        row1.addView(t1);
        bridgeTitle = status();
        row1.addView(bridgeTitle);
        bridgeDesc = desc();
        card1.addView(bridgeDesc);

        // 卡片 2：音源解锁
        LinearLayout card2 = card();
        root.addView(card2);
        ((LinearLayout.LayoutParams) card2.getLayoutParams()).topMargin = dp(14);

        LinearLayout row2 = row();
        card2.addView(row2);
        unlockDot = dot();
        row2.addView(unlockDot);
        TextView t2 = label("音源解锁");
        row2.addView(t2);
        unlockTitle = status();
        row2.addView(unlockTitle);
        unlockDesc = desc();
        card2.addView(unlockDesc);

        // 按钮
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        btns.setLayoutParams(blp);
        root.addView(btns);

        btnUnlock = new Button(this);
        btnUnlock.setAllCaps(false);
        btnUnlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnUnlock.setText("立即解锁音源");
        btnUnlock.setBackground(roundRect(ACCENT, 12));
        btnUnlock.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        btnUnlock.setLayoutParams(l1);
        btnUnlock.setOnClickListener(v -> doUnlock());
        btns.addView(btnUnlock);

        Button btnRestart = new Button(this);
        btnRestart.setAllCaps(false);
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnRestart.setText("重启桥接");
        btnRestart.setBackground(roundRect(0xFF262B36, 12));
        btnRestart.setTextColor(TEXT);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        l2.leftMargin = dp(12);
        btnRestart.setLayoutParams(l2);
        btnRestart.setOnClickListener(v -> restartBridge());
        btns.addView(btnRestart);

        // 底部说明
        TextView foot = new TextView(this);
        foot.setText("把本应用加入车机自启动白名单后，重启车机自动生效，无需再打开此页面。");
        foot.setTextColor(SUB);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        foot.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(24);
        foot.setLayoutParams(flp);
        root.addView(foot);

        return scroll;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(CARD, 16));
        int p = dp(18);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View dot() {
        View v = new View(this);
        v.setBackground(roundRect(SUB, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
        lp.rightMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView status() {
        TextView t = new TextView(this);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView desc() {
        TextView t = new TextView(this);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(18);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
