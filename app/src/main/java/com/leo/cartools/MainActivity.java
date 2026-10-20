package com.leo.cartools;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 无界面入口：拉起两个服务后立刻退出，只在屏幕底部提示一次。
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent bridge = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bridge);
        } else {
            startService(bridge);
        }

        // 本次开机已解锁过就不再重跑：避免正播放时被 requestSource 打断音源
        boolean needUnlock = !UnlockService.isUnlocked(this);
        if (needUnlock) {
            startService(new Intent(this, UnlockService.class));
        }

        Toast.makeText(this, needUnlock
                ? "车机助手已启动：导航仪表桥接 + 音源解锁"
                : "导航仪表桥接已启动（音源已解锁）", Toast.LENGTH_SHORT).show();
        finish();
    }
}
