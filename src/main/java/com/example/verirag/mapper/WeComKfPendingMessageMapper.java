package com.example.verirag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 微信上游游标已推进但尚未完成回复的消息暂存，支持异常恢复。 */
@Mapper
public interface WeComKfPendingMessageMapper {

    int insertPending(@Param("messageId") String messageId,
                      @Param("openKfId") String openKfId,
                      @Param("externalUserId") String externalUserId,
                      @Param("payloadJson") String payloadJson);

    List<String> listPendingPayloads(@Param("limit") int limit);

    int deletePending(@Param("messageId") String messageId);
}
