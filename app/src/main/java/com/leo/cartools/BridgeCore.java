package com.leo.cartools;

/**
 * 导航帧处理状态机。
 *
 * 纯 Java 实现，不依赖 Android，便于在 JVM 上离线模拟各种高德广播。
 */
final class BridgeCore {
    /** 超过 9 秒没有“可显示”的数据，视为导航结束 */
    static final long RX_TIMEOUT_MS = 9000;
    /** 同一图标下距离刷新的最小间隔 */
    static final long MIN_SEND_INTERVAL = 800;

    /** KEY_TYPE=10019 的 EXTRA_STATE */
    static final int NAV_STATE_EXIT = 9;
    static final int NAV_STATE_CRUISE = 24;
    static final int NAV_STATE_CRUISE_EXIT = 25;

    enum Reason {
        SEND,
        IGNORED,
        /** 转向图标不在映射表内 */
        UNMAPPED_ICON,
        /** 没有可显示的距离 */
        NO_DISTANCE,
        /** 距离变化但还没到刷新间隔 */
        THROTTLED,
        /** 收到导航结束/巡航状态 */
        CLEAR,
        /** 长时间没有可显示数据 */
        TIMEOUT
    }

    static final class Update {
        static final Update IGNORED = new Update(false, 0, null, Reason.IGNORED);

        final boolean send;
        final int icon;
        final String dis;
        final Reason reason;

        private Update(boolean send, int icon, String dis, Reason reason) {
            this.send = send;
            this.icon = icon;
            this.dis = dis;
            this.reason = reason;
        }

        static Update send(int icon, String dis) {
            return new Update(true, icon, dis, Reason.SEND);
        }

        static Update ignore(Reason reason) {
            return new Update(false, 0, null, reason);
        }
    }

    private int lastIcon = -1;
    private String lastDisStr = "";
    private long lastSendAt = 0;
    private long lastUsableAt = 0;
    private boolean active = false;

    /**
     * 处理一帧 KEY_TYPE=10001。
     *
     * @param oldIcon     旧字段 ICON
     * @param newIcon     扩展字段 NEW_ICON（优先）
     * @param dis         SEG_REMAIN_DIS
     * @param disAuto     SEG_REMAIN_DIS_AUTO
     * @param nextDis     NEXT_SEG_REMAIN_DIS
     * @param nextDisAuto NEXT_SEG_REMAIN_DIS_AUTO
     */
    Update onGuidance(long now, int oldIcon, int newIcon,
                      int dis, String disAuto, int nextDis, String nextDisAuto) {
        int clusterIcon = IconMap.toClusterPreferred(oldIcon, newIcon);
        if (clusterIcon <= 0) {
            // 映射表外的动作码：保留上一次画面，但不算“可显示数据”，
            // 这样 watchdog 仍能在长时间没有有效更新时清屏。
            return Update.ignore(Reason.UNMAPPED_ICON);
        }

        String disStr = formatDis(dis, disAuto, nextDis, nextDisAuto);
        if (disStr == null) {
            return Update.ignore(Reason.NO_DISTANCE);
        }

        active = true;
        lastUsableAt = now;

        boolean iconChanged = clusterIcon != lastIcon;
        boolean disChanged = !disStr.equals(lastDisStr);
        boolean timeUp = now - lastSendAt >= MIN_SEND_INTERVAL;
        if (!iconChanged && !(disChanged && (timeUp || lastSendAt == 0))) {
            return Update.ignore(Reason.THROTTLED);
        }

        lastIcon = clusterIcon;
        lastDisStr = disStr;
        lastSendAt = now;
        return Update.send(clusterIcon, disStr);
    }

    /** 处理 KEY_TYPE=10019 导航状态 */
    Update onNavState(long now, int state) {
        if (state == NAV_STATE_EXIT || state == NAV_STATE_CRUISE || state == NAV_STATE_CRUISE_EXIT) {
            return clear(Reason.CLEAR);
        }
        return Update.IGNORED;
    }

    /** 由服务定时调用；长时间没有可显示数据时清空仪表 */
    Update tick(long now) {
        if (active && now - lastUsableAt > RX_TIMEOUT_MS) {
            return clear(Reason.TIMEOUT);
        }
        return Update.IGNORED;
    }

    private Update clear(Reason reason) {
        if (!active) {
            return Update.IGNORED;
        }
        active = false;
        lastIcon = -1;
        lastDisStr = "";
        lastSendAt = 0;
        lastUsableAt = 0;
        return new Update(true, 0, "0,m", reason);
    }

    boolean isActive() {
        return active;
    }

    String getLastDisStr() {
        return lastDisStr;
    }

    int getLastIcon() {
        return lastIcon;
    }

    /**
     * 优先用高德自己的显示文本（"1.7公里" / "500米"），避免米数被仪表四舍五入。
     * 当前段距离为空或 0 时，回退到下一段距离（高架长路段常见）。
     */
    static String formatDis(int dis, String disAuto, int nextDis, String nextDisAuto) {
        String current = parseAuto(disAuto);
        if (current != null) return current;

        String next = parseAuto(nextDisAuto);
        if (next != null) return next;

        if (dis > 0) return dis + ",m";
        if (nextDis > 0) return nextDis + ",m";

        // 0 或未知距离不覆盖上一次，避免仪表长期停在 0m
        return null;
    }

    private static String parseAuto(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.endsWith("公里")) {
                String num = s.substring(0, s.length() - 2).trim();
                if (!num.isEmpty() && Double.parseDouble(num) > 0) {
                    return num + ",km";
                }
            } else if (s.endsWith("米")) {
                String num = s.substring(0, s.length() - 1).trim();
                if (!num.isEmpty() && Integer.parseInt(num) > 0) {
                    return num + ",m";
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
