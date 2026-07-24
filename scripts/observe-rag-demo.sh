#!/usr/bin/env bash
# 真实 RAG 观测数据演示：发送流式问题并制造一次回答缓存命中。
# 用法：bash scripts/observe-rag-demo.sh
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8081/veri-rag}"
USERNAME="${RAG_DEMO_USERNAME:-admin}"
PASSWORD="${RAG_DEMO_PASSWORD:-123456}"

echo "Logging in to ${API_BASE} as ${USERNAME}..."
login_response="$(curl -fsS "${API_BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
token="$(printf '%s' "${login_response}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
if [[ -z "${token}" ]]; then
  echo "Login failed: token was not found in response." >&2
  exit 1
fi

ask() {
  local question="$1"
  printf '  → %s\n' "${question}"
  curl -fsS -N "${API_BASE}/api/chat/ask/stream" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"question\":\"${question}\",\"categoryIds\":[]}" \
    -o /dev/null
}

echo "Sending real streaming RAG requests..."
ask "公司的薪资区间是多少？"
ask "新员工入职当天需要携带哪些材料？"
ask "员工请假需要如何申请？"
ask "公司有哪些福利？"
ask "公司编码规范有哪些？"

echo "Repeating the first question to create a cache-hit metric..."
ask "公司的薪资区间是多少？"

echo
echo "Requests completed. Metrics are exported to OTLP every 15 seconds."
echo "Open Grafana and query: rag_llm_duration_milliseconds_count"
echo "Direct application metrics: ${API_BASE}/actuator/prometheus"
