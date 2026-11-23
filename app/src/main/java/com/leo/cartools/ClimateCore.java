package com.leo.cartools;

/**
 * 自动内外循环的状态机。
 *
 * 纯 Java 实现，不依赖 Android，便于在 JVM 上做单元测试。
 *
 * 规则：
 *   外循环 → 内循环：车速 ≤ 阈值−迟滞（越过即切）
 *   内循环 → 外循环：车速 ≥ 阈值+迟滞（越过即切）
 *   两次切换之间至少间隔 {@link #MIN_DWELL_MS}
 *
 * 不再对速度条件额外计时：加速到 30 km/h 本身需要 2~3 秒，
 * 迟滞区间已足以过滤速度抖动，额外等待只会让切内循环变慢。
 *
 * 让行（暂停自动，不改动当前模式）：
 *   - 总开关关闭
 *   - 空调 AUTO 模式开启（控制权交给车机）
 *   - 除霜开启（安全优先，必须外循环进干燥空气）
 *   - GPS 无信号超过 {@link #GPS_LOST_MS}
 *
 * 迟滞的作用：城区跟车速度常在阈值附近波动，单阈值会导致反复切换。
 * 中间区间（阈值±迟滞）内保持当前状态不动。
 */
public final class ClimateCore {

    /** 默认基准速度（km/h） */
    public static final int DEFAULT_THRESHOLD = 25;
    /** 允许用户设置的阈值范围 */
    public static final int MIN_THRESHOLD = 15;
    public static final int MAX_THRESHOLD = 50;
    /** 迟滞带宽：切外 = 阈值+5，切内 = 阈值−5 */
    public static final int HYSTERESIS = 5;

    /** 两次切换的最小间隔（防抖） */
    public static final long MIN_DWELL_MS = 30_000L;
    /** GPS 多久没有更新视为失效 */
    public static final long GPS_LOST_MS = 30_000L;

    public enum Mode {
        /** 外循环 */
        OUTSIDE,
        /** 内循环 */
        INSIDE
    }

    public enum Action {
        /** 需要写入新状态 */
        SWITCH,
        /** 保持当前状态 */
        HOLD
    }

    public enum Reason {
        /** 停车超时，切内循环 */
        STOPPED,
        /** 行驶超时，切外循环 */
        MOVING,
        /** 总开关关闭 */
        DISABLED,
        /** 空调处于 AUTO 模式 */
        AUTO_ON,
        /** 除霜开启 */
        DEFROST_ON,
        /** 档位在 P/R/N，不需要自动切换 */
        PARKED,
        /** GPS 无信号 */
        GPS_LOST,
        /** 最短驻留时间内 */
        MIN_DWELL,
        /** 速度处于迟滞区间，保持不动 */
        IN_HYSTERESIS,
        /** 条件尚未满足足够时长 */
        PENDING,
        /** 已处于目标状态 */
        ALREADY
    }

    /** 一次判定的结果 */
    public static final class Decision {
        public final Action action;
        /** 需要切换时为目标模式，否则为当前模式 */
        public final Mode mode;
        public final Reason reason;

        Decision(Action action, Mode mode, Reason reason) {
            this.action = action;
            this.mode = mode;
            this.reason = reason;
        }

        public boolean shouldSwitch() {
            return action == Action.SWITCH;
        }
    }

    private Mode mode = Mode.OUTSIDE;
    private boolean enabled = false;
    private int threshold = DEFAULT_THRESHOLD;

    private boolean autoOn = false;
    private boolean defrostOn = false;
    /** 档位原始值：4=P 2=R 1=N 8=D 12=M；-1 表示尚未读到 */
    private int gear = -1;
    private boolean hasGps = false;

    private long lastSwitchAt = 0L;
    private long lastGpsAt = 0L;

    // ---------- 外部设置 ----------

    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setThreshold(int kmh) {
        if (kmh < MIN_THRESHOLD) kmh = MIN_THRESHOLD;
        if (kmh > MAX_THRESHOLD) kmh = MAX_THRESHOLD;
        this.threshold = kmh;
    }

    public int getThreshold() {
        return threshold;
    }

    public Mode getMode() {
        return mode;
    }

    /** 启动时用读到的车机实际状态初始化，避免首帧误判 */
    public void seedMode(Mode actual) {
        this.mode = actual;
    }

    public void setAutoOn(boolean on) {
        this.autoOn = on;
    }

    public void setDefrostOn(boolean on) {
        this.defrostOn = on;
    }

    /** 档位原始值：4=P 2=R 1=N 8=D 12=M；-1 表示未读到 */
    public void setGear(int raw) {
        this.gear = raw;
    }

    /** 用户手动操作后，占用最短驻留窗口 */
    public void onManualOverride(long now) {
        this.lastSwitchAt = now;
    }

    // ---------- 核心判定 ----------

    /**
     * @param now       当前时间（毫秒，SystemClock.elapsedRealtime 或 currentTimeMillis 均可，只要单调）
     * @param speedKmh  GPS 速度，无效时传 0 并把 hasGps 设为 false
     * @param gpsValid  GPS 是否有有效定位
     */
    public Decision update(long now, float speedKmh, boolean gpsValid) {
        // 1. 让行条件优先
        if (!enabled) {
            return hold(Reason.DISABLED);
        }
        if (autoOn) {
            return hold(Reason.AUTO_ON);
        }
        if (defrostOn) {
            return hold(Reason.DEFROST_ON);
        }
        // P/R/N 不切换。档位未知（-1）时不因档位让行，避免功能整体失效
        if (gear == 4 || gear == 2 || gear == 1) {
            return hold(Reason.PARKED);
        }

        // 2. GPS 有效性
        if (!gpsValid) {
            hasGps = false;
            boolean lost = lastGpsAt != 0L && (now - lastGpsAt) > GPS_LOST_MS;
            return hold(lost ? Reason.GPS_LOST : Reason.PENDING);
        }
        hasGps = true;
        lastGpsAt = now;

        // 3. 最短驻留
        if (lastSwitchAt != 0L && (now - lastSwitchAt) < MIN_DWELL_MS) {
            return hold(Reason.MIN_DWELL);
        }

        int hi = threshold + HYSTERESIS;
        int lo = threshold - HYSTERESIS;

        // 速度越过阈值即切换，不再额外计时：
        // 加速到 30 km/h 本身需要 2~3 秒，迟滞区间已经过滤了速度抖动。
        if (mode == Mode.OUTSIDE) {
            if (speedKmh <= lo) {
                return doSwitch(Mode.INSIDE, Reason.STOPPED, now);
            }
            return hold(speedKmh < hi ? Reason.IN_HYSTERESIS : Reason.ALREADY);
        } else {
            if (speedKmh >= hi) {
                return doSwitch(Mode.OUTSIDE, Reason.MOVING, now);
            }
            return hold(speedKmh > lo ? Reason.IN_HYSTERESIS : Reason.ALREADY);
        }
    }

    private Decision doSwitch(Mode target, Reason reason, long now) {
        mode = target;
        lastSwitchAt = now;
        return new Decision(Action.SWITCH, target, reason);
    }

    private Decision hold(Reason reason) {
        return new Decision(Action.HOLD, mode, reason);
    }

    /** 是否拿到过 GPS（供界面展示） */
    public boolean hasGps() {
        return hasGps;
    }
}
