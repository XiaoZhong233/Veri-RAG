# Observability Demo Data

本脚本通过真实的登录、向量检索和流式大模型调用生成观测数据。它不会修改知识库，只会新增测试会话和聊天消息。

## 运行

确保 MySQL、Redis、可观测容器和应用均已运行后，执行：

```bash
bash scripts/observe-rag-demo.sh
```

默认使用初始化脚本中的 `admin / 123456`。如你的账号不同：

```bash
RAG_DEMO_USERNAME=your_user RAG_DEMO_PASSWORD=your_password \
  bash scripts/observe-rag-demo.sh
```

脚本会产生：4 次真实 RAG 调用和 1 次相同问题的缓存命中。应用每 15 秒向 OTel Collector 推送一次指标。

## Grafana 验证查询

进入 Grafana 的 Prometheus 数据源，使用以下查询：

```promql
# 流式 LLM 调用次数
rag_llm_duration_milliseconds_count{mode="stream",outcome="success"}

# 流式 LLM 平均首 Token 耗时（应用启动以来）
sum(rag_llm_duration_milliseconds_sum{mode="stream",outcome="success"})
/
sum(rag_llm_duration_milliseconds_count{mode="stream",outcome="success"})

# 最近 5 分钟的端到端 RAG P90 耗时
histogram_quantile(
  0.90,
  sum(rate(rag_request_duration_milliseconds_bucket{outcome="success"}[5m])) by (le)
)

# 回答缓存命中数
rag_cache_requests_total{result="hit"}
```

P90 初次显示 `No data` 时，继续运行脚本一次并等待至少两个 15 秒上报周期；`rate` 需要多个采样点。
