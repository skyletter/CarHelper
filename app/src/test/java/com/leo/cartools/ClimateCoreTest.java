package com.leo.cartools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ClimateCore 状态机测试。时间用毫秒整数模拟，不依赖真实时钟。
 */
public class ClimateCoreTest {

    private static ClimateCore enabled() {
        ClimateCore c = new ClimateCore();
        c.setEnabled(true);
        return c;
    }

    @Test
    public void stoppedSwitchesToInsideAfter5s() {
        ClimateCore c = enabled();
        long t = 10_000L;
        // 前 5 秒内不切换
        for (int i = 0; i < 5; i++) {
            assertFalse("i=" + i, c.update(t + i * 1000L, 0f, true).shouldSwitch());
        }
        ClimateCore.Decision d = c.update(t + 5000L, 0f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.INSIDE, d.mode);
        assertEquals(ClimateCore.Reason.STOPPED, d.reason);
    }

    @Test
    public void movingSwitchesToOutsideAfter8s() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        long t = 10_000L;
        for (int i = 0; i < 8; i++) {
            assertFalse("i=" + i, c.update(t + i * 1000L, 40f, true).shouldSwitch());
        }
        ClimateCore.Decision d = c.update(t + 8000L, 40f, true);
        assertTrue(d.shouldSwitch());
        assertEquals(ClimateCore.Mode.OUTSIDE, d.mode);
        assertEquals(ClimateCore.Reason.MOVING, d.reason);
    }

    @Test
    public void hysteresisBandKeepsCurrentState() {
        ClimateCore c = enabled();
        long t = 10_000L;
        // 25 km/h 落在阈值 25±5 的中间区间，永远不切换
        for (int i = 0; i < 30; i++) {
            assertFalse(c.update(t + i * 1000L, 25f, true).shouldSwitch());
        }
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());
    }

    @Test
    public void slowCreepBelowLowerBoundSwitchesInside() {
        ClimateCore c = enabled();
        long t = 10_000L;
        // 蠕行 10 km/h（≤ 20）应判为堵车
        for (int i = 0; i <= 5; i++) {
            c.update(t + i * 1000L, 10f, true);
        }
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
    }

    @Test
    public void minDwellBlocksImmediateReverse() {
        ClimateCore c = enabled();
        long t = 10_000L;
        // 停车切内
        for (int i = 0; i <= 5; i++) {
            c.update(t + i * 1000L, 0f, true);
        }
        assertEquals(ClimateCore.Mode.INSIDE, c.getMode());
        // 刚切完立刻高速，30 秒内不应切回
        ClimateCore.Decision d = c.update(t + 6000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);
    }

    @Test
    public void autoModeSuspends() {
        ClimateCore c = enabled();
        c.setAutoOn(true);
        long t = 10_000L;
        for (int i = 0; i < 20; i++) {
            ClimateCore.Decision d = c.update(t + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.AUTO_ON, d.reason);
        }
    }

    @Test
    public void defrostSuspends() {
        ClimateCore c = enabled();
        c.setDefrostOn(true);
        long t = 10_000L;
        for (int i = 0; i < 20; i++) {
            ClimateCore.Decision d = c.update(t + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DEFROST_ON, d.reason);
        }
    }

    @Test
    public void parkedGearSuspends() {
        ClimateCore c = enabled();
        long t = 10_000L;
        int[] parked = {4, 2, 1};   // P / R / N
        for (int g : parked) {
            c.setGear(g);
            ClimateCore.Decision d = c.update(t, 0f, true);
            assertFalse("gear=" + g, d.shouldSwitch());
            assertEquals("gear=" + g, ClimateCore.Reason.PARKED, d.reason);
        }
    }

    @Test
    public void driveAndManualGearDoNotSuspend() {
        ClimateCore c = enabled();
        long t = 10_000L;
        int[] driving = {8, 12};    // D / M
        for (int g : driving) {
            c.setGear(g);
            ClimateCore.Decision d = c.update(t, 0f, true);
            assertEquals("gear=" + g, ClimateCore.Reason.PENDING, d.reason);
        }
    }

    @Test
    public void unknownGearDoesNotSuspend() {
        ClimateCore c = enabled();
        c.setGear(-1);
        ClimateCore.Decision d = c.update(10_000L, 0f, true);
        assertEquals(ClimateCore.Reason.PENDING, d.reason);
    }

    @Test
    public void disabledSuspends() {
        ClimateCore c = new ClimateCore();   // 默认关闭
        long t = 10_000L;
        for (int i = 0; i < 20; i++) {
            ClimateCore.Decision d = c.update(t + i * 1000L, 0f, true);
            assertFalse(d.shouldSwitch());
            assertEquals(ClimateCore.Reason.DISABLED, d.reason);
        }
    }

    @Test
    public void gpsLossKeepsStateAndReportsLost() {
        ClimateCore c = enabled();
        long t = 10_000L;
        c.update(t, 0f, true);   // 建立 GPS 时间基准
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
    public void manualOverrideResetsDwell() {
        ClimateCore c = enabled();
        c.seedMode(ClimateCore.Mode.INSIDE);
        long t = 10_000L;
        c.onManualOverride(t);
        // 手动操作后 30 秒内不自动切
        ClimateCore.Decision d = c.update(t + 20_000L, 60f, true);
        assertFalse(d.shouldSwitch());
        assertEquals(ClimateCore.Reason.MIN_DWELL, d.reason);
    }

    @Test
    public void conditionTimerResetsWhenSpeedConditionFlips() {
        ClimateCore c = enabled();
        long t = 10_000L;
        // 停 3 秒
        for (int i = 0; i < 3; i++) {
            c.update(t + i * 1000L, 0f, true);
        }
        // 动 1 秒（打断计时）
        c.update(t + 3000L, 40f, true);
        // 再停 3 秒，累计不足 5 秒，不应切换
        for (int i = 1; i <= 3; i++) {
            assertFalse(c.update(t + 3000L + i * 1000L, 0f, true).shouldSwitch());
        }
        assertEquals(ClimateCore.Mode.OUTSIDE, c.getMode());
    }
}
