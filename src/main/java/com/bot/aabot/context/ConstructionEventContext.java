package com.bot.aabot.context;

import com.bot.aabot.entity.EventRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ClassName: ConstructionEventContext
 * Package: com.bot.aabot.context
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/5/15
 */
public class ConstructionEventContext {
    public static List<Map<String, String>> eventList = new ArrayList<>();
    public static String creator_id;
    public static EventRecord constructionEvent = new EventRecord();
    public static String chatId;
}
