package com.example.verirag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 微信客服增量同步游标与消息去重状态。 */
@Mapper
public interface WeComKfStateMapper {

    String selectCursor(@Param("openKfId") String openKfId);

    int upsertCursor(@Param("openKfId") String openKfId,
                     @Param("cursor") String cursor);

    boolean isProcessed(@Param("messageId") String messageId);

    int insertProcessed(@Param("messageId") String messageId,
                        @Param("openKfId") String openKfId,
                        @Param("externalUserId") String externalUserId,
                        @Param("messageType") String messageType);
}
