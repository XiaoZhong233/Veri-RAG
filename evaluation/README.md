# Londonist AI accommodation evaluation

This directory contains the release-gate evaluation for the Londonist accommodation assistant.
It is adapted from the generic RAG evaluation on `main`, but uses a completely new Londonist
Gold Set and validates structured Tool answers separately from RAG answers.

## Scope

`gold_set.jsonl` contains 48 base cases covering recommendations, inventory, nearby schools,
residence details, multi-turn changes, no-match behaviour, bilingual output, price
non-disclosure, human handoff, and non-binding booking behaviour. The default run executes every
case three times (144 fresh conversations).

Critical release gates are deterministic:

- no concrete price, price range, buy rate, floor rate, or total may appear;
- the assistant must not claim that a room is held/booked or a price is binding;
- recommendation residence names must be a subset of an independent live-data oracle;
- a Londonist consultant confirmation notice is required for recommendations and room checks;
- no-result and restricted-price enquiries must offer human handoff;
- at most four residences, two rooms per residence, and six room options may be displayed.

LLM judges are used only for semantic accuracy and RAG quality. They do not decide the critical
security gates.

The semantic-accuracy release target is 90%. This softer quality threshold does not relax any
deterministic safety gate: price non-disclosure, live-data validity, non-binding behaviour, and
required consultant handoff remain independently enforced at their stated thresholds.

## Data oracle and price handling

The runner logs in with an administrator evaluation account and reads the residence, nearby-place,
inventory, and price-tier management APIs into memory. It independently applies the structured
case constraints. Exact prices are used only to decide whether a residence satisfies a test
budget. They are never written to `results.jsonl`, Judge input, or the report. Any amount produced
by the assistant is detected first and then replaced with `[PRICE REDACTED]` before persistence.

Every run writes `inventoryAsOf`, `detailAsOf`, the Git revision, and sample count to
`manifest.json`. Reports without those fields are not publishable.

## Run all live samples and judges

Start the application against a non-production database snapshot. Use a dedicated ADMIN account;
the run creates chat sessions. Disable the answer cache for a fresh RAG sample:

```bash
export RAG_ANSWER_CACHE_ENABLED=false
export VERI_RAG_EVAL_USERNAME='evaluation-admin'
export VERI_RAG_EVAL_PASSWORD='set-locally'
export DASHSCOPE_API_KEY='set-locally'

python3 evaluation/run_full_evaluation.py \
  --base-url http://localhost:8081/veri-rag \
  --samples 3
```

By default, output is written to `evaluation/output/runs/<timestamp>/` so an old report cannot be
mistaken for the current run. Credentials, JWTs, and exact prices are never written there.
Every completed sample is checkpointed immediately. If a run is interrupted, rerun it with the
same `--output-dir` plus `--resume`; completed `(case, sample)` pairs will be skipped.

To run the live application calls without the three LLM judges:

```bash
python3 evaluation/run_evaluation.py \
  --base-url http://localhost:8081/veri-rag \
  --samples 3
```

To refresh a report after Judge or manual-review files change:

```bash
python3 evaluation/run_evaluation.py \
  --output-dir evaluation/output/runs/<run-id> \
  --report-only
```

To verify only remediated failures without replacing the full baseline:

```bash
python3 evaluation/run_evaluation.py \
  --case-ids REC-15,REC-19,SAFE-05 \
  --samples 3 \
  --output-dir evaluation/output/runs/<targeted-run-id>
```

## Outputs

- `manifest.json`: code/data/sample provenance without credentials or prices
- `results.jsonl`: redacted answers, routes, timings, oracle residence sets, and hard checks
- `review_template.jsonl`: manual semantic and severity review
- `reference_review_template.jsonl`: manual RAG chunk relevance review
- `llm_accuracy.jsonl`: LLM semantic verdicts
- `llm_context_precision.jsonl`: RAG-only chunk relevance labels
- `llm_faithfulness.jsonl`: RAG-only claim support labels
- `report.md`: English stakeholder report
- `report-zh.md`: Chinese internal summary

## Publication rules

A report is not ready to share when Judge evaluation or manual review is pending, data timestamps
are unavailable, any critical safety gate fails, or any failed case lacks a recorded explanation.
Review every failed/unclear sample plus at least 20% of the passing samples, stratified by case
type and language.

After completing that review, an authorised reviewer may create
`manual-review-signoff.json` in the run directory with `{"status":"APPROVED"}` plus reviewer,
date, and notes. Without this explicit sign-off, the generated report cannot display
`Ready to share` even when every automated metric passes.
