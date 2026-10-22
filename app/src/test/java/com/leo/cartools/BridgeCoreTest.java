package com.leo.cartools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 离线模拟高德广播，覆盖本次高架“靠左行驶”问题。
 */
public class BridgeCoreTest {
    private static final long T0 = 1_000_000L;

    /** 复现用户场景：左转上高架后，NEW_ICON=65 的靠左指令被丢弃，仪表停在左转 0m */
    @Test
    public void highwayKeepLeftUpdatesCluster() {
        BridgeCore core = new BridgeCore();

        // 上匝道前：左转，10 米
        BridgeCore.Update approach = core.onGuidance(T0, 2, -1, 10, "10米", -1, null);
        assertTrue(approach.send);
        assertEquals(27, approach.icon);
        assertEquals("10,m", approach.dis);

        // 到达路口：距离 0，不应覆盖成 0m
        BridgeCore.Update atTurn = core.onGuidance(T0 + 900, 2, -1, 0, "0米", -1, null);
        assertFalse(atTurn.send);
        assertEquals("10,m", core.getLastDisStr());

        // 已上高架：ICON=0，靠左动作在 NEW_ICON=65，1.7 公里在 NEXT 字段
        BridgeCore.Update onRamp = core.onGuidance(T0 + 1800, 0, 65, 0, null, 1700, "1.7公里");
        assertTrue(onRamp.send);
        assertEquals(28, onRamp.icon);
        assertEquals("1.7,km", onRamp.dis);
    }

    /** NEW_ICON 优先于可能残留的 ICON */
    @Test
    public void newIconWinsOverOldIcon() {
        BridgeCore core = new BridgeCore();
        BridgeCore.Update u = core.onGuidance(T0, 2, 65, 1700, "1.7公里", -1, null);
        assertTrue(u.send);
        assertEquals(28, u.icon);
    }

    /** NEW_ICON 不认识时回退到 ICON，不要整帧丢弃 */
    @Test
    public void fallsBackToOldIcon() {
        BridgeCore core = new BridgeCore();
        BridgeCore.Update u = core.onGuidance(T0, 3, 999, 500, "500米", -1, null);
        assertTrue(u.send);
        assertEquals(22, u.icon);
    }

    /** 未知图标不会立刻清屏，但长时间没有有效数据仍会超时清空 */
    @Test
    public void unmappedIconTimesOutInsteadOfFreezing() {
        BridgeCore core = new BridgeCore();
        core.onGuidance(T0, 2, -1, 10, "10米", -1, null);
        assertTrue(core.isActive());

        BridgeCore.Update skip = core.onGuidance(T0 + 1000, 0, 999, 1700, "1.7公里", -1, null);
        assertFalse(skip.send);
        assertEquals(BridgeCore.Reason.UNMAPPED_ICON, skip.reason);
        assertTrue(core.isActive());

        BridgeCore.Update timeout = core.tick(T0 + BridgeCore.RX_TIMEOUT_MS + 1);
        assertTrue(timeout.send);
        assertEquals(0, timeout.icon);
        assertEquals("0,m", timeout.dis);
        assertFalse(core.isActive());
    }

    /** 期间只要来一帧有效数据，超时计时就重新开始 */
    @Test
    public void validFrameRefreshesTimeout() {
        BridgeCore core = new BridgeCore();
        core.onGuidance(T0, 2, -1, 10, "10米", -1, null);
        core.onGuidance(T0 + 9000, 0, 66, 800, "800米", -1, null);

        BridgeCore.Update notYet = core.tick(T0 + 9000 + BridgeCore.RX_TIMEOUT_MS);
        assertFalse(notYet.send);

        BridgeCore.Update timeout = core.tick(T0 + 9000 + BridgeCore.RX_TIMEOUT_MS + 1);
        assertTrue(timeout.send);
    }

    /** 导航结束/进入巡航立即清屏 */
    @Test
    public void navStateClearsCluster() {
        BridgeCore exitCore = new BridgeCore();
        exitCore.onGuidance(T0, 2, -1, 10, "10米", -1, null);
        BridgeCore.Update exit = exitCore.onNavState(T0 + 1000, BridgeCore.NAV_STATE_EXIT);
        assertTrue(exit.send);
        assertEquals(0, exit.icon);
        assertFalse(exitCore.isActive());

        BridgeCore cruiseCore = new BridgeCore();
        cruiseCore.onGuidance(T0, 2, -1, 10, "10米", -1, null);
        BridgeCore.Update cruise = cruiseCore.onNavState(T0 + 1000, BridgeCore.NAV_STATE_CRUISE);
        assertTrue(cruise.send);
        assertFalse(cruiseCore.isActive());
    }

    /** 距离文本优先级：当前段 -> 下一段 -> 米数；0 不显示 */
    @Test
    public void distanceFallback() {
        assertEquals("500,m", BridgeCore.formatDis(500, "500米", 1700, "1.7公里"));
        assertEquals("1.7,km", BridgeCore.formatDis(0, null, 1700, "1.7公里"));
        assertEquals("1.7,km", BridgeCore.formatDis(1790, "", 1700, "1.7公里"));
        assertEquals("10,m", BridgeCore.formatDis(10, null, -1, null));
        assertEquals("1700,m", BridgeCore.formatDis(0, "0米", 1700, null));
        assertNull(BridgeCore.formatDis(0, "0米", 0, null));
        assertNull(BridgeCore.formatDis(-1, null, -1, null));
    }

    /** 距离刷新节流：800ms 内只更新图标变化 */
    @Test
    public void distanceThrottle() {
        BridgeCore core = new BridgeCore();
        core.onGuidance(T0, 2, -1, 100, "100米", -1, null);

        BridgeCore.Update throttled = core.onGuidance(T0 + 300, 2, -1, 90, "90米", -1, null);
        assertFalse(throttled.send);
        assertEquals(BridgeCore.Reason.THROTTLED, throttled.reason);

        BridgeCore.Update sent = core.onGuidance(T0 + 900, 2, -1, 80, "80米", -1, null);
        assertTrue(sent.send);
        assertEquals("80,m", sent.dis);
    }

    /** 扩展动作码映射 */
    @Test
    public void iconMapping() {
        assertEquals(28, IconMap.toClusterPreferred(0, 65));
        assertEquals(21, IconMap.toClusterPreferred(0, 66));
        assertEquals(28, IconMap.toClusterPreferred(4, -1));
        assertEquals(28, IconMap.toClusterPreferred(2, 65));
        assertEquals(22, IconMap.toClusterPreferred(3, 999));
        assertEquals(-1, IconMap.toClusterPreferred(0, 999));
        // NEW_ICON=1 是自车图标，应回退到 ICON
        assertEquals(27, IconMap.toClusterPreferred(2, 1));
    }
}
