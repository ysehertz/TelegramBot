package com.bot.aabot.task;

import com.bot.aabot.initializer.BotContext;
import com.bot.aabot.context.DataContext;
import com.bot.aabot.utils.SQLiteUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * ClassName: DataTask
 * Package: com.bot.aabot.task
 * Description:
 *
 * @author fuchen
 * @version 1.0
 * @createTime 2025/3/19
 */
@Component
@Slf4j
@EnableScheduling
public class DataTask {
    @Autowired
    private SQLiteUtil sqLiteUtil;


    @Scheduled(cron = "0 0 0 * * ?")
    public void upTableName() {
        if("one".equals(BotContext.OneOrEveryday)){
            DataContext.tableName = "log" ;
            DataContext.resTableName = "res";
        }else {
            DataContext.tableName = "log_" + new Date().toString().substring(24, 28).substring(2) + "_" + (new Date().getMonth() + 1) + "_" + new Date().toString().substring(8, 10);
            DataContext.resTableName = "res_" + new Date().toString().substring(24, 28).substring(2) + "_" + (new Date().getMonth() + 1) + "_" + new Date().toString().substring(8, 10);

        }
        String sql = "CREATE TABLE IF NOT EXISTS " + DataContext.tableName +"( " +
                "    log_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    form_name TEXT,              " +
                "    message_id INTEGER,          " +
                "    user_id TEXT,                " +
                "    user_name TEXT,              " +
                "    message_type TEXT,           " +
                "    message TEXT,  " +
                "    is_edit INTEGER DEFAULT 0,    " +
                "     send_time TEXT            "+
                ");";
        sqLiteUtil.createTable(sql);

        sql = "CREATE TABLE IF NOT EXISTS "+DataContext.resTableName+ "(" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "    original_question TEXT NOT NULL, " +
                "    message_id INTEGER UNIQUE,  " +
                "    session_id TEXT,  " +
                "    user_name TEXT,   " +
                "    user_id INTEGER,  " +
                "    gpt_res TEXT,    " +
                "    res_time TEXT DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now','localtime'))" +
                ");";
        sqLiteUtil.createTable(sql);
    }
}
