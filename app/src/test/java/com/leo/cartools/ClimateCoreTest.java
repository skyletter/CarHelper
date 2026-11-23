package com.leo.cartools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ClimateCore 状态机测试。
 *
 * 判定方式：速度越过迟滞边界后，需持续 CONFIRM_MS（3 秒）才切换。
 */
public class ClimateCoreTest {

    private static final long T0 = 10_000L;
    private static final long CONFIRM = ClimateCore.CONFIRM_MS;

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
    public void stoppedSwitchesInsideAfterConfirmWindow() {
        ClimateCore c = enabled();
        // 前 2 秒不切换
        for (int i = 0; i < 3; i++) {
            assertFalse("i=" + i, c.update(T0 + i * 1000L, 0f, true).shouldSwitch());
        }
        // 满 3 秒切换
        ClimateCore.Decision d = c.update(T0 + CONFIRM, 0f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, d.mode);
        assertEquals(ClimateCore.Reason.STOPPED, d.reason);
    }

    @Test
    public void creepAt10SwitchesInside() {
        ClimateCore c = enabled();
        ClimateCore.Decision d = feed(c, T0, 3, 10f);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void fastEnoughSwitchesOutside() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        ClimateCore.Decision d = feed(c, T0, 3, 40f);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d.mode);
        assertEquals(ClimateCore.Reason.MOVING, d.reason);
    }

    @Test
    public void shortStopDoesNotSwitch() {
        ClimateCore c = enabled();
        // 停 1 秒后恢复 40 km/h，不应切内
        assertFalse(c.update(T0, 0f, true).shouldSwitch());
        ClimateCore.Decision d = c.update(T0 + 1000L, 40f, true);
        assertFalse(d.shouldSwitch());
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
        // 停 2 秒
        c.update(T0, 0f, true);
        c.update(T0 + 1000L, 0f, true);
        // 动 1 秒，计时清零
        c.update(T0 + 2000L, 40f, true);
        // 再停 2 秒，累计不足 3 秒，不切换
        assertFalse(c.update(T0 + 3000L, 0f, true).shouldSwitch());
        assertFalse(c.update(T0 + 4000L, 0f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());
    }

    @Test
    public void minDwellBlocksImmediateReverse() {
        ClimateCore c = enabled();
        assertTrue(feed(c, T0, 3, 0f).shouldSwitch());   // 切内
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());

        // 刚切完立刻高速，30 秒内不应切回
        ClimateCore.Decision d = c.update(T0 + 5000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);

        // 超过驻留（距上次切换 31 秒）且条件满足后可切
        ClimateCore.Decision d2 = feed(c, T0 + 34_000L, 3, 60f);
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
        int[] parked = {4, 2, 1};   // P / R / N
        for (int g : parked) {
            ClimateCore c = enabled();
            c.setGear(g);
            ClimateCore.Decision d = feed(c, T0, 5, 0f);
            assertFalse("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Reason.PARKED, d.reason);
        }
    }

    @Test
    public void driveAndManualGearDoNotSuspend() {
        int[] driving = {8, 12};    // D / M
        for (int g : driving) {
            ClimateCore c = enabled();
            c.setGear(g);
            ClimateCore.Decision d = feed(c, T0, 3, 0f);
            assertTrue("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Mode.INSIDE, d.mode);
        }
    }

    @Test
    public void unknownGearDoesNotSuspend() {
        ClimateCore c = enabled();
        c.setGear(-1);
        assertTrue(feed(c, T0, 3, 0f).shouldSwitch());
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
        c.setThreshold(35);   // 下限 30，上限 40
        // 速度 32 落在 30~40 之间，持续也不切换
        assertFalse(feed(c, T0, 10, 32f).shouldSwitch());
        // 速度 45 超过上限，持续 3 秒切外
        c.seedMode(ClimateCore.Mode.INSIDE);
        assertTrue(feed(c, 60_000L, 3, 45f).shouldSwitch());
    }
}
