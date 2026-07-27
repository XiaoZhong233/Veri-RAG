# Online Model Cost Estimate / 在线模型成本估算

> The English section is the delivery version; the Chinese section below uses the same prices and
> assumptions for implementation review.

This estimate uses the supplied Qwen list prices: Qwen Flash input is **¥0.00015 / 1K tokens**,
output is **¥0.0015 / 1K tokens**, and the embedding model is **¥0.50 / 1M tokens**
(¥0.0005 / 1K tokens). It is an engineering estimate, not a provider billing record; actual
usage depends on prompt length, output length, cache behaviour, and the selected model tier.

## Baseline: Top-K 2, reranker off

| Assumption per online question | Token estimate | Cost per request | Cost per 1,000 requests |
| --- | ---: | ---: | ---: |
| Generation input: system prompt, question, and two passages | 1,200 | ¥0.000180 | ¥0.180 |
| Generation output | 300 | ¥0.000450 | ¥0.450 |
| Query embedding | 30 | ¥0.000015 | ¥0.015 |
| **Total** | — | **¥0.000645** | **¥0.645** |

Formula:

```text
cost / 1,000 calls = 0.00015 × input_tokens
                  + 0.0015 × output_tokens
                  + 0.0005 × embedding_tokens
```

The coefficients are expressed in CNY per 1K tokens. Document embeddings are a one-time
ingestion cost and are deliberately excluded from the recurring online-question estimate.

## Optional LLM reranker

The reranker evaluates up to six candidates, each truncated to 3,000 characters. With a planning
assumption of 6,000 reranker input tokens and 150 output tokens, it adds approximately
**¥0.001125 per request**, or **¥1.125 per 1,000 requests**. This is why the default configuration
keeps reranking off and enables it only for a precision-sensitive tier.

## Scope

Offline LLM evaluation judges are excluded from online serving cost. Provider cache-hit and batch
prices are also excluded because the application does not currently emit provider token-usage
telemetry or use explicit prompt caching. Add DashScope usage telemetry before treating this
estimate as an invoice-grade cost report.

---

## 中文版

本估算使用已提供的 Qwen 价格：Qwen Flash 输入为 **¥0.00015 / 1K tokens**，输出为
**¥0.0015 / 1K tokens**；embedding 模型为 **¥0.50 / 1M tokens**（即 ¥0.0005 / 1K
tokens）。这是工程估算，不是供应商账单；实际成本会随 prompt 长度、回答长度、缓存命中率和模型
规格变化。

### 默认配置：Top-K 2，关闭 reranker

| 每次在线问答假设 | Token 估算 | 单次成本 | 每 1,000 次成本 |
| --- | ---: | ---: | ---: |
| 生成输入：系统提示词、问题和两个片段 | 1,200 | ¥0.000180 | ¥0.180 |
| 生成输出 | 300 | ¥0.000450 | ¥0.450 |
| 查询 embedding | 30 | ¥0.000015 | ¥0.015 |
| **合计** | — | **¥0.000645** | **¥0.645** |

计算公式：

```text
每 1,000 次调用成本 = 0.00015 × 输入 token 数
                   + 0.0015 × 输出 token 数
                   + 0.0005 × embedding token 数
```

公式中的系数单位均为每 1K tokens 的人民币价格。文档入库 embedding 属于一次性成本，因此不计入
在线问答的经常性成本。

### 可选 LLM 重排序

重排序最多评估 6 个候选，每个候选最多截取 3,000 个字符。若按 6,000 输入 tokens 与 150 输出
tokens 估算，重排序约增加 **¥0.001125 / 次**，即 **¥1.125 / 1,000 次**。因此默认关闭，仅在
对引用精度要求更高的场景启用。

### 范围与限制

离线 LLM Judge 评测不计入线上服务成本。当前应用未上报供应商 token 使用量，也未使用显式 prompt
缓存，因此不把缓存命中价和 Batch 价计入本表。若要形成可对账的成本报告，应补充 DashScope token
usage telemetry。
