# Design Note — Veri-RAG / 设计说明

> The English section below is the 305-word case-study submission. The Chinese section is a
> faithful companion version for implementation review and interview discussion.

Veri-RAG is a Spring Boot knowledge-base assistant for bilingual enterprise documents. The
application keeps relational records—users, uploaded documents, chat sessions, and messages—in
MySQL, while Redis Search stores embedding vectors and their document metadata. This separation
keeps transactional data durable and queryable without making the vector store responsible for
application state.

At ingestion time, Apache Tika extracts normal PDF and Office-document text. For a PDF with too
little usable text, the service invokes Tesseract with both Chinese and English language packs.
The OCR runtime is packaged in the application image, so the same path works on a Linux server
without a host Tesseract installation. Parsed text is chunked, embedded, and indexed with the
document title, category, and document ID as metadata.

At question time, Redis performs vector retrieval with a similarity threshold. The service
over-samples candidates, removes duplicate passages, and returns a small Top-K set as grounded
context. An optional LLM reranker can improve evidence ordering, but it adds one model call and
is therefore disabled in the default latency-sensitive configuration. The answer prompt requires
the model to abstain when evidence is absent and to answer in the user's language; references are
returned as structured API data rather than model-generated citations.

Conversation history is persisted in MySQL and a bounded recent window is used for follow-up
questions. Prompt-injection checks run before retrieval and reject requests for prompts,
credentials, keys, or internal configuration. The service records structured request, retrieval,
and LLM timing events, and exports Micrometer metrics to Prometheus/Grafana. Evaluation uses
live API cases and separate LLM judges for Accuracy, Context Precision, and Faithfulness.

The principal trade-off is retrieval depth versus latency and context precision. The selected
baseline uses Top-K 2, reranking off, and temperature 0.0: it retains enough evidence for
grounded answers while keeping P90 latency comfortably below the case-study target.

---

## 中文版

Veri-RAG 是一个面向企业内部双语文档的 Spring Boot 知识库助手。MySQL 保存用户、文档、会话和
消息等关系型业务数据；Redis Search 保存向量及文档元数据。这样既保证会话和文档状态可靠持久，
又能使用向量库完成语义检索。

在入库阶段，系统优先通过 Apache Tika 解析普通 PDF 和 Office 文档。若 PDF 的可用文本过少，
则使用同时安装了中文和英文语言包的 Tesseract OCR。OCR 被打包进应用镜像，因此部署到 Linux
服务器时无需额外安装宿主机依赖。解析后的文本会被切分、向量化，并带上标题、分类和文档 ID
等元数据写入索引。

问答阶段先由 Redis 执行相似度检索，服务端对候选进行过采样、去重，并选择少量 Top-K 片段作为
模型上下文。可选的 LLM 重排序可改善证据排序，但会增加一次模型调用，因此默认关闭，适合对延迟和
成本敏感的路径。提示词要求模型仅基于证据回答、没有证据时拒答，并根据用户提问语言输出中文或
英文。引用由服务端结构化返回，而不是让模型自行编造。

会话历史存储在 MySQL 中，只保留有限的最近窗口用于真正的追问。检索前会执行 Prompt Injection
检测，拦截索取提示词、凭据、密钥和内部配置的请求。系统记录结构化日志、检索与模型耗时，并通过
Micrometer、Prometheus 和 Grafana 提供可观测性。评测通过真实 API 用例以及 Accuracy、Context
Precision、Faithfulness 三项 LLM Judge 完成。

主要权衡是检索深度、延迟和上下文精度。推荐默认配置为 Top-K 2、关闭重排序、temperature 0.0：
既保留足够证据，也能在 case study 的 P90 延迟目标内稳定运行。
