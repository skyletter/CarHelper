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
        startService(new Intent(this, UnlockService.class));

        Toast.makeText(this, "车机助手已启动：导航仪表桥接 + 音源解锁", Toast.LENGTH_SHORT).show();
        finish();
    }
}
