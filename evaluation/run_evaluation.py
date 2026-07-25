#!/usr/bin/env python3
"""Run the checked-in RAG gold set against the real /api/chat/ask endpoint.

The script intentionally uses only the Python standard library. It writes raw API output,
an editable human-review file, and a Markdown report. Context Precision is calculated separately
by judge_context_precision.py after these real RAG calls finish.
"""
import argparse
import getpass
import json
import math
import os
import statistics
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def load_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def load_optional_jsonl(path):
    return load_jsonl(path) if Path(path).is_file() else []


def normalized(text):
    return "".join(str(text or "").lower().split())


def contains(text, term):
    return normalized(term) in normalized(text)


def english_response_matches(answer):
    latin_letters = sum(character.isascii() and character.isalpha() for character in answer or "")
    chinese_characters = sum("\u4e00" <= character <= "\u9fff" for character in answer or "")
    return latin_letters >= 3 and latin_letters > chinese_characters * 3


def percentile(values, ratio):
    if not values:
        return None
    values = sorted(values)
    rank = (len(values) - 1) * ratio
    low, high = math.floor(rank), math.ceil(rank)
    return values[low] if low == high else values[low] + (values[high] - values[low]) * (rank - low)


def ask(url, token, question, timeout, session_id=None):
    payload = json.dumps({"question": question}, ensure_ascii=False).encode("utf-8")
    if session_id is not None:
        payload = json.dumps({"question": question, "sessionId": session_id}, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body), round((time.perf_counter() - started) * 1000, 1), None
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        return None, None, round((time.perf_counter() - started) * 1000, 1), str(error)


def login(base_url, username, password, timeout):
    payload = json.dumps({"username": username, "password": password}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base_url.rstrip("/") + "/api/auth/login", data=payload,
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Login failed: {error}") from error
    token = ((body.get("data") or {}).get("token")) if body.get("code") == 200 else None
    if not token:
        raise RuntimeError(f"Login failed: {body.get('message', 'token missing')}")
    return token


def evaluate_case(case, url, token, timeout):
    questions = case.get("turns") or [case["question"]]
    session_id, status, payload, error = None, None, None, None
    latency_ms = 0
    for question in questions:
        status, payload, elapsed, error = ask(url, token, question, timeout, session_id)
        latency_ms += elapsed
        if error or status != 200:
            break
        session_id = ((payload or {}).get("data") or {}).get("sessionId")
    result = (payload or {}).get("data") or {}
    answer = result.get("answer") or ""
    references = result.get("references") or []
    expected_terms = case.get("expected_terms", [])
    evidence_terms = case.get("reference_terms", [])
    term_hit_rate = (sum(contains(answer, term) for term in expected_terms) / len(expected_terms)
                     if expected_terms else None)
    joined_references = "\n".join(f"{ref.get('title', '')}\n{ref.get('content', '')}" for ref in references)
    evidence_found = all(contains(joined_references, term) for term in evidence_terms) if evidence_terms else None
    relevant_refs = sum(any(contains(f"{ref.get('title', '')} {ref.get('content', '')}", term)
                            for term in evidence_terms) for ref in references) if evidence_terms else 0
    precision = relevant_refs / len(references) if references and evidence_terms else None
    refusal_terms = case.get("expected_terms", [])
    refusal_ok = any(contains(answer, term) for term in refusal_terms) and not references if case.get("refusal_required") else None
    expected_language = case.get("language")
    language_match = english_response_matches(answer) if expected_language == "en" else None
    return {
        "id": case["id"], "type": case["type"], "question": questions[-1], "turns": questions,
        "status": status, "error": error, "latency_ms": latency_ms, "answer": answer,
        "references": references, "auto_answer_term_hit_rate": term_hit_rate,
        "auto_expected_evidence_found": evidence_found, "auto_context_precision": precision,
        "auto_refusal_ok": refusal_ok, "source_hints": case.get("source_hints", []),
        "expected_language": expected_language, "auto_language_match": language_match,
    }


def write_jsonl(path, rows):
    Path(path).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def context_precision_at_k(labels):
    """RAGAS-style ranking precision for one ordered list of relevance labels."""
    relevant_count = 0
    weighted_precision = 0.0
    for rank, relevant in enumerate(labels, start=1):
        if relevant:
            relevant_count += 1
            weighted_precision += relevant_count / rank
    return weighted_precision / relevant_count if relevant_count else 0.0


def llm_context_precision(judge_rows):
    valid_rows = [
        row for row in judge_rows
        if row.get("labels") and all(isinstance(label, bool) for label in row["labels"])
    ]
    scores = [context_precision_at_k(row["labels"]) for row in valid_rows]
    chunk_count = sum(len(row["labels"]) for row in valid_rows)
    error_count = sum(bool(row.get("error")) for row in judge_rows)
    return (statistics.mean(scores), len(scores), chunk_count, error_count) if scores else (None, 0, 0, error_count)


def faithfulness_case_score(claims):
    supported = sum(claim["supported"] for claim in claims)
    return supported / len(claims) if claims else None


def llm_faithfulness(judge_rows):
    valid_rows = [
        row for row in judge_rows
        if row.get("claims")
        and all(
            isinstance(claim, dict)
            and isinstance(claim.get("text"), str)
            and isinstance(claim.get("supported"), bool)
            for claim in row["claims"]
        )
    ]
    scores = [faithfulness_case_score(row["claims"]) for row in valid_rows]
    claim_count = sum(len(row["claims"]) for row in valid_rows)
    supported_count = sum(
        claim["supported"] for row in valid_rows for claim in row["claims"]
    )
    error_count = sum(bool(row.get("error")) for row in judge_rows)
    return (
        (statistics.mean(scores), len(scores), claim_count, supported_count, error_count)
        if scores else (None, 0, 0, 0, error_count)
    )


def llm_accuracy(judge_rows):
    valid_rows = [row for row in judge_rows if isinstance(row.get("correct"), bool)]
    correct_count = sum(row["correct"] for row in valid_rows)
    error_count = sum(bool(row.get("error")) for row in judge_rows)
    return (
        (correct_count / len(valid_rows), len(valid_rows), correct_count, error_count)
        if valid_rows else (None, 0, 0, error_count)
    )


def report(rows, output, context_judge_rows, faithfulness_judge_rows, accuracy_judge_rows):
    completed = [row for row in rows if row["status"] == 200 and not row["error"]]
    latencies = [row["latency_ms"] for row in completed]
    refusal_rows = [row for row in completed if row["auto_refusal_ok"] is not None]
    refusal_passes = sum(bool(row["auto_refusal_ok"]) for row in refusal_rows)
    english_rows = [row for row in completed if row.get("expected_language") == "en"]
    english_language_passes = sum(bool(row.get("auto_language_match")) for row in english_rows)
    accuracy, accuracy_case_count, correct_count, accuracy_error_count = llm_accuracy(accuracy_judge_rows)
    judge_precision, judge_case_count, judge_chunk_count, judge_error_count = llm_context_precision(context_judge_rows)
    faithfulness, faithfulness_case_count, claim_count, supported_count, faithfulness_error_count = (
        llm_faithfulness(faithfulness_judge_rows)
    )
    accuracy_display = (
        f"{accuracy:.1%} ({correct_count}/{accuracy_case_count} cases)"
        if accuracy is not None else "Pending"
    )
    faithfulness_display = (
        f"{faithfulness:.3f} ({faithfulness_case_count} cases, {supported_count}/{claim_count} claims supported)"
        if faithfulness is not None else "Pending"
    )
    judge_display = (
        f"{judge_precision:.3f} ({judge_case_count} cases, {judge_chunk_count} chunks)"
        if judge_precision is not None else "Pending"
    )
    judge_note = "Mean ranking Precision@k from LLM relevance labels"
    if judge_error_count:
        judge_note += f"; {judge_error_count} judge error(s) excluded"
    faithfulness_note = "Mean per-case supported-claim ratio from LLM judge"
    if faithfulness_error_count:
        faithfulness_note += f"; {faithfulness_error_count} judge error(s) excluded"
    accuracy_note = "Binary correctness against Gold Set facts and per-case rubric"
    if accuracy_error_count:
        accuracy_note += f"; {accuracy_error_count} judge error(s) excluded"
    lines = [
        "# Veri-RAG Evaluation Report", "",
        f"- Generated: {datetime.now(timezone.utc).astimezone().isoformat(timespec='seconds')}",
        f"- Cases: {len(rows)}; successful API responses: {len(completed)}", "",
        "## Automated checks", "",
        "| Metric | Result | Target | Notes |", "|---|---:|---:|---|",
        f"| API success rate | {len(completed) / len(rows):.1%} | — | HTTP 200 responses |",
        f"| P50 latency | {percentile(latencies, 0.50):.0f} ms | — | End-to-end /api/chat/ask |" if latencies else "| P50 latency | N/A | — | No successful calls |",
        f"| P90 latency | {percentile(latencies, 0.90):.0f} ms | <10,000 ms | End-to-end /api/chat/ask |" if latencies else "| P90 latency | N/A | <10,000 ms | No successful calls |",
        f"| Accuracy | {accuracy_display} | ≥80% | {accuracy_note} |",
        f"| Context Precision | {judge_display} | ≥0.70 | {judge_note} |",
        f"| Faithfulness | {faithfulness_display} | ≥0.85 | {faithfulness_note} |",
        f"| English response-language pass rate | {english_language_passes / len(english_rows):.1%} | 100% | English questions answered primarily in English |" if english_rows else "| English response-language pass rate | N/A | 100% | No English cases completed |",
        f"| Refusal / injection pass rate | {refusal_passes / len(refusal_rows):.1%} | 100% | No reference + refusal wording heuristic |" if refusal_rows else "| Refusal / injection pass rate | N/A | 100% | No refusal cases completed |",
        "", "Accuracy, Context Precision, and Faithfulness are calculated from LLM Judge outputs. Manually audit samples before publishing.",
        "", "## Case results", "",
        "| ID | Type | HTTP | Latency | Language match |", "|---|---|---:|---:|---|",
    ]
    for row in rows:
        language = "—" if row.get("auto_language_match") is None else str(row["auto_language_match"])
        lines.append(f"| {row['id']} | {row['type']} | {row['status'] or 'ERR'} | {row['latency_ms']:.0f} ms | {language} |")
    Path(output).write_text("\n".join(lines) + "\n", encoding="utf-8")


def answer_review_template(rows):
    return [{"id": row["id"], "question": row["question"], "answer": row["answer"],
             "accuracy": None, "review_note": ""} for row in rows]


def reference_review_template(rows):
    return [{
        "id": row["id"], "question": row["question"],
        "reference_labels": [{
            "reference_index": index, "docId": reference.get("docId"), "title": reference.get("title"),
            "content": reference.get("content", ""), "relevant": None, "review_note": ""
        } for index, reference in enumerate(row.get("references", []), start=1)]
    } for row in rows]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8081/veri-rag", help="Application base URL")
    parser.add_argument("--token", help="Existing JWT (optional; skips login)")
    parser.add_argument("--username", default=os.getenv("VERI_RAG_EVAL_USERNAME"),
                        help="Login username; defaults to VERI_RAG_EVAL_USERNAME")
    parser.add_argument("--password", default=os.getenv("VERI_RAG_EVAL_PASSWORD"),
                        help="Login password; defaults to VERI_RAG_EVAL_PASSWORD. Prefer the prompt or env var.")
    parser.add_argument("--gold-set", default="evaluation/gold_set.jsonl")
    parser.add_argument("--output-dir", default="evaluation/output")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--report-only", action="store_true",
                        help="Rebuild report from existing results and completed manual review templates")
    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    if args.report_only:
        results_path = output_dir / "results.jsonl"
        if not results_path.is_file():
            parser.error(f"results file not found: {results_path}")
        rows = load_jsonl(results_path)
        answer_review_path = output_dir / "review_template.jsonl"
        reference_review_path = output_dir / "reference_review_template.jsonl"
        if not answer_review_path.is_file():
            write_jsonl(answer_review_path, answer_review_template(rows))
        if not reference_review_path.is_file():
            write_jsonl(reference_review_path, reference_review_template(rows))
        report(
            rows, output_dir / "report.md",
            load_optional_jsonl(output_dir / "llm_context_precision.jsonl"),
            load_optional_jsonl(output_dir / "llm_faithfulness.jsonl"),
            load_optional_jsonl(output_dir / "llm_accuracy.jsonl"),
        )
        print(f"Refreshed report from {results_path}")
        return
    token = args.token
    if not token:
        if not args.username:
            parser.error("provide --token or --username (or VERI_RAG_EVAL_USERNAME)")
        password = args.password or getpass.getpass("Password for evaluation account: ")
        token = login(args.base_url, args.username, password, args.timeout)
        print(f"Authenticated evaluation account: {args.username}")
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = [evaluate_case(case, args.base_url.rstrip("/") + "/api/chat/ask", token, args.timeout)
            for case in load_jsonl(args.gold_set)]
    write_jsonl(output_dir / "results.jsonl", rows)
    write_jsonl(output_dir / "review_template.jsonl", answer_review_template(rows))
    write_jsonl(output_dir / "reference_review_template.jsonl", reference_review_template(rows))
    write_jsonl(output_dir / "llm_context_precision.jsonl", [])
    write_jsonl(output_dir / "llm_faithfulness.jsonl", [])
    write_jsonl(output_dir / "llm_accuracy.jsonl", [])
    report(rows, output_dir / "report.md", [], [], [])
    print(f"Wrote {len(rows)} cases to {output_dir}")


if __name__ == "__main__":
    main()
