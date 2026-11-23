package com.leo.cartools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ClimateCore 状态机测试。
 *
 * 判定方式：速度越过迟滞边界即切换，不额外计时。
 */
public class ClimateCoreTest {

    private static ClimateCore enabled() {
        ClimateCore c = new ClimateCore();
        c.setEnabled(true);
        return c;
    }

    @Test
    public void stoppedBelowLowerBoundSwitchesInsideImmediately() {
        ClimateCore c = enabled();
        // 默认阈值 25，下限 20；速度 0 应立刻切内
        ClimateCore.Decision d = c.update(10_000L, 0f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, d.mode);
        assertEquals(ClimateCore.Reason.STOPPED, d.reason);
    }

    @Test
    public void creepAt10SwitchesInside() {
        ClimateCore c = enabled();
        // 蠕行 10 km/h 也属于堵车
        assertTrue(c.update(10_000L, 10f, true).shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void fastEnoughSwitchesOutsideImmediately() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        // 上限 30，速度 40 立刻切外
        ClimateCore.Decision d = c.update(10_000L, 40f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d.mode);
        assertEquals(ClimateCore.Reason.MOVING, d.reason);
    }

    @Test
    public void hysteresisBandKeepsCurrentState() {
        ClimateCore c = enabled();
        // 25 km/h 落在 20~30 中间区间，不切换
        for (int i = 0; i < 20; i++) {
            assertFalse(c.update(10_000L + i * 1000L, 25f, true).shouldSwitch());
        }
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());

        c.seedMode(ClimateCore.Mode.INSIDE);
        for (int i = 0; i < 20; i++) {
            assertFalse(c.update(40_000L + i * 1000L, 25f, true).shouldSwitch());
        }
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void minDwellBlocksImmediateReverse() {
        ClimateCore c = enabled();
        long t = 10_000L;
        assertTrue(c.update(t, 0f, true).shouldSwitch());   // 切内
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());

        // 刚切完立刻高速，30 秒内不应切回
        ClimateCore.Decision d = c.update(t + 5000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);

        // 超过驻留后可以切
        ClimateCore.Decision d2 = c.update(t + 31_000L, 60f, true);
        assertTrue(d2.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d2.mode);
    }

    @Test
    public void autoModeSuspends() {
        ClimateCore c = enabled();
        c.setAutoOn(true);
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(10_000L + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.AUTO_ON, d.reason);
        }
    }

    @Test
    public void defrostSuspends() {
        ClimateCore c = enabled();
        c.setDefrostOn(true);
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(10_000L + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DEFROST_ON, d.reason);
        }
    }

    @Test
    public void parkedGearSuspends() {
        ClimateCore c = enabled();
        int[] parked = {4, 2, 1};   // P / R / N
        for (int g : parked) {
            c.setGear(g);
            ClimateCore.Decision d = c.update(10_000L, 0f, true);
            assertFalse("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Reason.PARKED, d.reason);
        }
    }

    @Test
    public void driveAndManualGearDoNotSuspend() {
        ClimateCore c = enabled();
        int[] driving = {8, 12};    // D / M
        for (int g : driving) {
            c.setGear(g);
            ClimateCore.Decision d = c.update(10_000L, 0f, true);
            assertTrue("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Mode.INSIDE, d.mode);
        }
    }

    @Test
    public void unknownGearDoesNotSuspend() {
        ClimateCore c = enabled();
        c.setGear(-1);
        assertTrue(c.update(10_000L, 0f, true).shouldSwitch());
    }

    @Test
    public void disabledSuspends() {
        ClimateCore c = new ClimateCore();   // 默认关闭
        for (int i = 0; i < 10; i++) {
            ClimateCore.Decision d = c.update(10_000L + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DISABLED, d.reason);
        }
    }

    @Test
    public void gpsLossKeepsStateAndReportsLost() {
        ClimateCore c = enabled();
        long t = 10_000L;
        c.update(t, 25f, true);   // 建立 GPS 时间基准，中间区间不切换

        ClimateCore.Decision last = null;
        for (int i = 1; i <= 40; i++) {
            last = c.update(t + i * 1000L, 0f, false);
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
        long t = 10_000L;
        c.onManualOverride(t);
        ClimateCore.Decision d = c.update(t + 20_000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);
    }

    @Test
    public void thresholdChangeShiftsBoundaries() {
        ClimateCore c = enabled();
        c.setThreshold(35);   // 下限 30，上限 40
        // 速度 32 落在 30~40 之间，不切换
        assertFalse(c.update(10_000L, 32f, true).shouldSwitch());
        // 速度 45 超过上限，切外
        c.seedMode(ClimateCore.Mode.INSIDE);
        assertTrue(c.update(50_000L, 45f, true).shouldSwitch());
    }
}
