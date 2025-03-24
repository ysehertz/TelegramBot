package com.bot.aabot.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ClassName: SQLiteUtil
 * Package: com.bot.aabot.utils
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/19
 */
@Component
public class SQLiteUtil {
    @Autowired
    JdbcTemplate jdbcTemplate;
    public void createTable(String sql) {
        jdbcTemplate.execute(sql);
    }
    public void exeSql(String sql) {
        jdbcTemplate.execute(sql);
    }
}
