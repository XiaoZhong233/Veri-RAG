# Veri-RAG / 双语企业知识库问答

[English](#english) | [中文](#中文)

## 中文

Veri-RAG 是一个基于 Spring Boot 的企业内部知识库问答服务。它支持中文和英文文档、扫描 PDF OCR、多轮对话、基于检索证据的引用回答，以及基础的监控、安全和离线评测能力。

### 功能概览

- **RAG 问答与引用**：回答基于 Redis 向量检索结果生成，并返回文档标题、文档 ID、分类和证据片段。
- **双语与 OCR**：支持 CN/EN 文档；PDF 文本层过少时自动使用 Tesseract（`chi_sim+eng`）OCR。
- **多轮会话**：会话与消息持久化在 MySQL；较早消息可压缩为摘要，保留最近对话上下文。
- **检索质量控制**：相似度阈值、Top-K、候选过采样（`3 × Top-K`）、精确文本去重，以及可选 LLM Reranker。
- **安全与可观测性**：JWT 鉴权、Prompt Injection 拦截、无上下文拒答、结构化 RAG 日志、OpenTelemetry Trace/Metrics、Prometheus 与 Grafana。
- **评测**：47 个真实 API 用例，覆盖普通问答、多轮、OCR、英文、拒答和注入攻击；包含 Accuracy、Context Precision、Faithfulness 和 P90 延迟。

### 架构

```text
Browser / REST client
        │ JWT
        ▼
Spring Boot API ──► MySQL (users, documents, sessions, messages)
        │
        ├──► Redis 8 (vector store, retrieval, optional answer cache)
        ├──► DashScope-compatible Qwen (chat, embedding, optional reranker)
        ├──► Apache Tika + Tesseract (scanned PDF ingestion)
        └──► OpenTelemetry ──► Grafana OTEL LGTM
```

### 前置条件

- JDK 21
- Docker Desktop / Docker Compose v2
- Python 3（运行评测脚本）
- DashScope 兼容 API Key
- 本机运行 OCR 时：Tesseract 和 `chi_sim`、`eng` 语言包

macOS：

```bash
brew install tesseract tesseract-lang
tesseract --list-langs
```

Ubuntu / Debian：

```bash
sudo apt-get install tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng
tesseract --list-langs
```

### 快速启动

1. 创建本地配置并填写 API Key：

```bash
cp .env.example .env
# 在 .env 中设置 DASHSCOPE_API_KEY；本地开发也应替换 JWT_SECRET。
```

2. 构建并启动完整服务（应用、MySQL、Redis、Grafana/OTel）：

```bash
docker compose up -d --build
docker compose ps
```

3. 查看应用启动日志与健康状态：

```bash
docker compose logs -f app
curl http://127.0.0.1:8080/veri-rag/actuator/health
```

应用容器默认绑定宿主机 `127.0.0.1:8080`，网页入口为：

```text
http://localhost:8080/veri-rag/
```

> 云服务器请保留 `APP_BIND_ADDRESS=127.0.0.1`，并通过 Nginx/Caddy 将 HTTPS 流量反代到应用。临时直接演示时，可设为 `0.0.0.0`，并在防火墙中限制 8080 的来源。

本地初始化账号仅用于演示：`admin / 123456`。请勿在生产环境使用该账号、默认数据库密码或默认 JWT Secret。

### 使用流程

1. 使用管理员账号登录。
2. 在网页的文档管理页上传 PDF、DOCX、TXT 或 Markdown 文件，并选择分类。
3. 等待文档状态变为 `SUCCESS`；扫描 PDF 会在需要时进入 OCR。
4. 在聊天页提问，回答末尾会返回引用证据。
5. 追问时复用返回的 `sessionId`，保持同一个会话上下文。

#### REST 示例

登录并保存 Token：

```bash
API_BASE='http://localhost:8081/veri-rag'

curl -sS "${API_BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}'
```

发起问答（将 `${TOKEN}` 替换为登录响应中的 token）：

```bash
curl -sS "${API_BASE}/api/chat/ask" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"question":"What does the knowledge base say about employee leave?","categoryIds":[]}'
```

上传文档（仅管理员）：

```bash
curl -sS -X POST "${API_BASE}/api/documents?categoryId=1" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F 'file=@/absolute/path/to/document.pdf' \
  -F 'title=Employee Handbook'
```

常用接口：

| Endpoint | 说明 |
| --- | --- |
| `POST /api/auth/login` | 登录并获取 JWT |
| `POST /api/chat/ask` | 同步 RAG 问答 |
| `POST /api/chat/ask/stream` | SSE 流式 RAG 问答 |
| `GET /api/chat/sessions` | 当前用户会话列表 |
| `GET /api/chat/sessions/{sessionId}/messages` | 会话历史 |
| `POST /api/documents` | 上传、解析并向量化文档（管理员） |
| `GET /api/documents/page` | 文档列表（管理员） |
| `POST /api/documents/{id}/reingest` | 重建单个文档向量（管理员） |
| `GET /actuator/health` | 健康检查 |
| `GET /actuator/prometheus` | Prometheus 指标 |

### 关键配置

配置均可通过 `.env` 或环境变量覆盖；完整清单见 [`.env.example`](.env.example)。

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `LLM_CHAT_MODEL` | `qwen-flash` | 生成模型 |
| `LLM_TEMPERATURE` | `0.2` | 当前运行默认值 |
| `EMBEDDING_MODEL` | `qwen3.7-text-embedding` | 向量模型 |
| `RAG_RETRIEVAL_TOP_K` | `2` | 最终给模型的引用块数 |
| `RAG_SIMILARITY_THRESHOLD` | `0.75` | 低相关结果拒答阈值 |
| `RAG_RERANKER_ENABLED` | `false` | 启用额外 LLM 重排序 |
| `RAG_OCR_ENABLED` | `true` | 允许扫描 PDF OCR |
| `RAG_OCR_LANGUAGE` | `chi_sim+eng` | OCR 语言 |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `50MB` | 单文件上传限制 |

消融实验中，`Top-K=2 / Reranker=OFF / Temperature=0.0` 是推荐的平衡配置；代码默认仍是 `Temperature=0.2`。如需采用推荐配置，请在 `.env` 中显式设置 `LLM_TEMPERATURE=0.0`。完整数据见 [消融实验报告](docs/ablation-study.md)。

### OCR 行为

应用会优先解析 PDF 的文本层。当可见字符平均值低于 `RAG_OCR_MIN_CHARS_PER_PAGE`（默认 40）时，自动触发 Tesseract OCR。默认限制为 50 页、300 DPI、每页 120 秒；扫描质量差或未安装语言包时会明确报错，而不会把空文档标记为成功。

### 评测

确保应用、MySQL、Redis 都已运行后执行：

```bash
python3 evaluation/run_full_evaluation.py \
  --username admin \
  --base-url http://localhost:8081/veri-rag
```

脚本会登录获取 JWT、运行 47 个真实 API 用例，随后运行 Accuracy、Context Precision 和 Faithfulness LLM Judge，并输出 `evaluation/output/report.md`。详细方法、输出和单项 Judge 命令见 [evaluation/README.md](evaluation/README.md)。

已提交的结果与阈值说明见：[Evaluation Summary](docs/Veri-RAG_Evaluation_Summary.docx) 和 [Ablation Study](docs/ablation-study.md)。

### 设计、实验与成本摘要

#### 设计说明

系统将 MySQL 用于用户、文档与会话等事务数据，将 Redis Search 用于向量和检索元数据；上传阶段由
Tika 解析，扫描 PDF 按阈值触发 Tesseract OCR。问答阶段采用候选过采样、去重、Top-K 证据约束和可选
LLM 重排序；回答语言跟随用户语言，引用由服务端结构化返回。完整的中英文设计说明见
[Design Note](docs/design-note.md)。

#### 消融实验与推荐配置

在 47 个真实 API 用例上比较 Top-K、重排序和 Temperature（所有配置均为 47/47 HTTP 成功、P90 小于
10 秒）：

| 配置 | P90 | Accuracy | Context Precision | Faithfulness | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| Top-K 2，Reranker 关闭，T=0.2 | 1,479 ms | 90.2% | 0.927 | 0.984 | 基线 |
| Top-K 1，Reranker 关闭，T=0.2 | 1,726 ms | 85.4% | 0.875 | 0.894 | 证据不足 |
| Top-K 4，Reranker 关闭，T=0.2 | 1,610 ms | 92.7% | 0.913 | 0.988 | 召回更高、上下文更长 |
| Top-K 2，Reranker 开启，T=0.2 | 3,860 ms | 90.2% | 0.963 | 0.978 | 精度最高但成本/延迟更高 |
| Top-K 2，Reranker 关闭，T=0.0 | 1,483 ms | 90.2% | 0.927 | 1.000 | 推荐平衡配置 |

推荐 `Top-K=2`、关闭 Reranker、`LLM_TEMPERATURE=0.0`；若更重视召回可用 Top-K 4，若更重视证据
排序精度可按需开启 Reranker。完整实验设计、全部六组数据与局限性见 [Ablation Study](docs/ablation-study.md)。

#### 在线模型成本估算

按 Qwen Flash 输入 ¥0.00015 / 1K tokens、输出 ¥0.0015 / 1K tokens，及 embedding ¥0.0005 /
1K tokens 估算，默认配置（Top-K 2、关闭 Reranker）每次在线问答约 **¥0.000645**，即每 1,000 次
约 **¥0.645**。可选 LLM Reranker 约额外增加 **¥1.125 / 1,000 次**。这是基于 token 假设的工程估算，
不含一次性文档入库 embedding 和离线 Judge 成本；详见双语版 [Cost Estimate](docs/cost-estimate.md)。

### 监控、日志与安全

- Grafana：`http://localhost:3000`；演示脚本：`bash scripts/observe-rag-demo.sh`。
- 应用日志只记录事件、结果、耗时和计数；发布样例会去除 Trace/Span、用户、会话、提示词和文档内容。
- `PromptInjectionGuard` 会拦截常见的提示词覆盖和敏感信息索取请求。
- 检索无结果、相似度不足或没有可靠上下文时，服务会给出能力边界说明并抑制无关引用。
- 部署时必须通过环境变量提供 `DASHSCOPE_API_KEY`、`JWT_SECRET`、数据库与 Redis 密码；不要提交 `.env`。

更多观测信息见 [docs/observability.md](docs/observability.md) 与 [docs/observability-demo.md](docs/observability-demo.md)。

Grafana 的 `LLM指标` dashboard 基于 Prometheus 指标展示端到端、LLM 和向量检索的 P50/P90；
`observability` 服务使用命名卷持久化 Grafana 与监控数据。指标定义、日志/Trace 注意事项和演示查询见
[Observability Guide](docs/observability.md) 及 [Observability Demo](docs/observability-demo.md)。

### Linux / Docker 部署

`docker compose up -d --build` 会构建并启动应用容器。`Dockerfile` 使用 Java 21 多阶段构建，并在运行时镜像中安装 Tesseract、中文和英文语言包。

```bash
docker build -t veri-rag:latest .
docker run --rm --entrypoint tesseract veri-rag:latest --list-langs
```

生产环境应使用独立的 `.env.production`，并通过 `docker compose --env-file .env.production up -d --build` 启动。上传文件、MySQL 与 Redis 使用 named volume 持久化；MySQL、Redis、OTel 默认不暴露到公网。完整示例见 [docs/study-case-environment.md](docs/study-case-environment.md)。

---

## English

Veri-RAG is a Spring Boot knowledge-base QA service for internal enterprise documents. It supports Chinese and English content, OCR for scanned PDFs, multi-turn conversations, citation-backed grounded answers, observability, security controls, and offline evaluation.

### Features

- **Grounded RAG QA** — answers are generated from Redis vector retrieval and return document citations and evidence snippets.
- **Bilingual OCR** — CN/EN content is supported; PDFs with an insufficient text layer automatically fall back to Tesseract OCR (`chi_sim+eng`).
- **Conversation continuity** — MySQL persists sessions and messages; older context can be summarized while recent turns remain available to the model.
- **Retrieval controls** — similarity threshold, final Top-K, `3 × Top-K` candidate oversampling, exact-text deduplication, and an optional LLM reranker.
- **Security and observability** — JWT authentication, prompt-injection blocking, no-context refusal, structured RAG logs, OpenTelemetry traces/metrics, Prometheus, and Grafana.
- **Evaluation** — 47 live API cases cover normal QA, multi-turn, OCR, English, abstention, and injection attempts; Accuracy, Context Precision, Faithfulness, and P90 latency are measured.

### Quick start

Requirements: JDK 21, Docker Compose v2, Python 3 for evaluation, a DashScope-compatible API key, and Tesseract with Chinese/English language packs when OCR runs on the host.

```bash
cp .env.example .env
# Set DASHSCOPE_API_KEY and replace JWT_SECRET in .env.

docker compose up -d --build
```

Open `http://localhost:8080/veri-rag/`. Compose builds and starts the Spring Boot application, MySQL, Redis, and Grafana/OTel.

The seeded `admin / 123456` account is for local demonstration only. Never use it, default database passwords, or a default JWT secret in production.

### API quick reference

Base URL: `http://localhost:8081/veri-rag`

| Endpoint | Purpose |
| --- | --- |
| `POST /api/auth/login` | Obtain a JWT |
| `POST /api/chat/ask` | Synchronous RAG QA |
| `POST /api/chat/ask/stream` | Streaming SSE RAG QA |
| `GET /api/chat/sessions` | List the current user's sessions |
| `POST /api/documents` | Upload, parse, and vectorize a document (admin) |
| `GET /actuator/health` | Health check |
| `GET /actuator/prometheus` | Prometheus metrics |

Use `sessionId` from a previous chat response for a follow-up turn. Admin uploads require a multipart `file`, `categoryId`, and optional `title`.

### Configuration and tuning

All values can be overridden through `.env`; see [`.env.example`](.env.example) for the full list. Current runtime defaults are `qwen-flash`, `qwen3.7-text-embedding`, `Top-K=2`, similarity threshold `0.75`, reranker off, and temperature `0.2`.

The ablation study recommends `Top-K=2`, reranker off, and `temperature=0.0` as a balanced configuration. Apply that recommendation explicitly with `LLM_TEMPERATURE=0.0`; the source default intentionally remains `0.2`. See [the ablation study](docs/ablation-study.md) for the data and trade-offs.

### OCR, evaluation, and deployment

OCR runs only when a PDF's average visible text falls below `RAG_OCR_MIN_CHARS_PER_PAGE` (default 40). It is bounded by page count, DPI, and timeout settings, and fails clearly when Tesseract or a required language pack is unavailable.

Run the live evaluation after the application and dependencies are running:

```bash
python3 evaluation/run_full_evaluation.py \
  --username admin \
  --base-url http://localhost:8081/veri-rag
```

It authenticates, sends the 47 cases, then runs the configured LLM Judges. See [evaluation/README.md](evaluation/README.md) for commands and scoring details.

### Design, experiments, cost, and observability

#### Design note

MySQL stores transactional state (users, documents, and conversations), while Redis Search stores
vectors and retrieval metadata. Tika handles normal parsing and a threshold triggers Tesseract OCR
for scanned PDFs. At answer time, candidate oversampling, deduplication, Top-K evidence grounding,
and an optional LLM reranker control retrieval quality. The answer language follows the user's
question and citations are returned as structured API data. Read the bilingual [Design Note](docs/design-note.md)
for the full rationale.

#### Ablation result and selected configuration

The 47 live API cases compared Top-K, reranking, and temperature; every configuration returned
47/47 HTTP successes and stayed below the 10-second P90 target.

| Configuration | P90 | Accuracy | Context Precision | Faithfulness | Outcome |
| --- | ---: | ---: | ---: | ---: | --- |
| Top-K 2, reranker off, T=0.2 | 1,479 ms | 90.2% | 0.927 | 0.984 | Baseline |
| Top-K 1, reranker off, T=0.2 | 1,726 ms | 85.4% | 0.875 | 0.894 | Too little evidence |
| Top-K 4, reranker off, T=0.2 | 1,610 ms | 92.7% | 0.913 | 0.988 | Higher recall, longer context |
| Top-K 2, reranker on, T=0.2 | 3,860 ms | 90.2% | 0.963 | 0.978 | Highest precision, added latency/cost |
| Top-K 2, reranker off, T=0.0 | 1,483 ms | 90.2% | 0.927 | 1.000 | Recommended balance |

The selected configuration is `Top-K=2`, reranker off, and `LLM_TEMPERATURE=0.0`. Use Top-K 4
when recall takes priority, or enable reranking selectively when evidence-order precision matters.
See the [Ablation Study](docs/ablation-study.md) for all six runs, method, and limitations.

#### Online cost estimate

Using the supplied Qwen Flash prices (¥0.00015 input / 1K tokens, ¥0.0015 output / 1K tokens)
and ¥0.0005 / 1K embedding tokens, the default online path is estimated at **¥0.000645 per
question**, or **¥0.645 per 1,000 questions**. The optional LLM reranker adds approximately
**¥1.125 per 1,000 questions**. This is a token-assumption engineering estimate: it excludes
one-time document ingestion embeddings and offline LLM Judge calls. The bilingual assumptions and
formula are in [Cost Estimate](docs/cost-estimate.md).

#### Observability

The `LLM指标` Grafana dashboard derives end-to-end, LLM, and vector-retrieval P50/P90 from
Prometheus histograms. The `observability` service uses a named volume to retain Grafana and
monitoring data across container recreation. Metric definitions, trace/logging guidance, and demo
queries are available in the [Observability Guide](docs/observability.md) and
[Observability Demo](docs/observability-demo.md).

For Linux deployment, build the included Java 21 image; it contains Tesseract plus `chi_sim` and `eng` packs:

```bash
docker build -t veri-rag:latest .
docker run --rm --entrypoint tesseract veri-rag:latest --list-langs
```

Use injected secrets, persistent uploaded-file storage, and the shared MySQL/Redis/observability network in production. See [the environment guide](docs/study-case-environment.md) for the full deployment example.

### Project evidence

- [Evaluation Summary](docs/Veri-RAG_Evaluation_Summary.docx)
- [Design Note](docs/design-note.md)
- [Online Model Cost Estimate](docs/cost-estimate.md)
- [Ablation Study](docs/ablation-study.md)
- [Observability guide](docs/observability.md)
- [Local environment and OCR guide](docs/study-case-environment.md)
