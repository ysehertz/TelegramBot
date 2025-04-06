package com.bot.aabot.context;

import com.bot.aabot.entity.TextMessageEntity;
import groovyjarjarpicocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * ClassName: MessageContext
 * Package: com.bot.aabot.context
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/4/1
 */
public class MessageContext {
    public static List<TextMessageEntity> messageContextList = new LinkedList<>();
}
