# Veri-RAG Ablation and Sensitivity Study

Evaluation date: 25 July 2026  
Dataset: 47 live API cases (41 answer-bearing; 3 abstention; 3 prompt-injection)  
Model: `qwen-flash`  
Embedding model: `qwen3.7-text-embedding`  
Similarity threshold: `0.75`  
Answer cache: disabled

## Results

| Experiment | Top-K | Reranker | Temperature | P50 | P90 | Accuracy | Context Precision | Faithfulness | Returned chunks |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| E0 baseline | 2 | Off | 0.2 | 779 ms | 1,479 ms | 90.2% | 0.927 | 0.984 | 75 |
| E1 narrow retrieval | 1 | Off | 0.2 | 785 ms | 1,726 ms | 85.4% | 0.875 | 0.894 | 40 |
| E2 broad retrieval | 4 | Off | 0.2 | 813 ms | 1,610 ms | 92.7% | 0.913 | 0.988 | 123 |
| E3 LLM reranker | 2 | On | 0.2 | 1,608 ms | 3,860 ms | 90.2% | 0.963 | 0.978 | 75 |
| E4 deterministic generation | 2 | Off | 0.0 | 827 ms | 1,483 ms | 90.2% | 0.927 | 1.000 | 75 |
| E5 higher temperature | 2 | Off | 0.7 | 804 ms | 1,712 ms | 90.2% | 0.927 | 0.982 | 75 |

All six experiments returned HTTP 200 for 47/47 cases, achieved 100% English
response-language compliance and 100% refusal/prompt-injection compliance, and remained below
the 10-second P90 requirement. Accuracy, Context Precision, and Faithfulness were calculated by
the configured LLM Judges with no Judge errors.

## Findings

### Retrieval depth

`Top-K=1` reduced the available evidence too aggressively. Accuracy fell by 4.8 percentage
points and Faithfulness fell by 0.090 versus the baseline. Multi-turn case `MT-03` returned no
reference and could not be scored for Context Precision. The smaller prompt did not produce a
latency benefit in this single run.

`Top-K=4` produced the highest Accuracy (92.7%) and slightly higher Faithfulness (0.988), while
Context Precision decreased from 0.927 to 0.913 because more secondary chunks were returned.
P90 increased by 131 ms and the number of returned chunks increased from 75 to 123, implying a
larger generation prompt and higher token cost.

### Reranker

The LLM reranker improved Context Precision from 0.927 to 0.963, the best retrieval-ranking
result in the study. Accuracy remained unchanged and Faithfulness moved from 0.984 to 0.978.
The extra model call increased P50 from 779 ms to 1,608 ms and P90 from 1,479 ms to 3,860 ms.
The reranker is therefore valuable when retrieval precision is the priority, but is not the
best default for a latency- or cost-sensitive path.

### Temperature

Changing temperature did not affect retrieval metrics, as expected. `temperature=0.0` preserved
Accuracy and achieved the highest observed Faithfulness (1.000) with nearly unchanged P90.
`temperature=0.7` also preserved Accuracy but generated more atomic claims and reduced
Faithfulness to 0.982. The evidence favors `temperature=0.0` for concise enterprise QA, subject
to confirmation with repeated runs.

## Recommendation

Use `Top-K=2`, reranker off, and `temperature=0.0` as the balanced default. It meets every
quality threshold with low latency and the strongest observed Faithfulness. Use `Top-K=4` when
answer recall and Accuracy are more important than prompt size. Enable the LLM reranker
selectively for difficult queries or a higher-precision tier rather than for every request.

## Limitation and cost evidence

Each configuration was run once, so small latency and Judge-score differences may contain
sampling noise. A repeated run is recommended before treating sub-percentage changes as
significant. The application and evaluation result currently do not expose provider token usage;
therefore provider token usage is not yet measured directly. The supplied-price estimate is
documented in [Online Model Cost Estimate](cost-estimate.md); runtime cost includes query
embedding, generation, and the optional reranker call, while excluding offline LLM Judge calls.
