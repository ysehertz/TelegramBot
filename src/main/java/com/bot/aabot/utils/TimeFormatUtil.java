package com.bot.aabot.utils;

import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ClassName: TimeFormatUtil
 * Package: com.bot.aabot.utils
 * Description: 时间格式化工具类
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/19
 */
public class TimeFormatUtil {
    
    /**
     * 标准时间格式
     */
    public static final String STANDARD_FORMAT = "yyyy-MM-dd HH:mm:ss";
    
    /**
     * 将时间字符串格式化为标准格式，自动补0
     * 例如: 2025-3-14 3:3:12 -> 2025-03-14 03:03:12
     * 
     * @param timeStr 输入的时间字符串
     * @return 格式化后的时间字符串
     */
    public static String formatTimeString(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return timeStr == null ? null : timeStr.trim();
        }
        
        String result = timeStr.trim();
        
        // 分步处理，确保逻辑正确
        // 1. 处理日期部分: yyyy-M-d -> yyyy-MM-dd
        result = result.replaceAll("(\\d{4})-(\\d{1})-(\\d{1})\\s", "$1-0$2-0$3 ");
        result = result.replaceAll("(\\d{4})-(\\d{1})-(\\d{2})\\s", "$1-0$2-$3 ");
        result = result.replaceAll("(\\d{4})-(\\d{2})-(\\d{1})\\s", "$1-$2-0$3 ");
        
        // 2. 处理时间部分: H:m:s -> HH:mm:ss
        result = result.replaceAll("\\s(\\d{1}):(\\d{1}):(\\d{1})$", " 0$1:0$2:0$3");
        result = result.replaceAll("\\s(\\d{1}):(\\d{1}):(\\d{2})$", " 0$1:0$2:$3");
        result = result.replaceAll("\\s(\\d{1}):(\\d{2}):(\\d{1})$", " 0$1:$2:0$3");
        result = result.replaceAll("\\s(\\d{1}):(\\d{2}):(\\d{2})$", " 0$1:$2:$3");
        result = result.replaceAll("\\s(\\d{2}):(\\d{1}):(\\d{1})$", " $1:0$2:0$3");
        result = result.replaceAll("\\s(\\d{2}):(\\d{1}):(\\d{2})$", " $1:0$2:$3");
        result = result.replaceAll("\\s(\\d{2}):(\\d{2}):(\\d{1})$", " $1:$2:0$3");
        
        return result;
    }
    
    /**
     * 验证并格式化时间字符串
     * 
     * @param timeStr 输入的时间字符串
     * @return 格式化后的时间字符串
     * @throws ParseException 如果时间格式无效
     */
    public static String validateAndFormat(String timeStr) throws ParseException {
        String formattedTime = formatTimeString(timeStr);
        
        // 验证格式化后的时间是否有效
        SimpleDateFormat sdf = new SimpleDateFormat(STANDARD_FORMAT);
        sdf.setLenient(false);
        sdf.parse(formattedTime);
        
        return formattedTime;
    }
    
    /**
     * 比较两个时间字符串，检查结束时间是否晚于开始时间
     * 
     * @param startTimeStr 开始时间字符串
     * @param endTimeStr 结束时间字符串
     * @return true如果结束时间晚于开始时间
     * @throws ParseException 如果时间格式无效
     */
    public static boolean isEndTimeAfterStartTime(String startTimeStr, String endTimeStr) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat(STANDARD_FORMAT);
        sdf.setLenient(false);
        
        Date startDate = sdf.parse(startTimeStr);
        Date endDate = sdf.parse(endTimeStr);
        
        return endDate.after(startDate);
    }
} 