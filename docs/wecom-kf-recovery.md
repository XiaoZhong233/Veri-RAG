# 微信客服消息恢复与人工意图

## 部署

本次只调整失败消息恢复和人工转接意图，不调整账号、密码或前端安全配置。
`schema.sql` 新增独立的 `t_wecom_kf_message_retry` 表；默认启动初始化会幂等创建，
不修改原有待处理表，不删除历史消息。如部署关闭了 SQL 初始化，需要先执行该表的建表语句。

## 重试与隔离

- 恢复任务每 15 秒扫描一次，只取到期、未隔离、未处理且不在当前实例执行中的消息。
- 处理失败后的等待间隔是 15、30、60、120、240 秒；第 6 次失败后停止自动重试。
- 第 6 次失败为隔离，不是成功：原始消息仍在待处理表中，日志包含 `isolated=true`，指标事件为 `retry_exhausted`。
- 损坏 JSON 单独记录失败，不终止本轮其他消息；线程池拒绝任务会释放执行标记并进入重试。
- 已处理的残留待处理记录不会占据扫描名额。成功处理会清理该消息的待处理和重试记录。
- 这是单实例恢复机制，不提供多实例分布式执行锁。

查看隔离记录（只读）：

```sql
SELECT p.message_id, p.open_kf_id, p.external_user_id, p.create_time,
       r.failure_count, r.next_retry_at
FROM t_wecom_kf_pending_message p
JOIN t_wecom_kf_message_retry r ON r.message_id = p.message_id
WHERE r.failure_count >= 6
ORDER BY p.create_time;
```

排除根因、确认仍需回复后，可对**一条明确消息**重置。此操作会重新尝试回复或转接，
先核对客户当前会话状态与消息时效，不要批量重放旧的转人工请求：

```sql
UPDATE t_wecom_kf_message_retry
SET failure_count = 0, next_retry_at = CURRENT_TIMESTAMP
WHERE message_id = '替换为已核实的单条消息ID' AND failure_count >= 6;
```

## 人工意图

微信渠道启用内部 `allowHumanHandoff` 标记，和房源意图共用一次分类请求，读取现有最近对话。
模型输出 `HUMAN_HANDOFF` 后直接执行接待人员分配，不再生成普通 RAG 回答。
不再使用原来的关键词包含匹配；网页提交的 JSON 不能启用内部动作。

分类提示覆盖明确转接、否定、询问人工服务时间、上下文确认和合并消息中的撤回。
模型报错、关闭或标签不明确时不自动转接，继续原问答路径。
生产环境应保持 `rag.intent-classifier.enabled=true`。
仍遵守原来的人工在线状态和微信会话状态约束；无人在线不会自动创造可用接待人员。

上线验收：测试“我想和真人聊”“不要转人工，继续找房”“人工几点上班”，
以及“转人工”后紧接“算了，先不转”的合并消息；核对意图日志和真实接待状态。
提示词不能保证模型永不误判，以上场景需要使用实际配置的模型验收。

## 验证范围

已补充分类标签、分类失败、内部动作、非关键词转接、否定文本、损坏消息及执行拒绝的测试代码。
遵照项目要求，不运行 Maven 编译；本次未执行 Java 测试、真实模型评测或线上部署。
