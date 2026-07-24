# VeriRAG Observability

本项目使用 Spring Boot Actuator、Micrometer 与 OpenTelemetry 记录 RAG 请求的指标和 Trace。`compose.yaml` 中的 `observability` 服务使用 `grafana/otel-lgtm`，包含 Grafana、Prometheus、Tempo、Loki 与 OpenTelemetry Collector，适合本地演示和 case study。

## 启动

```bash
docker compose up -d observability
./mvnw spring-boot:run
```

- Grafana: http://localhost:3000 （默认账号 `admin` / `admin`）
- Prometheus: http://localhost:9090
- 应用 health: http://localhost:8081/veri-rag/actuator/health
- 应用 Prometheus 指标: http://localhost:8081/veri-rag/actuator/prometheus

如果应用运行在 Docker 中，设置以下环境变量，使其将 OTLP 上报到 Compose 服务名：

```bash
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://observability:4318/v1/traces
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://observability:4318/v1/metrics
```

## RAG 指标

| Metric | 用途 |
| --- | --- |
| `rag.request.duration` | 端到端问答耗时；含 1/3/5/10 秒 SLO buckets，可计算 P90 < 10s。 |
| `rag.cache.requests` | 回答缓存命中和未命中次数。 |
| `rag.retrieval.duration` | embedding 与 Redis 向量检索耗时，`outcome` 为 hit/empty/error。 |
| `rag.retrieval.chunks` | 每次检索返回的相关 Chunk 数。 |
| `rag.llm.first-token.duration` | 流式回答的首 Token 延迟（TTFT）。 |
| `rag.llm.duration` | 大模型调用总耗时，按 stream/sync 与 success/error 区分。 |

避免把 `sessionId`、用户问题、文档标题等高基数字段作为 Metrics tag。它们只出现在应用日志中。

可运行 [观测数据演示脚本](observability-demo.md)，快速生成真实 RAG 调用、缓存命中和 Grafana 查询样例。

## 日志与 Trace

日志格式自动附带 `traceId` 与 `spanId`。RAG 检索会创建 `rag.retrieval` span；HTTP 请求 Trace 由 Spring Boot/Micrometer 自动创建。Grafana 中可通过 Trace 查看请求链路，并使用同一 `traceId` 在日志中定位异常。

当前 `LoggerAdvisor` 会输出完整 Prompt/模型响应，便于 demo 排障。真实企业资料上线前必须关闭该 DEBUG 级内容或改为仅记录长度、哈希和脱敏摘要，避免知识库内容进入日志。
