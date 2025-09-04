package com.bot.aabot.service;

import com.bot.aabot.dao.GroupDao;
import com.bot.aabot.utils.LoggingUtils;
import com.bot.aabot.utils.MessagesUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import java.util.List;

/**
 * ClassName: UserService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Slf4j
@Service
public class UserService {

    @Autowired
    private GroupDao groupDao;
    /**
     * 封禁用户
     * @param bot
     * @param chatIdStr
     * @param userIdStr
     */
    public void adminBlacklistUser(Object bot, String chatIdStr, String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            // 1) 批量撤回历史消息
            try {
                List<Integer> messageIds = groupDao.getUserMessages(chatIdStr, userIdStr);

                if (messageIds != null) {
                    LoggingUtils.logOperation("BLACKLIST_DELETE_COUNT", userIdStr, "准备撤回消息数量=" + messageIds.size());
                    for (Integer mid : messageIds) {
                        try {
                            MessagesUtil.deleteMessage(bot, Long.parseLong(chatIdStr), mid);
                        } catch (Exception ex) {
                            // 忽略单条失败
                        }
                    }
                }
            } catch (Exception e) {
                LoggingUtils.logError("BLACKLIST_DELETE_HISTORY_ERROR", "删除历史消息失败", e);
            }
            // 2) 永久封禁并踢出
            try {
                BanChatMember ban = BanChatMember.builder()
                        .chatId(chatIdStr)
                        .userId(userId)
                        .untilDate((int)(System.currentTimeMillis()/1000 + 365*24*3600))
                        .build();
                bot.getClass().getMethod("banUser", BanChatMember.class).invoke(bot, ban);
                LoggingUtils.logOperation("BLACKLIST_DONE", userIdStr, "已加入黑名单并踢出群聊");
            } catch (Exception e) {
                LoggingUtils.logError("BLACKLIST_BAN_ERROR", "加入黑名单/踢出失败", e);
            }
        } catch (Exception e) {
            LoggingUtils.logError("BLACKLIST_ERROR", "处理黑名单失败", e);
        }
    }

    /**
     * 获取用户名称
     * @param user
     * @return
     */
    public String getUserDisplayName(org.telegram.telegrambots.meta.api.objects.User user) {
        StringBuilder displayName = new StringBuilder();

        // 优先使用用户名
        if (user.getUserName() != null && !user.getUserName().trim().isEmpty()) {
            displayName.append("@").append(user.getUserName());
        } else {
            // 如果没有用户名，使用真实姓名
            if (user.getFirstName() != null && !user.getFirstName().trim().isEmpty()) {
                displayName.append(user.getFirstName());
            }
            if (user.getLastName() != null && !user.getLastName().trim().isEmpty()) {
                if (displayName.length() > 0) {
                    displayName.append(" ");
                }
                displayName.append(user.getLastName());
            }
        }

        // 如果都没有，使用默认值
        if (displayName.length() == 0) {
            displayName.append("未知用户");
        }

        return displayName.toString();
    }
}
