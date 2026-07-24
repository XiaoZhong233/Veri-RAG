-- 为已有数据库执行一次。完整聊天记录不受影响，仅增加用于压缩记忆的会话字段。
ALTER TABLE t_chat_session
    ADD COLUMN memory_summary MEDIUMTEXT NULL COMMENT '已压缩的早期会话记忆' AFTER title,
    ADD COLUMN summarized_message_count INT NOT NULL DEFAULT 0 COMMENT '已写入摘要的消息数量' AFTER memory_summary;
