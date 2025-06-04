package com.bot.aabot;

import com.bot.aabot.utils.TimeFormatUtil;
import org.junit.jupiter.api.Test;
import java.text.ParseException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间格式化工具测试类
 */
public class TimeFormatUtilTest {

    @Test
    public void testFormatTimeString() {
        // 测试基本的时间格式化
        assertEquals("2025-03-14 03:03:12", TimeFormatUtil.formatTimeString("2025-3-14 3:3:12"));
        assertEquals("2025-12-25 15:30:45", TimeFormatUtil.formatTimeString("2025-12-25 15:30:45"));
        assertEquals("2025-01-01 00:00:00", TimeFormatUtil.formatTimeString("2025-1-1 0:0:0"));
        
        // 测试部分需要补0的情况
        assertEquals("2025-03-14 15:30:45", TimeFormatUtil.formatTimeString("2025-3-14 15:30:45"));
        assertEquals("2025-12-05 03:30:45", TimeFormatUtil.formatTimeString("2025-12-5 3:30:45"));
        assertEquals("2025-12-25 15:03:05", TimeFormatUtil.formatTimeString("2025-12-25 15:3:5"));
        
        // 测试空字符串和null
        assertNull(TimeFormatUtil.formatTimeString(null));
        assertEquals("", TimeFormatUtil.formatTimeString(""));
        assertEquals("", TimeFormatUtil.formatTimeString("   "));
    }

    @Test
    public void testValidateAndFormat() throws ParseException {
        // 测试有效的时间格式化和验证
        assertEquals("2025-03-14 03:03:12", TimeFormatUtil.validateAndFormat("2025-3-14 3:3:12"));
        assertEquals("2025-12-25 15:30:45", TimeFormatUtil.validateAndFormat("2025-12-25 15:30:45"));
        
        // 测试无效的时间格式
        assertThrows(ParseException.class, () -> {
            TimeFormatUtil.validateAndFormat("2025-13-14 25:30:45"); // 无效月份和小时
        });
        
        assertThrows(ParseException.class, () -> {
            TimeFormatUtil.validateAndFormat("invalid-time"); // 完全无效的格式
        });
    }

    @Test
    public void testIsEndTimeAfterStartTime() throws ParseException {
        // 测试正常情况：结束时间晚于开始时间
        assertTrue(TimeFormatUtil.isEndTimeAfterStartTime("2025-03-14 10:00:00", "2025-03-14 11:00:00"));
        assertTrue(TimeFormatUtil.isEndTimeAfterStartTime("2025-03-14 23:59:59", "2025-03-15 00:00:00"));
        
        // 测试异常情况：结束时间等于或早于开始时间
        assertFalse(TimeFormatUtil.isEndTimeAfterStartTime("2025-03-14 10:00:00", "2025-03-14 10:00:00"));
        assertFalse(TimeFormatUtil.isEndTimeAfterStartTime("2025-03-14 11:00:00", "2025-03-14 10:00:00"));
    }
} 