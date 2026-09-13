package com.example.verirag.mapper;

import com.example.verirag.entity.WeComCustomer;
import com.example.verirag.entity.WeComCustomerMessage;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WeComCustomerMapper {
    @Insert("""
        INSERT INTO t_wecom_kf_customer(open_kf_id,external_user_id,last_seen_at)
        VALUES(#{kf},#{external},#{at}) ON DUPLICATE KEY UPDATE last_seen_at=GREATEST(last_seen_at,VALUES(last_seen_at))
        """)
    int touch(@Param("kf") String kf, @Param("external") String external, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM t_wecom_kf_customer WHERE id=#{id}")
    WeComCustomer find(@Param("id") long id);

    @Select("""
        SELECT * FROM t_wecom_kf_customer
        WHERE (#{kf}='' OR open_kf_id=#{kf})
          AND (#{keyword}='' OR LOCATE(#{keyword},COALESCE(nickname,''))>0 OR LOCATE(#{keyword},external_user_id)>0)
        ORDER BY last_seen_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<WeComCustomer> list(@Param("kf") String kf, @Param("keyword") String keyword,
                            @Param("limit") int limit, @Param("offset") long offset);

    @Update("""
        UPDATE t_wecom_kf_customer SET nickname=#{nickname},avatar=#{avatar},profile_updated_at=CURRENT_TIMESTAMP WHERE id=#{id}
        """)
    int profile(@Param("id") long id, @Param("nickname") String nickname, @Param("avatar") String avatar);

    @Update("""
        UPDATE t_wecom_kf_customer SET service_summary=#{summary},summary_updated_at=#{at} WHERE id=#{id}
        """)
    int summary(@Param("id") long id, @Param("summary") String summary, @Param("at") LocalDateTime at);

    @Insert("""
        INSERT IGNORE INTO t_wecom_kf_customer_message(open_kf_id,external_user_id,message_id,speaker,message_type,content,sent_at)
        VALUES(#{kf},#{external},#{msg},#{speaker},#{type},#{content},#{at})
        """)
    int archive(@Param("kf") String kf, @Param("external") String external, @Param("msg") String msg,
                @Param("speaker") String speaker, @Param("type") String type,
                @Param("content") String content, @Param("at") LocalDateTime at);

    @Select("""
        SELECT m.* FROM t_wecom_kf_customer_message m
        JOIN t_wecom_kf_customer c ON c.open_kf_id=m.open_kf_id AND c.external_user_id=m.external_user_id
        WHERE c.id=#{id} AND (#{before}=0 OR m.id<#{before}) ORDER BY m.id DESC LIMIT #{limit}
        """)
    List<WeComCustomerMessage> messages(@Param("id") long id, @Param("before") long before, @Param("limit") int limit);

    @Select("""
        SELECT MIN(m.sent_at) FROM t_wecom_kf_customer_message m
        JOIN t_wecom_kf_customer c ON c.open_kf_id=m.open_kf_id AND c.external_user_id=m.external_user_id WHERE c.id=#{id}
        """)
    LocalDateTime firstArchivedAt(@Param("id") long id);

    @Select("""
        SELECT cm.id,CONCAT('legacy-',cm.id) AS message_id,
            CASE WHEN cm.role='USER' THEN 'CUSTOMER' ELSE 'BOT' END AS speaker,
            'text' AS message_type,cm.content,cm.create_time AS sent_at
        FROM t_wecom_kf_customer c
        JOIN t_wecom_conversation wc ON wc.bot_id=CONCAT('kf:',c.open_kf_id) AND wc.conversation_key=c.external_user_id
        JOIN t_chat_session cs ON cs.id=wc.session_id AND cs.user_id=wc.user_id
        JOIN t_chat_message cm ON cm.session_id=cs.id
        WHERE c.id=#{id} AND (#{before}=0 OR cm.id<#{before})
          AND (#{cutoff} IS NULL OR cm.create_time<#{cutoff})
        ORDER BY cm.id DESC LIMIT #{limit}
        """)
    List<WeComCustomerMessage> legacy(@Param("id") long id, @Param("before") long before,
                                      @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("""
        SELECT cs.memory_summary FROM t_wecom_kf_customer c
        JOIN t_wecom_conversation wc ON wc.bot_id=CONCAT('kf:',c.open_kf_id) AND wc.conversation_key=c.external_user_id
        JOIN t_chat_session cs ON cs.id=wc.session_id AND cs.user_id=wc.user_id WHERE c.id=#{id}
        """)
    String memorySummary(@Param("id") long id);
}
