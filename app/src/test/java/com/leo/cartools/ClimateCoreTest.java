package com.leo.cartools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ClimateCore 状态机测试。
 *
 * 判定方式（渐进式）：
 *   速度越过迟滞边界 ±5 的裕度线（±10）→ 立即切换
 *   速度落在边缘区（越界但未达裕度线）→ 需持续 CONFIRM_MS（0 = 关闭确认，越过即切）
 */
public class ClimateCoreTest {

    private static final long T0 = 10_000L;

    private static ClimateCore enabled() {
        ClimateCore c = new ClimateCore();
        c.setEnabled(true);
        return c;
    }

    /** 从 start 起每秒喂一次同一速度，共 seconds+1 次，返回最后一次判定 */
    private static ClimateCore.Decision feed(ClimateCore c, long start, int seconds, float speed) {
        ClimateCore.Decision last = null;
        for (int i = 0; i <= seconds; i++) {
            last = c.update(start + i * 1000L, speed, true);
        }
        return last;
    }

    @Test
    public void hardStopImmediateInside() {
        ClimateCore c = enabled();
        // 急停：速度掉到 15 以下（裕度线），立即切内
        ClimateCore.Decision d = c.update(T0, 5f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, d.mode);
        assertEquals(ClimateCore.Reason.STOPPED, d.reason);
    }

    @Test
    public void creepAt10ImmediateInside() {
        ClimateCore c = enabled();
        assertTrue(c.update(T0, 10f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void hardAccelerateImmediateOutside() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        // 急加速：速度冲到 35 以上（裕度线），立即切外
        ClimateCore.Decision d = c.update(T0, 40f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d.mode);
        assertEquals(ClimateCore.Reason.MOVING, d.reason);
    }

    @Test
    public void edgeZoneNeedsConfirmWindow() {
        ClimateCore c = enabled();
        // 速度 18：低于下限 20 但高于裕度线 15，需持续确认（默认 3 秒）
        for (int i = 0; i < 3; i++) {
            assertFalse("i=" + i, c.update(T0 + i * 1000L, 18f, true).shouldSwitch());
        }
        ClimateCore.Decision d = c.update(T0 + ClimateCore.DEFAULT_CONFIRM_MS, 18f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, d.mode);
    }

    @Test
    public void shortStopDoesNotSwitch() {
        ClimateCore c = enabled();
        // 边缘区停 1 秒（不足确认窗），随后起步，均不应切内
        assertFalse(c.update(T0, 18f, true).shouldSwitch());
        ClimateCore.Decision d = c.update(T0 + 1000L, 18f, true);
        assertFalse(d.shouldSwitch());
        // 起步回 40：外循环模式下本就是外，维持不变
        assertFalse(c.update(T0 + 2000L, 40f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());
    }

    @Test
    public void hysteresisBandKeepsCurrentState() {
        ClimateCore c = enabled();
        assertFalse(feed(c, T0, 20, 25f).shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());

        c.seedMode(ClimateCore.Mode.INSIDE);
        assertFalse(feed(c, 60_000L, 20, 25f).shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void conditionTimerResetsWhenSpeedFlips() {
        ClimateCore c = enabled();
        // 边缘区停 2 秒
        c.update(T0, 18f, true);
        c.update(T0 + 1000L, 18f, true);
        // 动 1 秒（回外循环速度），计时清零
        c.update(T0 + 2000L, 40f, true);
        // 再停 2 秒，累计不足 3 秒，不切换
        assertFalse(c.update(T0 + 3000L, 18f, true).shouldSwitch());
        assertFalse(c.update(T0 + 4000L, 18f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());
    }

    @Test
    public void minDwellBlocksImmediateReverse() {
        ClimateCore c = enabled();
        ClimateCore.Decision d0 = c.update(T0, 0f, true);   // 急停立即切内
        assertTrue(d0.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());

        // 刚切完立刻高速，30 秒内不应切回
        ClimateCore.Decision d = c.update(T0 + 5000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);

        // 超过驻留（距上次切换 34 秒）后急加速立即切外
        ClimateCore.Decision d2 = c.update(T0 + 34_000L, 60f, true);
        assertTrue(d2.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d2.mode);
    }

    @Test
    public void autoModeSuspends() {
        ClimateCore c = enabled();
        c.setAutoOn(true);
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(T0 + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.AUTO_ON, d.reason);
        }
    }

    @Test
    public void defrostSuspends() {
        ClimateCore c = enabled();
        c.setDefrostOn(true);
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(T0 + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DEFROST_ON, d.reason);
        }
    }

    @Test
    public void parkedGearSuspends() {
        int[] parked = {4, 2};   // P / R（N 允许切换：用户有 N 档滑行习惯）
        for (int g : parked) {
            ClimateCore c = enabled();
            c.setGear(g);
            ClimateCore.Decision d = feed(c, T0, 5, 0f);
            assertFalse("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Reason.PARKED, d.reason);
        }
    }

    @Test
    public void neutralGearDoesNotSuspend() {
        // N 档滑行时允许自动切换
        ClimateCore c = enabled();
        c.setGear(1);
        assertTrue(c.update(T0, 0f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void driveAndManualGearDoNotSuspend() {
        int[] driving = {8, 12};    // D / M
        for (int g : driving) {
            ClimateCore c = enabled();
            c.setGear(g);
            ClimateCore.Decision d = c.update(T0, 0f, true);
            assertTrue("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Mode.INSIDE, d.mode);
        }
    }

    @Test
    public void unknownGearDoesNotSuspend() {
        ClimateCore c = enabled();
        c.setGear(-1);
        assertTrue(c.update(T0, 0f, true).shouldSwitch());
    }

    @Test
    public void disabledSuspends() {
        ClimateCore c = new ClimateCore();   // 默认关闭
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(T0 + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DISABLED, d.reason);
        }
    }

    @Test
    public void gpsLossKeepsStateAndReportsLost() {
        ClimateCore c = enabled();
        c.update(T0, 25f, true);   // 建立 GPS 时间基准

        ClimateCore.Decision last = null;
        for (int i = 1; i <= 40; i++) {
            last = c.update(T0 + i * 1000L, 0f, false);
            assertFalse(last.shouldSwitch());
        }
        assertEquals(ClimateCore.Reason.GPS_LOST, last.reason);
    }

    @Test
    public void thresholdClampedToRange() {
        ClimateCore c = enabled();
        c.setThreshold(5);
        assertEquals(ClimateCore.MIN_THRESHOLD, c.getThreshold());
        c.setThreshold(100);
        assertEquals(ClimateCore.MAX_THRESHOLD, c.getThreshold());
    }

    @Test
    public void confirmMsClampedToRange() {
        ClimateCore c = enabled();
        c.setConfirmMs(-1L);
        assertEquals(ClimateCore.MIN_CONFIRM_MS, c.getConfirmMs());   // 0 = 关闭确认
        c.setConfirmMs(60_000L);
        assertEquals(ClimateCore.MAX_CONFIRM_MS, c.getConfirmMs());
    }

    @Test
    public void zeroConfirmDisablesWindow() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        c.setConfirmMs(0L);
        // 确认关闭：速度越过下限 20（32 < 40 上限）立即切外，不等确认
        ClimateCore.Decision d = c.update(T0, 32f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d.mode);
    }

    @Test
    public void manualOverrideOccupiesDwell() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        c.onManualOverride(T0);
        ClimateCore.Decision d = c.update(T0 + 20_000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);
    }

    @Test
    public void thresholdChangeShiftsBoundaries() {
        ClimateCore c = enabled();
        c.setThreshold(35);   // 边缘下限 30，裕度线 25；边缘上限 40，裕度线 45
        // 速度 32 落在 30~40 之间，持续也不切换
        assertFalse(feed(c, T0, 10, 32f).shouldSwitch());
        // 速度 45 达到裕度线，INSIDE 模式立即切外
        c.seedMode(ClimateCore.Mode.INSIDE);
        assertTrue(c.update(60_000L, 45f, true).shouldSwitch());
    }
}
