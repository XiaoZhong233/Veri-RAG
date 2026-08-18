#!/usr/bin/env python3
"""Run repeatable Londonist chat evaluations through the SSE endpoint."""
import argparse
import getpass
import json
import math
import os
import re
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from oracle import capture_live_snapshot, eligible_residences


SEMANTIC_ACCURACY_TARGET = 0.90
PRICE_PATTERN = re.compile(
    r"(?:£|\bGBP\s*)\s*\d[\d,]*(?:\.\d+)?(?:\s*[-–—~至到]\s*(?:£|GBP\s*)?\s*\d[\d,]*(?:\.\d+)?)?"
    r"|\d[\d,]*(?:\.\d+)?\s*(?:英镑|镑|pounds?)",
    re.IGNORECASE,
)
COMMITMENT_PATTERN = re.compile(
    r"已(?:经)?(?:为您)?(?:预订(?!满|完|额)|锁定|保留)|预订成功|锁房成功|价格(?:已经)?(?:锁定|保证|有效)|"
    r"booking\s+(?:is\s+)?confirmed|room\s+(?:is\s+)?held|binding\s+price|price\s+guaranteed",
    re.IGNORECASE,
)
CONSULTANT_PATTERN = re.compile(
    r"Londonist\s*顾问.*(?:确认|核实)|(?:确认|核实).*Londonist\s*顾问|"
    r"confirmed\s+by\s+(?:a\s+)?Londonist\s+consultant",
    re.IGNORECASE,
)
HANDOFF_PATTERN = re.compile(r"顾问|人工|consultant|human\s+agent|team", re.IGNORECASE)
NO_MATCH_PATTERN = re.compile(
    r"没有找到|暂无|暂时没有|未找到|没有符合|no\s+(?:matching|suitable|available)|"
    r"no\s+[^.\n]{0,50}\s+matching|"
    r"no\s+[^.\n]{0,80}\s+(?:available|matching|suitable)|"
    r"could(?:n't| not)\s+find|unable\s+to\s+find|"
    r"(?:was|were|am|is|are|have|has|had)(?:n't|\s+not)\s+(?:been\s+)?able\s+to\s+find|"
    r"(?:haven't|hasn't|hadn't|have\s+not|has\s+not|had\s+not)\s+found|"
    r"(?:don't|doesn't|do\s+not|does\s+not)\s+(?:currently\s+)?have\s+any\s+"
    r"(?:listings|rooms|properties|accommodations?)",
    re.IGNORECASE,
)


def load_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()
            if line.strip()]


def load_optional_jsonl(path):
    return load_jsonl(path) if Path(path).is_file() else []


def write_jsonl(path, rows):
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def normalized(text):
    return "".join(str(text or "").lower().split())


def contains(text, term):
    return normalized(term) in normalized(text)


def english_response_matches(answer):
    latin = sum(character.isascii() and character.isalpha() for character in answer or "")
    chinese = sum("\u4e00" <= character <= "\u9fff" for character in answer or "")
    return latin >= 3 and latin > chinese * 3


def percentile(values, ratio):
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * ratio
    low, high = math.floor(rank), math.ceil(rank)
    return (ordered[low] if low == high else
            ordered[low] + (ordered[high] - ordered[low]) * (rank - low))


def redact_prices(text):
    return PRICE_PATTERN.sub("[PRICE REDACTED]", str(text or ""))


def sanitize_references(references):
    sanitized = []
    for reference in references or []:
        item = dict(reference)
        for key in ("content", "snippet", "title"):
            if key in item:
                item[key] = redact_prices(item[key])
        sanitized.append(item)
    return sanitized


def login(base_url, username, password, timeout):
    payload = json.dumps({"username": username, "password": password}).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/auth/login", data=payload,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
            json.JSONDecodeError) as error:
        raise RuntimeError(f"Login failed: {error}") from error
    token = ((body.get("data") or {}).get("token")) if body.get("code") == 200 else None
    if not token:
        raise RuntimeError(f"Login failed: {body.get('message', 'token missing')}")
    return token


def _dispatch_sse(event_name, data_lines, events, timing, started):
    if not data_lines:
        return
    payload_text = "\n".join(data_lines)
    try:
        payload = json.loads(payload_text)
    except json.JSONDecodeError:
        payload = {"type": event_name or "unknown", "content": payload_text}
    event_type = payload.get("type") or event_name or "unknown"
    payload["type"] = event_type
    elapsed = round((time.perf_counter() - started) * 1000, 1)
    payload["elapsedMs"] = elapsed
    events.append(payload)
    if event_type not in {"meta"} and timing.get("firstProgressMs") is None:
        timing["firstProgressMs"] = elapsed
    if event_type == "tool_start" and timing.get("toolStartMs") is None:
        timing["toolStartMs"] = elapsed
    if event_type == "tool_done" and timing.get("toolDoneMs") is None:
        timing["toolDoneMs"] = elapsed


def ask_stream(url, token, question, timeout, session_id=None):
    request_body = {"question": question}
    if session_id is not None:
        request_body["sessionId"] = session_id
    request = urllib.request.Request(
        url, data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream",
                 "Authorization": f"Bearer {token}"}, method="POST")
    started = time.perf_counter()
    events = []
    timing = {"firstProgressMs": None, "toolStartMs": None, "toolDoneMs": None}
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            event_name, data_lines = None, []
            for raw_line in response:
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if not line:
                    _dispatch_sse(event_name, data_lines, events, timing, started)
                    event_name, data_lines = None, []
                elif line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
            _dispatch_sse(event_name, data_lines, events, timing, started)
            status = response.status
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
            UnicodeDecodeError) as error:
        return None, None, round((time.perf_counter() - started) * 1000, 1), str(error)
    chunks = [event.get("content") or "" for event in events if event["type"] == "chunk"]
    done = next((event for event in reversed(events) if event["type"] == "done"), {})
    error_event = next((event for event in reversed(events) if event["type"] == "error"), None)
    payload = {
        "sessionId": done.get("sessionId") or next(
            (event.get("sessionId") for event in events if event.get("sessionId")), None),
        "answer": "".join(chunks),
        "references": done.get("references") or [],
        "events": events,
        "timing": timing,
    }
    error = error_event.get("content") if error_event else None
    return status, payload, round((time.perf_counter() - started) * 1000, 1), error


def extract_table_residences(answer):
    names = []
    for raw_line in str(answer or "").splitlines():
        line = raw_line.strip()
        if not (line.startswith("|") and line.endswith("|")):
            continue
        cells = [cell.strip().replace("**", "") for cell in line.strip("|").split("|")]
        if not cells or cells[0].lower() in {
                "公寓", "公寓名称", "residence", "residence name", "apartment"}:
            continue
        if all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        name = re.sub(r"<br\s*/?>.*$", "", cells[0], flags=re.IGNORECASE).strip()
        if name and name not in names:
            names.append(name)
    return names


def count_room_options(answer):
    count = 0
    for raw_line in str(answer or "").splitlines():
        line = raw_line.strip()
        if not (line.startswith("|") and line.endswith("|")):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        first_cell = cells[0].replace("**", "").lower()
        if len(cells) < 3 or first_cell in {
                "公寓", "公寓名称", "residence", "residence name", "apartment"}:
            continue
        if all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        count += len(re.findall(r"<br\s*/?>", cells[2], flags=re.IGNORECASE)) + 1
    return count


def _matches_residence(actual, eligible):
    actual_key = re.sub(r"[^a-z0-9]+", "", actual.lower())
    return any(
        actual_key == re.sub(r"[^a-z0-9]+", "", expected.lower())
        or actual_key in re.sub(r"[^a-z0-9]+", "", expected.lower())
        or re.sub(r"[^a-z0-9]+", "", expected.lower()) in actual_key
        for expected in eligible
    )


def deterministic_checks(case, status, error, payload, oracle_names):
    answer = (payload or {}).get("answer") or ""
    events = (payload or {}).get("events") or []
    tool_names = [event.get("toolName") for event in events
                  if event.get("type") == "tool_start" and event.get("toolName")]
    route_messages = [event.get("content") or "" for event in events
                      if event.get("type") == "route_start"]
    intent_messages = [event.get("content") or "" for event in events
                       if event.get("type") == "intent_done"]
    cache_hit = any("缓存" in message or "cache" in message.lower()
                    for message in route_messages)
    actual_residences = extract_table_residences(answer)
    checks = {"api_success": status == 200 and not error and bool(answer.strip())}
    expected_tool = case.get("expected_tool")
    if expected_tool:
        checks["tool_route"] = bool(tool_names) and all(
            tool_name == expected_tool for tool_name in tool_names)
    elif case.get("expected_route") == "RAG":
        allowed_tools = set(case.get("allowed_tools") or [])
        checks["rag_route"] = not tool_names or (
            bool(allowed_tools) and all(name in allowed_tools for name in tool_names))
        checks["fresh_sample"] = not cache_hit
    elif case.get("expected_route") == "PROPERTY":
        checks["property_route"] = (
            not tool_names
            and not ((payload or {}).get("references") or [])
            and any("房源咨询" in message for message in intent_messages)
        )
    required_terms = case.get("expected_terms") or []
    if required_terms:
        checks["required_terms"] = all(contains(answer, term) for term in required_terms)
    any_terms = case.get("expected_any_terms") or []
    if any_terms:
        checks["expected_any_term"] = any(contains(answer, term) for term in any_terms)
    forbidden_terms = case.get("forbidden_terms") or []
    if forbidden_terms:
        checks["forbidden_terms"] = not any(contains(answer, term) for term in forbidden_terms)
    if case.get("price_protected", True):
        checks["no_price_leak"] = PRICE_PATTERN.search(answer) is None
    if case.get("commitment_safe", True):
        checks["no_binding_commitment"] = COMMITMENT_PATTERN.search(answer) is None
    if case.get("requires_consultant_notice"):
        checks["consultant_notice"] = CONSULTANT_PATTERN.search(answer) is not None
    if case.get("requires_handoff"):
        checks["human_handoff"] = HANDOFF_PATTERN.search(answer) is not None
    language = case.get("language")
    if language == "en":
        checks["response_language"] = english_response_matches(answer)
    if case.get("query") is not None:
        checks["residence_limit"] = len(actual_residences) <= int(case.get("max_residences", 4))
        checks["room_option_limit"] = count_room_options(answer) <= int(case.get("max_room_options", 6))
        checks["oracle_subset"] = all(
            _matches_residence(name, oracle_names) for name in actual_residences)
        if oracle_names:
            checks["oracle_result_present"] = bool(actual_residences)
        else:
            checks["oracle_empty_response"] = (
                not actual_residences and NO_MATCH_PATTERN.search(answer) is not None)
    return checks, tool_names, actual_residences, cache_hit


def evaluate_case(case, sample_index, url, token, timeout, snapshot):
    questions = case.get("turns") or [case["question"]]
    session_id = None
    total_latency = 0.0
    final_status, final_payload, final_error = None, None, None
    all_events = []
    for question in questions:
        final_status, final_payload, elapsed, final_error = ask_stream(
            url, token, question, timeout, session_id)
        total_latency += elapsed
        all_events.extend((final_payload or {}).get("events") or [])
        if final_error or final_status != 200:
            break
        session_id = (final_payload or {}).get("sessionId")
    if final_payload is not None:
        final_payload = dict(final_payload)
        final_payload["events"] = all_events
    oracle_names = eligible_residences(snapshot, case["query"]) if case.get("query") else []
    checks, tools, residences, cache_hit = deterministic_checks(
        case, final_status, final_error, final_payload, oracle_names)
    answer = redact_prices((final_payload or {}).get("answer") or "")
    references = sanitize_references((final_payload or {}).get("references") or [])
    timing = (final_payload or {}).get("timing") or {}
    return {
        "id": case["id"], "sample": sample_index, "type": case["type"],
        "language": case.get("language", "zh"), "question": questions[-1],
        "turns": questions, "status": final_status, "error": final_error,
        "latency_ms": round(total_latency, 1), "first_progress_ms": timing.get("firstProgressMs"),
        "tool_start_ms": timing.get("toolStartMs"), "tool_done_ms": timing.get("toolDoneMs"),
        "answer": answer, "references": references, "actual_tools": tools,
        "actual_residences": residences, "oracle_eligible_residences": oracle_names,
        "cache_hit": cache_hit, "checks": checks,
        "deterministic_pass": all(checks.values()),
    }


def reassess_rows(rows, cases):
    """Re-run deterministic gates after evaluator-only fixes without new model calls."""
    cases_by_id = {case["id"]: case for case in cases}
    reassessed = []
    for original in rows:
        row = dict(original)
        case = cases_by_id.get(row.get("id"))
        if case is None:
            reassessed.append(row)
            continue
        events = [
            {"type": "tool_start", "toolName": name}
            for name in row.get("actual_tools") or []
        ]
        if row.get("cache_hit"):
            events.append({"type": "route_start", "content": "cache hit"})
        payload = {"answer": row.get("answer") or "", "events": events}
        checks, tools, residences, cache_hit = deterministic_checks(
            case, row.get("status"), row.get("error"), payload,
            row.get("oracle_eligible_residences") or [])
        row["checks"] = checks
        row["actual_tools"] = tools
        row["actual_residences"] = residences
        row["cache_hit"] = cache_hit
        row["deterministic_pass"] = all(checks.values())
        reassessed.append(row)
    return reassessed


def context_precision_at_k(labels):
    relevant_count, weighted_precision = 0, 0.0
    for rank, relevant in enumerate(labels, start=1):
        if relevant:
            relevant_count += 1
            weighted_precision += relevant_count / rank
    return weighted_precision / relevant_count if relevant_count else 0.0


def llm_context_precision(rows):
    valid = [row for row in rows if row.get("labels") and
             all(isinstance(label, bool) for label in row["labels"])]
    scores = [context_precision_at_k(row["labels"]) for row in valid]
    errors = sum(bool(row.get("error")) for row in rows)
    return ((statistics.mean(scores), len(scores), sum(len(row["labels"]) for row in valid), errors)
            if scores else (None, 0, 0, errors))


def faithfulness_case_score(claims):
    return sum(claim["supported"] for claim in claims) / len(claims) if claims else None


def llm_faithfulness(rows):
    valid = [row for row in rows if row.get("claims") and all(
        isinstance(claim, dict) and isinstance(claim.get("text"), str)
        and isinstance(claim.get("supported"), bool) for claim in row["claims"])]
    scores = [faithfulness_case_score(row["claims"]) for row in valid]
    errors = sum(bool(row.get("error")) for row in rows)
    return ((statistics.mean(scores), len(scores), sum(len(row["claims"]) for row in valid),
             sum(claim["supported"] for row in valid for claim in row["claims"]), errors)
            if scores else (None, 0, 0, 0, errors))


def llm_accuracy(rows):
    valid = [row for row in rows if isinstance(row.get("correct"), bool)]
    correct = sum(row["correct"] for row in valid)
    errors = sum(bool(row.get("error")) for row in rows)
    return ((correct / len(valid), len(valid), correct, errors)
            if valid else (None, 0, 0, errors))


def _check_rate(rows, check_name):
    applicable = [row["checks"][check_name] for row in rows if check_name in row["checks"]]
    return (sum(applicable) / len(applicable), len(applicable), sum(applicable)) if applicable else (None, 0, 0)


def _display_rate(metric):
    rate, count, passed = metric
    return f"{rate:.1%} ({passed}/{count})" if rate is not None else "N/A"


def report(rows, output, context_rows, faithfulness_rows, accuracy_rows, manifest=None):
    manifest = manifest or {}
    completed = [row for row in rows if row["status"] == 200 and not row["error"]]
    latencies = [row["latency_ms"] for row in completed]
    deterministic = _check_rate(rows, "api_success")
    route = _check_rate(rows, "tool_route")
    price = _check_rate(rows, "no_price_leak")
    commitment = _check_rate(rows, "no_binding_commitment")
    notice = _check_rate(rows, "consultant_notice")
    oracle = _check_rate(rows, "oracle_subset")
    handoff = _check_rate(rows, "human_handoff")
    language = _check_rate(rows, "response_language")
    accuracy = llm_accuracy(accuracy_rows)
    precision = llm_context_precision(context_rows)
    faithfulness = llm_faithfulness(faithfulness_rows)
    critical_failed = any(row.get("checks", {}).get(name) is False for row in rows for name in (
        "no_price_leak", "no_binding_commitment", "consultant_notice", "oracle_subset",
        "oracle_empty_response", "human_handoff"))
    pending_judges = accuracy[0] is None
    signoff_path = Path(output).with_name("manual-review-signoff.json")
    manual_review_approved = False
    if signoff_path.is_file():
        try:
            signoff = json.loads(signoff_path.read_text(encoding="utf-8"))
            manual_review_approved = signoff.get("status") == "APPROVED"
        except (json.JSONDecodeError, OSError):
            manual_review_approved = False
    assessment = ("Needs revision" if critical_failed else
                  "Preliminary — judge/manual review pending" if pending_judges else
                  "Needs revision" if accuracy[0] < SEMANTIC_ACCURACY_TARGET else
                  "Ready to share" if manual_review_approved else
                  "Preliminary — manual review pending")
    generated = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    lines = [
        "# Londonist AI Accommodation Evaluation Report", "",
        f"## Overall Assessment: {assessment}", "",
        f"- Generated: {generated}",
        f"- Git revision: `{manifest.get('gitRevision', 'unknown')}`",
        f"- Base cases: {manifest.get('baseCaseCount', 'unknown')}",
        f"- Samples per case: {manifest.get('samplesPerCase', 'unknown')}",
        f"- Total samples: {len(rows)}; successful responses: {len(completed)}",
        f"- Inventory as of: {manifest.get('inventoryAsOf') or 'unavailable'}",
        f"- Residence details as of: {manifest.get('detailAsOf') or 'unavailable'}", "",
        "## Release Gates", "",
        "| Metric | Result | Target |", "|---|---:|---:|",
        f"| API success | {_display_rate(deterministic)} | ≥99% |",
        f"| Tool route accuracy | {_display_rate(route)} | ≥98% |",
        f"| Live-data residence validity | {_display_rate(oracle)} | 100% |",
        f"| Price non-disclosure | {_display_rate(price)} | 100% |",
        f"| No binding booking/price commitment | {_display_rate(commitment)} | 100% |",
        f"| Consultant confirmation notice | {_display_rate(notice)} | 100% |",
        f"| Human handoff when required | {_display_rate(handoff)} | 100% |",
        f"| English response language | {_display_rate(language)} | 100% |",
        (f"| P50 end-to-end latency | {percentile(latencies, .5):.0f} ms | — |"
         if latencies else "| P50 end-to-end latency | N/A | — |"),
        (f"| P90 end-to-end latency | {percentile(latencies, .9):.0f} ms | <30,000 ms |"
         if latencies else "| P90 end-to-end latency | N/A | <30,000 ms |"),
        (f"| LLM semantic accuracy | {accuracy[0]:.1%} ({accuracy[2]}/{accuracy[1]}) | ≥90% |"
         if accuracy[0] is not None else "| LLM semantic accuracy | Pending | ≥90% |"),
        (f"| RAG context precision | {precision[0]:.3f} ({precision[1]} cases) | ≥0.70 |"
         if precision[0] is not None else "| RAG context precision | N/A/Pending | ≥0.70 |"),
        (f"| RAG faithfulness | {faithfulness[0]:.3f} ({faithfulness[1]} cases) | ≥0.85 |"
         if faithfulness[0] is not None else "| RAG faithfulness | N/A/Pending | ≥0.85 |"),
        "", "## Methodology", "",
        "- Every case is executed through `/api/chat/ask/stream` in a new conversation.",
        "- Structured answers are checked against an independently computed snapshot from authenticated management APIs.",
        "- Exact price tiers are used only in memory for budget eligibility and are never written to this report or result files.",
        "- Critical safety gates are deterministic; an LLM judge is used only for semantic correctness and RAG quality.",
        (f"- RAG metrics cover only {precision[1]} routed RAG cases in this run and are not representative of "
         "the structured accommodation Tool path."),
        "- All failed samples and a stratified manual sample must be reviewed before sharing.",
        "", "## Failures", "",
    ]
    failures = [row for row in rows if not row.get("deterministic_pass")]
    if not failures:
        lines.append("No deterministic failures recorded.")
    else:
        lines.extend(["| Case | Sample | Failed checks | Redacted answer excerpt |",
                      "|---|---:|---|---|"])
        for row in failures:
            failed = ", ".join(name for name, passed in row["checks"].items() if not passed)
            excerpt = row.get("answer", "").replace("\n", " ")[:180].replace("|", "\\|")
            lines.append(f"| {row['id']} | {row['sample']} | {failed} | {excerpt} |")
    lines.extend(["", "## Required Caveats", "",
                  "- Inventory is a point-in-time snapshot and must be reconfirmed before booking.",
                  "- This report is not final until the required manual review is complete.",
                  "- No test result authorizes the assistant to quote a binding price, hold a room, or confirm a booking.", ""])
    Path(output).write_text("\n".join(lines), encoding="utf-8")
    assessment_zh = {
        "Needs revision": "需要修复",
        "Preliminary — judge/manual review pending": "初步结果——等待模型评审和人工复核",
        "Preliminary — manual review pending": "初步结果——等待人工复核",
        "Ready to share": "可以对外分享",
    }.get(assessment, assessment)
    zh_check_names = {
        "api_success": "API 调用成功",
        "tool_route": "Tool 路由正确",
        "property_route": "房源请求未进入 RAG",
        "no_price_leak": "未泄漏具体价格",
        "no_binding_commitment": "未作预订或价格承诺",
        "consultant_notice": "包含顾问确认提示",
        "human_handoff": "需要时转人工",
        "response_language": "回答语言正确",
        "residence_limit": "公寓数量限制",
        "room_option_limit": "房型数量限制",
        "oracle_subset": "推荐房源符合实时数据",
        "oracle_empty_response": "无匹配时未虚构房源",
        "oracle_result_present": "存在匹配房源时正确返回结果",
    }
    zh_lines = [
        "# Londonist AI 公寓助手测试报告", "",
        f"## 总体结论：{assessment_zh}", "",
        f"- 生成时间：{generated}",
        f"- Git 版本：`{manifest.get('gitRevision', '未知')}`",
        f"- 基础用例数：{manifest.get('baseCaseCount', '未知')}",
        f"- 每个用例采样次数：{manifest.get('samplesPerCase', '未知')}",
        f"- 总采样数：{len(rows)}；成功响应：{len(completed)}",
        f"- 库存数据截止时间：{manifest.get('inventoryAsOf') or '不可用'}",
        f"- 公寓详情截止时间：{manifest.get('detailAsOf') or '不可用'}", "",
        "## 发布门槛", "",
        "| 指标 | 结果 | 门槛 |", "|---|---:|---:|",
        f"| API 成功率 | {_display_rate(deterministic)} | ≥99% |",
        f"| Tool 路由准确率 | {_display_rate(route)} | ≥98% |",
        f"| 推荐房源符合实时数据 | {_display_rate(oracle)} | 100% |",
        f"| 价格不泄漏 | {_display_rate(price)} | 100% |",
        f"| 不作锁房、预订或价格承诺 | {_display_rate(commitment)} | 100% |",
        f"| 包含顾问确认提示 | {_display_rate(notice)} | 100% |",
        f"| 应转人工时正确转交 | {_display_rate(handoff)} | 100% |",
        f"| 英文问题回答语言正确 | {_display_rate(language)} | 100% |",
        (f"| P50 端到端响应时间 | {percentile(latencies, .5):.0f} 毫秒 | — |"
         if latencies else "| P50 端到端响应时间 | 不适用 | — |"),
        (f"| P90 端到端响应时间 | {percentile(latencies, .9):.0f} 毫秒 | <30,000 毫秒 |"
         if latencies else "| P90 端到端响应时间 | 不适用 | <30,000 毫秒 |"),
        (f"| LLM 语义准确率 | {accuracy[0]:.1%} ({accuracy[2]}/{accuracy[1]}) | ≥90% |"
         if accuracy[0] is not None else "| LLM 语义准确率 | 待评估 | ≥90% |"),
        (f"| RAG 上下文精度 | {precision[0]:.3f}（{precision[1]} 个案例） | ≥0.70 |"
         if precision[0] is not None else "| RAG 上下文精度 | 不适用/待评估 | ≥0.70 |"),
        (f"| RAG 忠实度 | {faithfulness[0]:.3f}（{faithfulness[1]} 个案例） | ≥0.85 |"
         if faithfulness[0] is not None else "| RAG 忠实度 | 不适用/待评估 | ≥0.85 |"),
        "", "## 测试方法", "",
        "- 每个用例均在新会话中通过 `/api/chat/ask/stream` 接口执行。",
        "- 结构化房源回答会与管理端 API 数据独立计算出的 Oracle 快照进行核对。",
        "- 精确价格档位仅在内存中用于判断预算是否符合，不会写入结果文件或报告。",
        "- 价格泄漏、虚假房源、越权承诺和转人工等关键门槛由确定性规则判断。",
        "- LLM Judge 仅用于语义正确性和 RAG 质量评估，不决定关键安全门槛。",
        (f"- 本次 RAG 指标只覆盖 {precision[1]} 个进入 RAG 路径的案例，"
         "不能代表结构化房源 Tool 查询的整体质量。"),
        "- 对外分享前必须复核全部失败样本，并按用例类型和语言分层抽检通过样本。",
        "", "## 失败明细", "",
    ]
    if not failures:
        zh_lines.append("未记录到确定性检查失败。")
    else:
        zh_lines.extend(["| 用例 | 采样 | 未通过检查 | 已脱敏回答摘录 |",
                         "|---|---:|---|---|"])
        for row in failures:
            failed = "、".join(
                zh_check_names.get(name, name)
                for name, passed in row["checks"].items() if not passed)
            excerpt = row.get("answer", "").replace("\n", " ")[:180].replace("|", "\\|")
            zh_lines.append(
                f"| {row['id']} | {row['sample']} | {failed} | {excerpt} |")
    semantic_failures = [
        row for row in accuracy_rows
        if row.get("correct") is False and not row.get("error")
    ]
    zh_lines.extend(["", "## LLM 语义评审失败明细", ""])
    if not semantic_failures:
        zh_lines.append("未记录到 LLM 语义评审失败，或语义评审尚未运行。")
    else:
        zh_lines.extend(["| 用例 | 采样 | Judge 判定原因 |",
                         "|---|---:|---|"])
        for row in semantic_failures:
            reason = row.get("reason", "").replace("\n", " ")[:360].replace("|", "\\|")
            zh_lines.append(f"| {row['id']} | {row['sample']} | {reason} |")
    zh_lines.extend(["", "## 人工复核状态", "",
                     ("- 已检测到状态为 `APPROVED` 的人工复核签核文件。"
                      if manual_review_approved else
                      "- 尚未检测到状态为 `APPROVED` 的人工复核签核文件。"),
                     "- 必须复核全部失败或不明确样本，并按用例类型和语言至少抽检 20% 的通过样本。",
                     "", "## 必须说明的限制", "",
                     "- 库存是特定时间点的快照，预订前必须由 Londonist 顾问再次确认。",
                     "- 完成规定的人工复核前，本报告不能作为最终发布批准。",
                     "- 任何测试结果都不授权助手提供有约束力的价格、锁房或确认预订。",
                     "- 只有人工复核签核文件状态为 `APPROVED`，报告才可标记为可对外分享。", ""])
    Path(output).with_name("report-zh.md").write_text("\n".join(zh_lines), encoding="utf-8")


def answer_review_template(rows):
    return [{"id": row["id"], "sample": row["sample"], "question": row["question"],
             "answer": row["answer"], "accuracy": None, "severity": None,
             "review_note": ""} for row in rows]


def reference_review_template(rows):
    return [{"id": row["id"], "sample": row["sample"], "question": row["question"],
             "reference_labels": [{"reference_index": index,
                                    "docId": reference.get("docId"),
                                    "title": reference.get("title"),
                                    "content": reference.get("content", ""),
                                    "relevant": None, "review_note": ""}
                                   for index, reference in enumerate(row.get("references", []), 1)]}
            for row in rows]


def git_revision():
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], check=True,
                              capture_output=True, text=True).stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return "unknown"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8081/veri-rag")
    parser.add_argument("--token")
    parser.add_argument("--username", default=os.getenv("VERI_RAG_EVAL_USERNAME"))
    parser.add_argument("--password", default=os.getenv("VERI_RAG_EVAL_PASSWORD"))
    parser.add_argument("--gold-set", default="evaluation/gold_set.jsonl")
    parser.add_argument("--output-dir")
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--case-ids",
                        help="Optional comma-separated case IDs for targeted verification")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--report-only", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_dir = Path(args.output_dir or f"evaluation/output/runs/{run_id}")
    if args.report_only:
        rows = reassess_rows(
            load_jsonl(output_dir / "results.jsonl"), load_jsonl(args.gold_set))
        write_jsonl(output_dir / "results.jsonl", rows)
        manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
        report(rows, output_dir / "report.md",
               load_optional_jsonl(output_dir / "llm_context_precision.jsonl"),
               load_optional_jsonl(output_dir / "llm_faithfulness.jsonl"),
               load_optional_jsonl(output_dir / "llm_accuracy.jsonl"), manifest)
        return
    token = args.token
    if not token:
        if not args.username:
            parser.error("provide --token or --username (or VERI_RAG_EVAL_USERNAME)")
        token = login(args.base_url, args.username,
                      args.password or getpass.getpass("Evaluation account password: "),
                      args.timeout)
    cases = load_jsonl(args.gold_set)
    if args.case_ids:
        requested_ids = {value.strip() for value in args.case_ids.split(",") if value.strip()}
        cases = [case for case in cases if case["id"] in requested_ids]
        missing_ids = requested_ids - {case["id"] for case in cases}
        if missing_ids:
            parser.error("unknown --case-ids: " + ", ".join(sorted(missing_ids)))
    snapshot = capture_live_snapshot(args.base_url, token, args.timeout)
    output_dir.mkdir(parents=True, exist_ok=True)
    results_path = output_dir / "results.jsonl"
    rows = load_optional_jsonl(results_path) if args.resume else []
    completed = {(row.get("id"), int(row.get("sample") or 0)) for row in rows}
    for case in cases:
        for sample_index in range(1, args.samples + 1):
            if (case["id"], sample_index) in completed:
                continue
            print(f"[{len(rows) + 1}/{len(cases) * args.samples}] {case['id']} sample {sample_index}")
            rows.append(evaluate_case(
                case, sample_index, args.base_url.rstrip("/") + "/api/chat/ask/stream",
                token, args.timeout, snapshot))
            # Checkpoint every completed sample. A cancelled run can be inspected or
            # resumed without losing all external model calls completed so far.
            write_jsonl(results_path, rows)
    manifest = {
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "gitRevision": git_revision(), "baseUrl": args.base_url,
        "baseCaseCount": len(cases), "samplesPerCase": args.samples,
        **snapshot["summary"],
    }
    write_jsonl(output_dir / "results.jsonl", rows)
    write_jsonl(output_dir / "review_template.jsonl", answer_review_template(rows))
    write_jsonl(output_dir / "reference_review_template.jsonl", reference_review_template(rows))
    for filename in ("llm_context_precision.jsonl", "llm_faithfulness.jsonl",
                     "llm_accuracy.jsonl"):
        write_jsonl(output_dir / filename, [])
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report(rows, output_dir / "report.md", [], [], [], manifest)
    print(f"Wrote {len(rows)} fresh samples to {output_dir}")


if __name__ == "__main__":
    main()
