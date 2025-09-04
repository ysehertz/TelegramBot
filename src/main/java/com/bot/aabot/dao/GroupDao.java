package com.bot.aabot.dao;

import com.bot.aabot.context.DataContext;
import com.bot.aabot.utils.LoggingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ClassName: GroupDao
 * Package: com.bot.aabot.dao
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/9/4
 */
@Repository
public class GroupDao {
    @Autowired
    private JdbcTemplate jdbcTemplate;


    /**
     * 检查群聊是否在响应列表中
     * @param groupId
     * @param threadId
     * @return
     */
    public Integer isGroupEnabledForResponse(String groupId, String threadId) throws Exception{
        String sql;
        Object[] params;

        if (threadId != null) {
            // 如果有thread_id，检查精确匹配
            sql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ? ";
            params = new Object[]{groupId, threadId};
        } else {
            // 如果没有thread_id，检查group_id匹配且thread_id为空的记录
            sql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
            params = new Object[]{groupId};
        }
        return jdbcTemplate.queryForObject(sql, Integer.class, params);
    }

    /**
     * 获取管理群组
     * @return
     */
    public String getAdminGroup() {
        String sql = "SELECT group_id FROM admin_group LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, String.class);
        } catch (Exception e) {
            LoggingUtils.logError("GET_ADMIN_GROUP_ERROR", "获取管理群组失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 添加群聊到AI互动列表
     * @param groupId
     * @param threadId
     * @return
     */
    public Integer addGroupToResponse(String groupId, String threadId) {
        // 检查是否已存在
        String checkSql;
        Object[] checkParams;

        if (threadId != null) {
            checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ?";
            checkParams = new Object[]{groupId, threadId};
        } else {
            checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
            checkParams = new Object[]{groupId};
        }

        Integer existingCount = jdbcTemplate.queryForObject(checkSql, Integer.class, checkParams);
        if (existingCount != null && existingCount > 0) {
            return existingCount;
        }else {
            // 插入新记录
            String insertSql = "INSERT INTO res_group (group_id, thread_id) VALUES (?, ?)";
            jdbcTemplate.update(insertSql, groupId, threadId);
            return -1;
        }


    }

    /**
     * 删除群聊从AI互动列表
     * @param groupId
     * @param threadId
     * @return
     */
    public int removeGroupFromResponse(String groupId, String threadId) {
        String deleteSql;
        Object[] deleteParams;

        if (threadId != null) {
            deleteSql = "DELETE FROM res_group WHERE group_id = ? AND thread_id = ?";
            deleteParams = new Object[]{groupId, threadId};
        } else {
            deleteSql = "DELETE FROM res_group WHERE group_id = ? AND thread_id IS NULL";
            deleteParams = new Object[]{groupId};
        }

        return jdbcTemplate.update(deleteSql, deleteParams);
    }

    /**
     * 获取群聊的AI互动状态
     * @param groupId
     * @param threadId
     * @return
     */
    public Integer getGroupResponseStatus(String groupId, String threadId) {
        String checkSql;
        Object[] checkParams;

        if (threadId != null) {
            checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id = ?";
            checkParams = new Object[]{groupId, threadId};
        } else {
            checkSql = "SELECT COUNT(*) FROM res_group WHERE group_id = ? AND thread_id IS NULL";
            checkParams = new Object[]{groupId};
        }

        return jdbcTemplate.queryForObject(checkSql, Integer.class, checkParams);
    }

    /**
     * 获取用户发送的消息ID列表
     * @param chatIdStr
     * @param userIdStr
     * @return
     */
    public List<Integer> getUserMessages(String chatIdStr, String userIdStr) {
        return  jdbcTemplate.query(
                "SELECT message_id FROM " + DataContext.tableName + " WHERE chat_id = ? AND user_id = ?",
                ps -> {
                    ps.setString(1, chatIdStr);
                    ps.setString(2, userIdStr);
                },
                (rs, rowNum) -> rs.getInt("message_id"));
    }
}
