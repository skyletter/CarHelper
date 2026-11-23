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
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
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
    private Button btnRestartBridge;

    private BridgeService bridge;
    private boolean bound;
    private final Handler ticker = new Handler(Looper.getMainLooper());

    /* ---------- 自动内外循环 ---------- */
    private ClimateService climate;
    private boolean climateBound;
    private View climateDot;
    private TextView climateTitle;
    private TextView climateDesc;
    private Button btnClimateToggle;
    private EditText etThreshold;
    private EditText etConfirm;

    private final ServiceConnection climateConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof ClimateService.LocalBinder) {
                climate = ((ClimateService.LocalBinder) service).service();
            }
            climateBound = true;
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            climate = null;
            refresh();
        }
    };

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

        // 开机自启拉起本界面时，只启动服务、不显示面板（60 秒内视为开机阶段）
        if (SystemClock.elapsedRealtime() < 60_000L) {
            startBridge();
            startClimate();
            finish();
            return;
        }

        getWindow().setStatusBarColor(BG);
        setContentView(buildUi());

        // 桥接是核心功能，进界面即确保在跑
        startBridge();

        requestLocationIfNeeded();
    }

    private void requestLocationIfNeeded() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            refresh();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        startBridge();
        Intent it = new Intent(this, BridgeService.class);
        bound = bindService(it, conn, Context.BIND_AUTO_CREATE);

        startClimate();
        Intent ci = new Intent(this, ClimateService.class);
        climateBound = bindService(ci, climateConn, Context.BIND_AUTO_CREATE);

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
        if (climateBound) {
            try {
                unbindService(climateConn);
            } catch (Throwable ignored) {
            }
            climateBound = false;
        }
        bridge = null;
        climate = null;
        super.onStop();
    }

    @Override
    protected void onPause() {
        // 兜底保存：按 Home、切后台等离开路径不一定触发焦点丢失，
        // 在暂停时把两个输入框的当前值落盘，防止刷新逻辑用默认值覆盖
        applyThreshold();
        applyConfirm();
        super.onPause();
    }

    private void startClimate() {
        Intent it = new Intent(this, ClimateService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
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

        refreshClimate();
    }

    private void refreshClimate() {
        if (climateDot == null) {
            return;
        }
        boolean on = climate != null && climate.isEnabled();
        boolean perm = climate != null && climate.hasPermission();

        if (climate == null) {
            setDot(climateDot, WARN);
            climateTitle.setText("未运行");
            climateTitle.setTextColor(WARN);
            climateDesc.setText("服务未启动");
        } else if (!on) {
            setDot(climateDot, SUB);
            climateTitle.setText("已关闭");
            climateTitle.setTextColor(SUB);
            climateDesc.setText("打开开关后：停车自动切内循环，行驶自动切外循环");
        } else if (!perm) {
            setDot(climateDot, WARN);
            climateTitle.setText("缺少定位权限");
            climateTitle.setTextColor(WARN);
            climateDesc.setText("需要授予定位权限才能获取车速");
        } else {
            boolean inside = climate.getMode() == ClimateCore.Mode.INSIDE;
            setDot(climateDot, inside ? ACCENT : OK);
            climateTitle.setText(inside ? "内循环" : "外循环");
            climateTitle.setTextColor(inside ? ACCENT : OK);
            String speed = climate.hasGps()
                    ? Math.round(climate.getSpeedKmh()) + " km/h"
                    : "GPS 无信号";
            climateDesc.setText(speed + " · " + climate.getReason());
        }

        if (btnClimateToggle != null) {
            btnClimateToggle.setText(on ? "关闭自动" : "开启自动");
            btnClimateToggle.setBackground(roundRect(on ? 0xFF262B36 : ACCENT, 10));
            btnClimateToggle.setTextColor(on ? SUB : Color.WHITE);
        }

        if (etThreshold != null && climate != null && !etThreshold.hasFocus()) {
            String want = String.valueOf(climate.getThreshold());
            if (!want.equals(etThreshold.getText().toString())) {
                etThreshold.setText(want);
            }
        }

        if (etConfirm != null && climate != null && !etConfirm.hasFocus()) {
            String want = String.valueOf(climate.getConfirmMs() / 1000L);
            if (!want.equals(etConfirm.getText().toString())) {
                etConfirm.setText(want);
            }
        }
    }

    private void applyThreshold() {
        if (climate == null || etThreshold == null) {
            return;
        }
        String s = etThreshold.getText().toString().trim();
        if (s.isEmpty()) {
            etThreshold.setText(String.valueOf(climate.getThreshold()));
            return;
        }
        try {
            int v = Integer.parseInt(s);
            climate.setThreshold(v);
            int clamped = climate.getThreshold();
            etThreshold.setText(String.valueOf(clamped));
            if (v != clamped) {
                Toast.makeText(this,
                        "阈值限定在 " + ClimateCore.MIN_THRESHOLD + "~" + ClimateCore.MAX_THRESHOLD
                                + " 之间，已设为 " + clamped, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            etThreshold.setText(String.valueOf(climate.getThreshold()));
        }
    }

    private void applyConfirm() {
        if (climate == null || etConfirm == null) {
            return;
        }
        String s = etConfirm.getText().toString().trim();
        if (s.isEmpty()) {
            etConfirm.setText(String.valueOf(climate.getConfirmMs() / 1000L));
            return;
        }
        try {
            int sec = Integer.parseInt(s);
            climate.setConfirmMs(sec * 1000L);
            long clamped = climate.getConfirmMs();
            etConfirm.setText(String.valueOf(clamped / 1000L));
            if (sec * 1000L != clamped) {
                Toast.makeText(this,
                        "确认时长限定在 " + ClimateCore.MIN_CONFIRM_MS / 1000 + "~"
                                + ClimateCore.MAX_CONFIRM_MS / 1000 + " 秒之间，已设为 "
                                + clamped / 1000L + " 秒", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            etConfirm.setText(String.valueOf(climate.getConfirmMs() / 1000L));
        }
    }

    private void setDot(View v, int color) {
        ((GradientDrawable) v.getBackground()).setColor(color);
    }

    /* ---------------- 交互 ---------------- */

    private void doUnlock() {
        UnlockService.clearUnlocked(this);
        Intent it = new Intent(this, UnlockService.class);
        it.putExtra(UnlockService.EXTRA_FAST, true);   // 手动触发不等 8 秒开机延迟
        startService(it);
        Toast.makeText(this, "已发起解锁，约 1 秒后生效", Toast.LENGTH_SHORT).show();
        btnUnlock.setEnabled(false);
        ticker.postDelayed(() -> {
            btnUnlock.setEnabled(true);
            refresh();
        }, 3000);
        refresh();
    }

    private void restartBridge() {
        // 先解绑再停止，确保服务真的被销毁（START_STICKY 下 stopService 是异步的）
        if (bound) {
            try {
                unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        bridge = null;
        stopService(new Intent(this, BridgeService.class));

        btnRestartBridge.setEnabled(false);
        Toast.makeText(this, "桥接已重启", Toast.LENGTH_SHORT).show();

        // 等 stop 生效后再拉起，避免 stop/start 落在同一帧被系统合并
        ticker.postDelayed(() -> {
            startBridge();
            Intent it = new Intent(this, BridgeService.class);
            bound = bindService(it, conn, Context.BIND_AUTO_CREATE);
            if (btnRestartBridge != null) btnRestartBridge.setEnabled(true);
            ticker.postDelayed(this::refresh, 600);
        }, 500);
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

        // 卡片 3：自动内外循环
        LinearLayout card3 = card();
        root.addView(card3);
        ((LinearLayout.LayoutParams) card3.getLayoutParams()).topMargin = dp(14);

        LinearLayout row3 = row();
        card3.addView(row3);
        climateDot = dot();
        row3.addView(climateDot);
        TextView t3 = label("自动内外循环");
        row3.addView(t3);
        climateTitle = status();
        row3.addView(climateTitle);
        climateDesc = desc();
        card3.addView(climateDesc);

        // 阈值设置行
        LinearLayout thrRow = row();
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(14);
        trlp.leftMargin = dp(18);
        thrRow.setLayoutParams(trlp);
        card3.addView(thrRow);

        TextView thrLabel = new TextView(this);
        thrLabel.setText("切换阈值");
        thrLabel.setTextColor(SUB);
        thrLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        thrRow.addView(thrLabel);

        etThreshold = new EditText(this);
        etThreshold.setInputType(InputType.TYPE_CLASS_NUMBER);
        etThreshold.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etThreshold.setTextColor(TEXT);
        etThreshold.setGravity(Gravity.CENTER);
        etThreshold.setSingleLine(true);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(64), dp(38));
        elp.leftMargin = dp(10);
        elp.rightMargin = dp(6);
        etThreshold.setLayoutParams(elp);
        etThreshold.setBackground(roundRect(0xFF262B36, 8));
        etThreshold.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // 输入法回车立即保存（焦点丢失在回车时不触发，v3.8 修复）
        etThreshold.setOnEditorActionListener((v, actionId, event) -> {
            applyThreshold();
            etThreshold.clearFocus();
            return true;
        });
        etThreshold.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) applyThreshold();
        });
        thrRow.addView(etThreshold);

        TextView unit = new TextView(this);
        unit.setText("km/h");
        unit.setTextColor(SUB);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        thrRow.addView(unit);

        LinearLayout.LayoutParams togLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        togLp.leftMargin = dp(14);

        btnClimateToggle = new Button(this);
        btnClimateToggle.setAllCaps(false);
        btnClimateToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnClimateToggle.setLayoutParams(togLp);
        btnClimateToggle.setOnClickListener(v -> {
            if (climate == null) {
                startClimate();
                Toast.makeText(this, "自动循环服务启动中，请稍后", Toast.LENGTH_SHORT).show();
                return;
            }
            climate.setEnabled(!climate.isEnabled());
            refresh();
        });
        thrRow.addView(btnClimateToggle);

        // 确认时长行
        LinearLayout cfRow = row();
        LinearLayout.LayoutParams cflp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cflp.topMargin = dp(12);
        cflp.leftMargin = dp(18);
        cfRow.setLayoutParams(cflp);
        card3.addView(cfRow);

        TextView cfLabel = new TextView(this);
        cfLabel.setText("确认时长");
        cfLabel.setTextColor(SUB);
        cfLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cfRow.addView(cfLabel);

        etConfirm = new EditText(this);
        etConfirm.setInputType(InputType.TYPE_CLASS_NUMBER);
        etConfirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etConfirm.setTextColor(TEXT);
        etConfirm.setGravity(Gravity.CENTER);
        etConfirm.setSingleLine(true);
        LinearLayout.LayoutParams celp = new LinearLayout.LayoutParams(dp(64), dp(38));
        celp.leftMargin = dp(10);
        celp.rightMargin = dp(6);
        etConfirm.setLayoutParams(celp);
        etConfirm.setBackground(roundRect(0xFF262B36, 8));
        etConfirm.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // 输入法回车立即保存（焦点丢失在回车时不触发，v3.8 修复）
        etConfirm.setOnEditorActionListener((v, actionId, event) -> {
            applyConfirm();
            etConfirm.clearFocus();
            return true;
        });
        etConfirm.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) applyConfirm();
        });
        cfRow.addView(etConfirm);

        TextView cfUnit = new TextView(this);
        cfUnit.setText("秒（默认 3，限 0~10，0=关闭确认）");
        cfUnit.setTextColor(SUB);
        cfUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cfRow.addView(cfUnit);

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
        btnRestartBridge = btnRestart;
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
