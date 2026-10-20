# 车机助手（CarHelper）

**长安 C211 平台车机（全志 T7 / Android 9）二合一工具**

一个 APK 解决两个原厂遗留问题：

| # | 问题 | 效果 |
|---|---|---|
| 1 | 升级高德后，仪表盘「导航信息」页变灰进不去 | 导航时自动恢复，显示转向箭头 + 到路口距离 |
| 2 | 第三方播放器（AntennaPod / 喜马拉雅 / QQ 音乐）显示播放中但没声音 | 开机自动解锁音源，不用再先开一次酷我 |

> 本仓库由原 **CarUnlock** 升级而来，已合并导航仪表桥接功能，应用名改为「车机助手」。

---

## 适用车型

- 长安 inCall / Coagent **C211** 平台车机（全志 T7，Android 9，1920×720）
- 车机版本号以 `OS_C211-` 或 `OS_CA` 开头（设置 → 系统升级 里查看）
- 常见车型：二代逸动、逸动 PLUS（早期）、CS75 / CS55 / CS95、锐程系列、UNI 系列（早期）
- 仅测试于 `OS_CA6884-V9_V00.00.47-20210517`

---

## 使用方法

1. 安装 `CarHelper-v2.0.apk`
2. 把「车机助手」加入车机的**自启动白名单**（不同版本位置不同，一般在车辆设置里）
3. 重启车机

之后无需任何操作：

- 打开高德开始导航 → 方向盘切换到「导航信息」页即可看到箭头和距离
- 打开任意第三方播放器 → 直接有声音

应用**没有界面**，点图标只会弹一条提示；常驻通知只显示「导航仪表桥接运行中」。

---

## 功能一：仪表盘导航

### 背景

出厂高德是长安定制版 4.1.7，升级到公众版 8.x 后，仪表「导航信息」页会变灰不可用。

原因不是播放器/导航软件的问题，而是**协议层断了一环**：

```
高德 8.x   → AUTONAVI_STANDARD_BROADCAST_SEND (KEY_TYPE=10001) → 仅标准数据
原厂 4.1.7 → com.autonavi.navigation.NAVIGATION_UPDATES        → com.fce.navi.service → CAN → 仪表
```

新版只发标准广播，不发长安 OEM 那条，`com.fce.navi.service` 收不到图标和距离，仪表就把该页置灰。

### 做法

`BridgeService` 常驻监听高德的 `KEY_TYPE=10001` 广播，取出转向图标和到路口距离，按原厂映射表转换后补发 OEM 广播：

```
ICON (AMap 转向 ID)  →  TBT_Navi_id_mapping  →  navigation_id
SEG_REMAIN_DIS_AUTO  →  直接透传显示文本    →  navigation_dis
```

原厂映射表（节选）：

| AMap 转向 | 仪表图标 ID | 含义 |
|---|---|---|
| 2 | 27 | 左转 |
| 3 | 22 | 右转 |
| 9 | 17 | 直行 |
| 8 | 25 | 左转掉头 |
| 15 | 6 | 到达目的地 |
| 11121~111210 | 235~271 | 环岛左转第 1~10 出口 |
| 11171~111710 | 334~343 | 环岛右转第 1~10 出口 |

### 几个踩过的坑

- 图标 ID 是 **16 位**，`navigation_id` 低字节是图标、高字节是高位，传 0 会让仪表页变灰
- 距离不能直接发米：1790 米会被仪表四舍五入成 1.8km，而高德显示 1.7km。改用高德自己的显示文本（`1.7公里` → `"1.7,km"`）后完全一致
- 超过 9 秒没有导航数据自动清空，仪表回到未导航状态（与原厂一致）

### 仪表能显示什么

只有**箭头图标 + 距离**，没有路名、ETA、红绿灯。

这不是 App 的限制：`com.fce.navi.service` 向 CAN 只写 3 个字段（图标、距离、状态），页面布局由仪表 MCU 固件固定。原厂高德能显示的也只有这些。

---

## 功能二：第三方播放器无声

### 症状

- 第三方播放器显示在播放，但**扬声器没声音**
- 车机自带的酷我、网易云**有声音**，高德导航也有声音
- **先打开一次酷我，再开第三方播放器就有声**（经典土办法）
- 暂停再播放没用，**每次开机复现**

### 原因

C211 采用音源仲裁 + 应用白名单：媒体音频走 `bus0_media_out` → SPDIF，功放开关由 `AudioControlDsp` 按 `Snd1Mute` 控制。第三方 App 不注册音源，功放保持静音（音频数据其实已经正常送到声卡）。

### 做法

开机后调用车机自带的音源仲裁接口：

```
requestSource("app")
  → switchChannel From = USB to = APP
  → select device(out): OUT_SPDIF
  → notify dsp amp 16 unmute
  → Snd1Mute 0 → amp unmute
```

不修改系统分区、不刷机、不需要 root。

---

## 技术细节

| 项 | 值 |
|---|---|
| 包名 | `com.leo.cartools` |
| minSdk / targetSdk | 26 / 28 |
| 音源服务 | `fce_misc_service_source_ctrl`，AIDL `com.fce.misc.proxy.source.ISourceCtrl`，事务码 1 |
| 导航服务 | `com.fce.navi.service`（NaviHostService，未导出） |
| 导航广播 | `com.autonavi.navigation.NAVIGATION_UPDATES`，extra：`navigation_id`(int) / `navigation_dis`("1.7,km") |
| 仪表属性 | `AP_NAVI_STATE_INFO_ICON_NUMBER` / `AP_NAVI_NEXT_ROADCROSS_DISTANCE` |

---

## 构建

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（用 debug 签名，方便车机直接安装）。

CI（GitHub Actions）会在推送到 `main` 时自动构建并发布 Release。

---

## 已知限制

- 只在 `OS_CA6884-V9_V00.00.47-20210517` 上验证过，其他固件可能字段不同
- 仪表显示内容受固件限制，无法扩展
- 部分车机重启后 USB ADB 授权失效，与 App 无关

---

## License

MIT
