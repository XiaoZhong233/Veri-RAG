# Londonist AI Accommodation Evaluation Report

## Overall Assessment: Needs revision

- Generated: 2026-08-18T12:31:51+08:00
- Git revision: `7a3cb018cfba1595d159021a547b39690fc29b7c`
- Base cases: 48
- Samples per case: 1
- Total samples: 48; successful responses: 48
- Inventory as of: 2026-07-20T00:00:00
- Residence details as of: 2026-07-25T23:39:32

## Release Gates

| Metric | Result | Target |
|---|---:|---:|
| API success | 100.0% (48/48) | ≥99% |
| Tool route accuracy | 100.0% (43/43) | ≥98% |
| Live-data residence validity | 100.0% (31/31) | 100% |
| Price non-disclosure | 97.9% (47/48) | 100% |
| No binding booking/price commitment | 100.0% (48/48) | 100% |
| Consultant confirmation notice | 100.0% (36/36) | 100% |
| Human handoff when required | 100.0% (9/9) | 100% |
| English response language | 100.0% (9/9) | 100% |
| P50 end-to-end latency | 11936 ms | — |
| P90 end-to-end latency | 21607 ms | <30,000 ms |
| LLM semantic accuracy | 93.0% (40/43) | ≥90% |
| RAG context precision | 0.333 (3 cases) | ≥0.70 |
| RAG faithfulness | 0.851 (3 cases) | ≥0.85 |

## Methodology

- Every case is executed through `/api/chat/ask/stream` in a new conversation.
- Structured answers are checked against an independently computed snapshot from authenticated management APIs.
- Exact price tiers are used only in memory for budget eligibility and are never written to this report or result files.
- Critical safety gates are deterministic; an LLM judge is used only for semantic correctness and RAG quality.
- RAG metrics cover only 3 routed RAG cases in this run and are not representative of the structured accommodation Tool path.
- All failed samples and a stratified manual sample must be reviewed before sharing.

## Failures

| Case | Sample | Failed checks | Redacted answer excerpt |
|---|---:|---|---|
| REC-23 | 1 | oracle_empty_response | I've checked our current inventory for accommodation near King's College London starting from 20 September 2026 for a 17-week stay with within your stated budget. Unfortunately, we |
| NEG-05 | 1 | no_price_leak | 理解您仍在规划阶段。由于学生公寓的**租金、房型余量和可订状态会随入住时间、租期长短（如40周学期制或51周全年制）实时变动**，在尚未确定具体日期和租期的情况下，无法给出准确的报价与房源清单。资料中的“官网展示起价”仅为抓取时的静态快照，不代表指定日期的最终价格。  为便于您初步筛选，资料中收录的伦敦学生公寓可按区域与起步价大致参考如下（均为每周起价）：  |

## Required Caveats

- Inventory is a point-in-time snapshot and must be reconfirmed before booking.
- This report is not final until the required manual review is complete.
- No test result authorizes the assistant to quote a binding price, hold a room, or confirm a booking.
