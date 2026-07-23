DROP DATABASE IF EXISTS veri_rag;
CREATE DATABASE veri_rag DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE veri_rag;

-- 用户表（管理员 ADMIN / 普通用户 USER）
CREATE TABLE t_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL COMMENT '登录名',
  password      VARCHAR(32)  NOT NULL COMMENT 'MD5 密码',
  real_name     VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
  avatar        VARCHAR(512) DEFAULT NULL COMMENT '头像相对路径（对应 /files/ 下资源）',
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN / USER',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 知识库分类表
CREATE TABLE t_kb_category (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(128) NOT NULL COMMENT '分类名称',
  description VARCHAR(512) DEFAULT NULL COMMENT '描述',
  icon        VARCHAR(64)  DEFAULT NULL COMMENT '图标标识',
  sort_order  INT         NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库分类';

-- 知识文档元数据表
CREATE TABLE t_kb_document (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  category_id    BIGINT       NOT NULL COMMENT '分类ID',
  title          VARCHAR(255) NOT NULL COMMENT '显示标题',
  file_name      VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_path      VARCHAR(512) NOT NULL COMMENT '磁盘相对路径（相对 uploads 根）',
  file_type      VARCHAR(16)  NOT NULL COMMENT '扩展名小写',
  file_size      BIGINT       NOT NULL DEFAULT 0 COMMENT '字节大小',
  status         VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAIL',
  vector_count   INT          NOT NULL DEFAULT 0 COMMENT '向量块数量',
  upload_user_id BIGINT       DEFAULT NULL COMMENT '上传用户ID',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_category (category_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档';

-- 问答会话
CREATE TABLE t_chat_session (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT      NOT NULL COMMENT '用户ID',
  title       VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话';

-- 问答消息
CREATE TABLE t_chat_message (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id  BIGINT      NOT NULL COMMENT '会话ID',
  role        VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT',
  content     MEDIUMTEXT  NOT NULL COMMENT '消息内容',
  refs        JSON        DEFAULT NULL COMMENT '引用文档JSON',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息';

-- 系统日志（统计用）
CREATE TABLE t_system_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT      DEFAULT NULL COMMENT '用户ID',
  action      VARCHAR(128) NOT NULL COMMENT '动作描述',
  ip          VARCHAR(64)  DEFAULT NULL COMMENT 'IP',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统日志';

-- ========== 测试数据 ==========
-- 密码均为 123456 的 MD5
INSERT INTO t_user (username, password, real_name, role, status) VALUES
('admin', 'e10adc3949ba59abbe56e057f20f883e', 'Admin', 'ADMIN', 1),
('user1', 'e10adc3949ba59abbe56e057f20f883e', 'Howell', 'USER', 1);

INSERT INTO t_kb_category (name, description, icon, sort_order) VALUES
('技术文档', '研发与接口说明类文档', 'Document', 1),
('规章制度', '公司制度与流程', 'Notebook', 2),
('产品手册', '产品使用说明', 'Goods', 3),
('常见问题', 'FAQ 与帮助', 'QuestionFilled', 4);

-- 示例文档元数据（实际文件由管理员在界面中上传后解析入库；此处仅演示列表）
INSERT INTO t_kb_document (category_id, title, file_name, file_path, file_type, file_size, status, vector_count, upload_user_id) VALUES
(1, '示例-API说明', 'api_example.txt', '202605/example_api.txt', 'txt', 1024, 'SUCCESS', 0, 1),
(2, '示例-考勤制度', 'hr_rules.pdf', '202605/hr_rules.pdf', 'pdf', 204800, 'PROCESSING', 0, 1);

INSERT INTO t_chat_session (user_id, title) VALUES (2, '新员工入职咨询');
INSERT INTO t_chat_message (session_id, role, content, refs) VALUES
(1, 'USER', '公司年假规则是什么？', NULL),
(1, 'ASSISTANT', '请上传规章制度文档后，我可基于内容回答。', JSON_ARRAY());

INSERT INTO t_system_log (user_id, action, ip, create_time) VALUES
(1, '登录系统', '127.0.0.1', NOW() - INTERVAL 6 DAY),
(2, '提问', '127.0.0.1', NOW() - INTERVAL 5 DAY),
(3, '提问', '127.0.0.1', NOW() - INTERVAL 4 DAY),
(2, '提问', '127.0.0.1', NOW() - INTERVAL 3 DAY),
(4, '登录系统', '127.0.0.1', NOW() - INTERVAL 2 DAY),
(2, '提问', '127.0.0.1', NOW() - INTERVAL 1 DAY);
