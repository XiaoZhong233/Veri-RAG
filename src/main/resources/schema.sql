CREATE TABLE IF NOT EXISTS t_residence (
    id               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_id        VARCHAR(128)   NOT NULL COMMENT 'HTML 中的公寓唯一标识',
    name             VARCHAR(255)   NOT NULL COMMENT '公寓名称',
    city             VARCHAR(128)   NOT NULL DEFAULT 'London' COMMENT '所在城市',
    region           VARCHAR(32)    DEFAULT NULL COMMENT '区域：east/west/north/south',
    zone             VARCHAR(64)    DEFAULT NULL COMMENT '伦敦交通分区',
    address          VARCHAR(512)   NOT NULL DEFAULT '' COMMENT '完整地址',
    station          VARCHAR(255)   DEFAULT NULL COMMENT '最近车站',
    latitude         DECIMAL(10, 7) DEFAULT NULL COMMENT '纬度',
    longitude        DECIMAL(10, 7) DEFAULT NULL COMMENT '经度',
    map_url          VARCHAR(1024)  DEFAULT NULL COMMENT '地图链接',
    source_file_name VARCHAR(255)   DEFAULT NULL COMMENT '最近来源文件',
    source_hash      CHAR(64)       DEFAULT NULL COMMENT '最近来源文件 SHA-256',
    active           TINYINT        NOT NULL DEFAULT 1 COMMENT '1有效，0已从最新数据源移除',
    create_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_residence_source_id (source_id),
    KEY idx_residence_name (name),
    KEY idx_residence_city_active (city, active),
    KEY idx_residence_region_active (region, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公寓地址库';

CREATE TABLE IF NOT EXISTS t_sales_recommendation (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    residence_id  BIGINT        NOT NULL COMMENT '优先推荐公寓ID',
    priority      INT           NOT NULL DEFAULT 100 COMMENT '同等条件排序优先级，数值越小越优先',
    enabled       TINYINT       NOT NULL DEFAULT 1 COMMENT '1启用，0停用',
    note          VARCHAR(512)  DEFAULT NULL COMMENT '内部推荐备注，不直接展示给用户',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_sales_recommendation_residence
        FOREIGN KEY (residence_id) REFERENCES t_residence(id) ON DELETE CASCADE,
    UNIQUE KEY uk_sales_recommendation_residence (residence_id),
    KEY idx_sales_recommendation_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售同等条件优先推荐配置';

INSERT IGNORE INTO t_sales_recommendation (residence_id, priority, enabled, note)
SELECT id, 100, 1, '原销售推荐规则迁移'
FROM t_residence
WHERE LOWER(source_id) = 'drapery-place'
   OR LOWER(name) IN ('drapery place', 'drapery place residence')
ORDER BY active DESC, id ASC
LIMIT 1;

CREATE TABLE IF NOT EXISTS t_offer_import_batch (
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_name                VARCHAR(255) NOT NULL COMMENT '导入文件名',
    file_hash                CHAR(64)     NOT NULL COMMENT '文件 SHA-256',
    status                   VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAIL',
    inventory_total          INT          NOT NULL DEFAULT 0 COMMENT '库存源记录数',
    inventory_inserted       INT          NOT NULL DEFAULT 0 COMMENT '新增库存数',
    inventory_updated        INT          NOT NULL DEFAULT 0 COMMENT '更新库存数',
    price_total              INT          NOT NULL DEFAULT 0 COMMENT '价格档位源记录数',
    price_inserted           INT          NOT NULL DEFAULT 0 COMMENT '新增价格档位数',
    price_updated            INT          NOT NULL DEFAULT 0 COMMENT '更新价格档位数',
    skipped                  INT          NOT NULL DEFAULT 0 COMMENT '因时间较旧等原因跳过数',
    upload_user_id           BIGINT       DEFAULT NULL COMMENT '上传用户ID',
    message                  VARCHAR(1024) DEFAULT NULL COMMENT '导入摘要',
    create_time              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finish_time              DATETIME     DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    KEY idx_offer_batch_time (create_time),
    KEY idx_offer_batch_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房型报价导入批次';

CREATE TABLE IF NOT EXISTS t_room_inventory (
    id                       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    residence_id             BIGINT        NOT NULL COMMENT '公寓ID',
    room_code                VARCHAR(128)  NOT NULL COMMENT '房型稳定编码',
    room_name                VARCHAR(255)  NOT NULL COMMENT '完整房型名称',
    root_type                VARCHAR(32)   NOT NULL COMMENT '房型大类',
    earliest_start_date      DATE          NOT NULL COMMENT '最早起租日期',
    latest_end_date          DATE          NOT NULL COMMENT '最晚退房日期',
    remaining_quantity       INT           DEFAULT NULL COMMENT '剩余数量，未知为空',
    inventory_status         VARCHAR(16)   NOT NULL COMMENT 'AVAILABLE/LIMITED/SOLD_OUT/UNKNOWN',
    inventory_updated_at     DATETIME      NOT NULL COMMENT '库存业务更新时间',
    note                     VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    source_file_name         VARCHAR(255)  DEFAULT NULL COMMENT '最近导入文件',
    import_batch_id          BIGINT        DEFAULT NULL COMMENT '最近导入批次',
    create_time              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_room_inventory_residence
        FOREIGN KEY (residence_id) REFERENCES t_residence(id) ON DELETE RESTRICT,
    CONSTRAINT fk_room_inventory_batch
        FOREIGN KEY (import_batch_id) REFERENCES t_offer_import_batch(id) ON DELETE SET NULL,
    UNIQUE KEY uk_room_inventory_scope
        (residence_id, room_code, earliest_start_date, latest_end_date),
    KEY idx_room_inventory_status (inventory_status),
    KEY idx_room_inventory_dates (earliest_start_date, latest_end_date),
    KEY idx_room_inventory_name (room_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房型库存';

CREATE TABLE IF NOT EXISTS t_room_price_tier (
    id                       BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    inventory_id             BIGINT         NOT NULL COMMENT '房型库存ID',
    min_weeks                INT            NOT NULL COMMENT '最短租期周数（含）',
    max_weeks                INT            DEFAULT NULL COMMENT '最长租期周数（含），空表示不限',
    weekly_price             DECIMAL(10, 2) NOT NULL COMMENT '每周价格',
    currency                 CHAR(3)        NOT NULL DEFAULT 'GBP' COMMENT '币种',
    price_updated_at         DATETIME       NOT NULL COMMENT '价格业务更新时间',
    note                     VARCHAR(1024)  DEFAULT NULL COMMENT '备注',
    source_file_name         VARCHAR(255)   DEFAULT NULL COMMENT '最近导入文件',
    import_batch_id          BIGINT         DEFAULT NULL COMMENT '最近导入批次',
    create_time              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_room_price_inventory
        FOREIGN KEY (inventory_id) REFERENCES t_room_inventory(id) ON DELETE CASCADE,
    CONSTRAINT fk_room_price_batch
        FOREIGN KEY (import_batch_id) REFERENCES t_offer_import_batch(id) ON DELETE SET NULL,
    UNIQUE KEY uk_room_price_min_weeks (inventory_id, min_weeks),
    KEY idx_room_price_range (min_weeks, max_weeks)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房型分档周租价格';
