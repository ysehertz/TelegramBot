/*
 Navicat Premium Data Transfer

 Source Server         : tg_archive
 Source Server Type    : SQLite
 Source Server Version : 3035005 (3.35.5)
 Source Schema         : main

 Target Server Type    : SQLite
 Target Server Version : 3035005 (3.35.5)
 File Encoding         : 65001

 Date: 12/08/2025 16:54:27
*/

PRAGMA foreign_keys = false;

-- ----------------------------
-- Table structure for _res_group_old_20250729
-- ----------------------------
DROP TABLE IF EXISTS "_res_group_old_20250729";
CREATE TABLE "_res_group_old_20250729" (
  "thread_id" TEXT,
  "group_id" text NOT NULL,
  PRIMARY KEY ("group_id")
);

-- ----------------------------
-- Table structure for _res_group_old_20250729_1
-- ----------------------------
DROP TABLE IF EXISTS "_res_group_old_20250729_1";
CREATE TABLE "_res_group_old_20250729_1" (
  "thread_id" TEXT NOT NULL,
  "group_id" text NOT NULL,
  PRIMARY KEY ("group_id", "thread_id")
);

-- ----------------------------
-- Table structure for _res_group_old_20250729_2
-- ----------------------------
DROP TABLE IF EXISTS "_res_group_old_20250729_2";
CREATE TABLE "_res_group_old_20250729_2" (
  "thread_id" TEXT NOT NULL,
  "group_id" text NOT NULL
);

-- ----------------------------
-- Table structure for admin_group
-- ----------------------------
DROP TABLE IF EXISTS "admin_group";
CREATE TABLE "admin_group" (
  "group_id" TEXT
);

-- ----------------------------
-- Table structure for admin_user
-- ----------------------------
DROP TABLE IF EXISTS "admin_user";
CREATE TABLE "admin_user" (
  "user_id" TEXT,
  "user_name" TEXT
);

-- ----------------------------
-- Table structure for base_achievement_list
-- ----------------------------
DROP TABLE IF EXISTS "base_achievement_list";
CREATE TABLE "base_achievement_list" (
  "achievement_name" TEXT,
  "achievement_id" INTEGER,
  "achievement_description" TEXT,
  "achievement_type" TEXT,
  "condition_count" INTEGER,
  "reward" TEXT
);

-- ----------------------------
-- Table structure for event_achievements
-- ----------------------------
DROP TABLE IF EXISTS "event_achievements";
CREATE TABLE "event_achievements" (
  "event_id" INTEGER,
  "achievement_name" TEXT,
  "achievement_id" TEXT,
  "achievement_description" TEXT,
  "achievement_type" TEXT,
  "condition_count" INTEGER,
  "reward" TEXT
);

-- ----------------------------
-- Table structure for event_records
-- ----------------------------
DROP TABLE IF EXISTS "event_records";
CREATE TABLE "event_records" (
  "event_id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "event_name" TEXT,
  "event_description" TEXT,
  "start_time" TEXT,
  "end_time" TEXT,
  "event_group_id" TEXT,
  "admin_group_id" TEXT,
  "creator_id" TEXT
);

-- ----------------------------
-- Table structure for global_achievements
-- ----------------------------
DROP TABLE IF EXISTS "global_achievements";
CREATE TABLE "global_achievements" (
  "achievement_id" INTEGER NOT NULL,
  "achievement_name" TEXT,
  "achievement_description" TEXT,
  "achievement_type" TEXT,
  "condition_count" INTEGER,
  "reward" TEXT
);

-- ----------------------------
-- Table structure for group_chat_records
-- ----------------------------
DROP TABLE IF EXISTS "group_chat_records";
CREATE TABLE "group_chat_records" (
  "group_id" TEXT,
  "group_name" TEXT,
  "topic_id" integer,
  "topic_name" TEXT
);

-- ----------------------------
-- Table structure for log
-- ----------------------------
DROP TABLE IF EXISTS "log";
CREATE TABLE "log" (
  "log_id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "form_name" TEXT,
  "message_id" INTEGER,
  "user_id" TEXT,
  "user_name" TEXT,
  "message_type" TEXT,
  "message" TEXT,
  "is_edit" INTEGER DEFAULT 0,
  "send_time" TEXT,
  "chat_id" TEXT,
  "topic_id" TEXT
);

-- ----------------------------
-- Table structure for res
-- ----------------------------
DROP TABLE IF EXISTS "res";
CREATE TABLE "res" (
  "id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "original_question" TEXT NOT NULL,
  "message_id" INTEGER,
  "session_id" TEXT,
  "user_name" TEXT,
  "user_id" INTEGER,
  "gpt_res" TEXT,
  "res_time" TEXT DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now', 'localtime'))
);

-- ----------------------------
-- Table structure for res_group
-- ----------------------------
DROP TABLE IF EXISTS "res_group";
CREATE TABLE "res_group" (
  "thread_id" TEXT,
  "group_id" text NOT NULL,
  PRIMARY KEY ("group_id", "thread_id")
);

-- ----------------------------
-- Table structure for sqlite_sequence
-- ----------------------------
DROP TABLE IF EXISTS "sqlite_sequence";
CREATE TABLE "sqlite_sequence" (
  "name",
  "seq"
);

-- ----------------------------
-- Table structure for user_achievements
-- ----------------------------
DROP TABLE IF EXISTS "user_achievements";
CREATE TABLE "user_achievements" (
  "achievement_id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "user_id" TEXT,
  "achievement_name" TEXT,
  "progress" INTEGER,
  "complete_time" TEXT,
  "event_id" INTEGER,
  "is_global" INTEGER DEFAULT 0,
  "chat_id" TEXT,
  "user_name" TEXT
);

-- ----------------------------
-- Table structure for user_activity_logs
-- ----------------------------
DROP TABLE IF EXISTS "user_activity_logs";
CREATE TABLE "user_activity_logs" (
  "log_id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "user_id" TEXT,
  "activity_type" TEXT,
  "activity_time" TEXT,
  "activity_log" TEXT,
  "event_id" INTEGER,
  "topic_id" INTEGER,
  "chat_id" TEXT
);

-- ----------------------------
-- Table structure for user_join_time
-- ----------------------------
DROP TABLE IF EXISTS "user_join_time";
CREATE TABLE "user_join_time" (
  "id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "user_id" TEXT NOT NULL,
  "group_id" TEXT NOT NULL,
  "chat_id" TEXT NOT NULL,
  "join_time" TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now','localtime')),
  UNIQUE ("user_id" ASC, "group_id" ASC)
);

-- ----------------------------
-- Table structure for user_points
-- ----------------------------
DROP TABLE IF EXISTS "user_points";
CREATE TABLE "user_points" (
  "event_id" INTEGER,
  "chat_name" TEXT,
  "user_id" INTEGER,
  "points" INTEGER,
  "special_points" INTEGER,
  "role" TEXT,
  "aggregate_points" TEXT,
  "user_name" TEXT
);

-- ----------------------------
-- Auto increment value for log
-- ----------------------------
UPDATE "sqlite_sequence" SET seq = 13416 WHERE name = 'log';

-- ----------------------------
-- Auto increment value for res
-- ----------------------------
UPDATE "sqlite_sequence" SET seq = 212 WHERE name = 'res';

-- ----------------------------
-- Auto increment value for user_achievements
-- ----------------------------
UPDATE "sqlite_sequence" SET seq = 652 WHERE name = 'user_achievements';

-- ----------------------------
-- Auto increment value for user_activity_logs
-- ----------------------------
UPDATE "sqlite_sequence" SET seq = 3536 WHERE name = 'user_activity_logs';

-- ----------------------------
-- Auto increment value for user_join_time
-- ----------------------------
UPDATE "sqlite_sequence" SET seq = 275 WHERE name = 'user_join_time';

PRAGMA foreign_keys = true;
