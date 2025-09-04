package com.bot.aabot.utils;

import com.bot.aabot.config.BotConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ClassName: MessagesUtil
 * Package: com.bot.aabot.utils
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Component
@Slf4j
public class MessagesUtil {
    @Autowired
    private BotConfig botConfig;
    /**
     * 清理消息文本（去除空格、标点符号等）
     * @param text
     * @return
     */
    public static String cleanMessageText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s,。;、]", "").toLowerCase();
    }

    /**
     * 读取禁止词文件
     */
    public static List<String> readForbiddenWords(String forbidFilePath) {
        List<String> words = new ArrayList<>();
//        String forbidFilePath = botConfig.getForbidUrl();

        if (forbidFilePath == null || forbidFilePath.trim().isEmpty()) {
            LoggingUtils.logError("FORBID_FILE_ERROR", "禁止词文件路径未配置", null);
            return words;
        }

        try {
            Path path = Paths.get(forbidFilePath);
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        words.add(cleanMessageText(trimmed));
                    }
                }
            } else {
                LoggingUtils.logError("FORBID_FILE_NOT_FOUND", "禁止词文件不存在: " + forbidFilePath, null);
            }
        } catch (Exception e) {
            LoggingUtils.logError("READ_FORBID_FILE_ERROR", "读取禁止词文件失败", e);
        }

        return words;
    }

    /**
     * 撤回消息
     */
    public static void deleteMessage(Object bot, Long chatId, Integer messageId) {
        try {
            DeleteMessage deleteMessage = DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build();

            // 使用bot的deleteMessage方法来执行删除操作
            bot.getClass().getMethod("deleteMessage", DeleteMessage.class).invoke(bot, deleteMessage);

        } catch (Exception e) {
            LoggingUtils.logError("DELETE_MESSAGE_ERROR", "撤回消息失败", e);
        }
    }

    /**
     * 限制用户权限（临时禁言）
     */
    public static void banUser(Object bot, Long chatId, Long userId, int duration) {
        try {
            // 使用RestrictChatMember来限制用户权限，而不是踢出群聊
            // 限制所有权限（禁言）
            ChatPermissions restrictedPermissions = ChatPermissions.builder()
                    .canSendMessages(false)
                    .canSendAudios(false)
                    .canSendDocuments(false)
                    .canSendPhotos(false)
                    .canSendVideos(false)
                    .canSendVideoNotes(false)
                    .canSendVoiceNotes(false)
                    .canSendPolls(false)
                    .canSendOtherMessages(false)
                    .canAddWebPagePreviews(false)
                    .canChangeInfo(false)
                    .canInviteUsers(false)
                    .canPinMessages(false)
                    .canManageTopics(false)
                    .build();

            RestrictChatMember restrictChatMember = RestrictChatMember.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .permissions(restrictedPermissions)
                    .untilDate((int) (System.currentTimeMillis() / 1000 + duration))
                    .build();

            // 使用bot的restrictUser方法来执行限制操作
            bot.getClass().getMethod("restrictUser", RestrictChatMember.class).invoke(bot, restrictChatMember);

        } catch (Exception e) {
            LoggingUtils.logError("RESTRICT_USER_ERROR", "限制用户权限失败", e);
        }
    }
}
