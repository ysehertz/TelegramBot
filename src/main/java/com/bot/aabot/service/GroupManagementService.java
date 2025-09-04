package com.bot.aabot.service;

import com.bot.aabot.dao.GroupDao;
import com.bot.aabot.utils.BotReplyUtil;
import com.bot.aabot.utils.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * ClassName: GroupManagementService
 * Package: com.bot.aabot.service
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Slf4j
@Service
public class GroupManagementService {

    @Autowired
    private GroupDao groupDao;
    /**
     * 检查群聊是否在 res_group 表中，允许bot回复
     * @param update 消息更新
     * @return 是否允许回复
     */
    public boolean isGroupEnabledForResponse(Update update) {
        try {
            String groupId = String.valueOf(update.getMessage().getChatId());
            String threadId = BotReplyUtil.getThreadId(update);

            Integer count = groupDao.isGroupEnabledForResponse(groupId, threadId);

            boolean isEnabled = count != null && count > 0;

            LoggingUtils.logOperation("CHECK_GROUP_RESPONSE", groupId,
                    String.format("检查群聊回复权限 - GroupId: %s, ThreadId: %s, 结果: %s",
                            groupId, threadId, isEnabled ? "允许" : "禁止"));

            return isEnabled;

        } catch (Exception e) {
            LoggingUtils.logError("CHECK_GROUP_RESPONSE_ERROR", "检查群聊回复权限失败", e);
            return false; // 默认不允许回复
        }
    }

    /**
     * 添加群聊到回复白名单
     * @param groupId 群聊ID
     * @param threadId 话题ID（可为空）
     * @return 操作结果消息
     */
    public String addGroupToResponse(String groupId, String threadId) {
        try {

            Integer existingCount = groupDao.addGroupToResponse(groupId, threadId);
            if(existingCount == null || existingCount > 0){
                return String.format("群聊已在回复白名单中\nGroupId: %s\nThreadId: %s",
                        groupId, threadId != null ? threadId : "无");
            }
            LoggingUtils.logOperation("ADD_GROUP_RESPONSE", groupId,
                    String.format("添加群聊到回复白名单 - GroupId: %s, ThreadId: %s", groupId, threadId));

            return String.format("✅ 成功添加群聊到回复白名单\nGroupId: %s\nThreadId: %s\n\n现在机器人会在此群聊中回复消息",
                    groupId, threadId != null ? threadId : "无");

        } catch (Exception e) {
            LoggingUtils.logError("ADD_GROUP_RESPONSE_ERROR", "添加群聊到回复白名单失败", e);
            return "❌ 添加失败：" + e.getMessage();
        }
    }

    /**
     * 从回复白名单移除群聊
     * @param groupId 群聊ID
     * @param threadId 话题ID（可为空）
     * @return 操作结果消息
     */
    public String removeGroupFromResponse(String groupId, String threadId) {
        try {

            int deletedRows = groupDao.removeGroupFromResponse(groupId , threadId);

            if (deletedRows > 0) {
                LoggingUtils.logOperation("REMOVE_GROUP_RESPONSE", groupId,
                        String.format("从回复白名单移除群聊 - GroupId: %s, ThreadId: %s", groupId, threadId));

                return String.format("✅ 成功从回复白名单移除群聊\nGroupId: %s\nThreadId: %s\n\n机器人将不再在此群聊中回复消息",
                        groupId, threadId != null ? threadId : "无");
            } else {
                return String.format("⚠️ 群聊不在回复白名单中\nGroupId: %s\nThreadId: %s",
                        groupId, threadId != null ? threadId : "无");
            }

        } catch (Exception e) {
            LoggingUtils.logError("REMOVE_GROUP_RESPONSE_ERROR", "从回复白名单移除群聊失败", e);
            return "❌ 移除失败：" + e.getMessage();
        }
    }

    /**
     * 获取当前群聊的回复白名单状态
     * @param groupId 群聊ID
     * @param threadId 话题ID（可为空）
     * @return 状态消息
     */
    public String getGroupResponseStatus(String charName,String groupId, String threadId) {
        try {
            Integer count = groupDao.getGroupResponseStatus(groupId, threadId);


            boolean isEnabled = count != null && count > 0;

            String status = isEnabled ? "✅ 已启用" : "❌ 未启用";
            return String.format("当前群聊回复状态：%s\nGroupId: %s\nThreadId: %s\n\n%s",
                    status, groupId, threadId != null ? threadId : "无",
                    isEnabled ? "机器人会回复消息" : "机器人不会回复消息");

        } catch (Exception e) {
            LoggingUtils.logError("GET_GROUP_RESPONSE_STATUS_ERROR", "获取群聊回复状态失败", e);
            return "❌ 获取状态失败：" + e.getMessage();
        }
    }
}
