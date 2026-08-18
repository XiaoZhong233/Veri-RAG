import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from judge_accuracy import build_expected_facts
from run_evaluation import (
    PRICE_PATTERN,
    COMMITMENT_PATTERN,
    NO_MATCH_PATTERN,
    context_precision_at_k,
    deterministic_checks,
    extract_table_residences,
    english_response_matches,
    faithfulness_case_score,
    llm_accuracy,
    llm_context_precision,
    llm_faithfulness,
    redact_prices,
    report,
)


class IntentRoutingTests(unittest.TestCase):

    def test_expected_intent_checks_machine_readable_sse_field(self):
        case = {"expected_route": "PROPERTY", "expected_intent": "CLARIFY"}
        payload = {
            "answer": "请告诉我具体是哪间公寓。",
            "references": [],
            "events": [{"type": "intent_done", "intent": "CLARIFY"}],
        }
        checks, _, _, _ = deterministic_checks(case, 200, None, payload, [])
        self.assertTrue(checks["property_route"])
        self.assertTrue(checks["intent_classification"])

    def test_rag_route_requires_none_intent(self):
        case = {"expected_route": "RAG"}
        payload = {
            "answer": "请参考合同流程。",
            "references": [{"content": "合同流程"}],
            "events": [{"type": "intent_done", "intent": "NONE"}],
        }
        checks, _, _, _ = deterministic_checks(case, 200, None, payload, [])
        self.assertTrue(checks["rag_route"])


class ContextPrecisionTests(unittest.TestCase):

    def test_relevant_chunk_ranked_first_scores_one(self):
        self.assertEqual(1.0, context_precision_at_k([True, False]))

    def test_relevant_chunk_ranked_second_scores_half(self):
        self.assertEqual(0.5, context_precision_at_k([False, True]))

    def test_multiple_relevant_chunks_use_precision_at_each_relevant_rank(self):
        self.assertAlmostEqual((1.0 + 2 / 3) / 2, context_precision_at_k([True, False, True]))

    def test_dataset_score_averages_cases_and_excludes_errors(self):
        score, case_count, chunk_count, error_count = llm_context_precision([
            {"labels": [True, False], "error": None},
            {"labels": [False, True], "error": None},
            {"labels": [], "error": "invalid labels"},
            {"labels": [], "error": None},
        ])
        self.assertEqual(0.75, score)
        self.assertEqual(2, case_count)
        self.assertEqual(4, chunk_count)
        self.assertEqual(1, error_count)


class FaithfulnessTests(unittest.TestCase):

    def test_case_score_is_supported_claim_ratio(self):
        self.assertEqual(2 / 3, faithfulness_case_score([
            {"text": "claim one", "supported": True},
            {"text": "claim two", "supported": False},
            {"text": "claim three", "supported": True},
        ]))

    def test_dataset_score_averages_cases_and_excludes_skips_and_errors(self):
        score, case_count, claim_count, supported_count, error_count = llm_faithfulness([
            {"claims": [{"text": "a", "supported": True}], "error": None},
            {"claims": [
                {"text": "b", "supported": True},
                {"text": "c", "supported": False},
            ], "error": None},
            {"claims": [], "error": None},
            {"claims": [], "error": "invalid"},
        ])
        self.assertEqual(0.75, score)
        self.assertEqual(2, case_count)
        self.assertEqual(3, claim_count)
        self.assertEqual(2, supported_count)
        self.assertEqual(1, error_count)


class AccuracyTests(unittest.TestCase):

    def test_accuracy_uses_only_binary_judged_cases(self):
        score, case_count, correct_count, error_count = llm_accuracy([
            {"correct": True, "error": None},
            {"correct": False, "error": None},
            {"correct": None, "error": None},
            {"correct": None, "error": "invalid"},
        ])
        self.assertEqual(0.5, score)
        self.assertEqual(2, case_count)
        self.assertEqual(1, correct_count)
        self.assertEqual(1, error_count)

    def test_expected_any_terms_are_presented_as_alternatives(self):
        facts = build_expected_facts(
            {"expected_any_terms": ["Aldgate", "Leman", "landmark"]}, {})
        self.assertEqual(1, len(facts))
        self.assertTrue(facts[0].startswith("[ANY-OF: ONE MATCH IS SUFFICIENT]"))
        self.assertIn("Aldgate | Leman | landmark", facts[0])

    def test_expected_any_terms_are_not_sent_to_judge_when_already_satisfied(self):
        facts = build_expected_facts(
            {"expected_any_terms": ["Aldgate", "Leman", "landmark"]},
            {"answer": "Nearby landmarks include Spitalfields Market."},
        )
        self.assertEqual(1, len(facts))
        self.assertTrue(
            facts[0].startswith("[PREVALIDATED ANY-OF: REQUIREMENT ALREADY SATISFIED]"))


class ReleaseGateTests(unittest.TestCase):

    def test_tool_route_at_95_percent_passes_release_gate(self):
        rows = self._route_rows(passed=19, total=20)

        english, chinese = self._render_reports(rows)

        self.assertIn("Tool route accuracy | 95.0% (19/20) | ≥95%", english)
        self.assertIn("Overall Assessment: Preliminary — manual review pending", english)
        self.assertIn("Tool 路由准确率 | 95.0% (19/20) | ≥95%", chinese)

    def test_tool_route_below_95_percent_requires_revision(self):
        rows = self._route_rows(passed=18, total=20)

        english, chinese = self._render_reports(rows)

        self.assertIn("Overall Assessment: Needs revision", english)
        self.assertIn("总体结论：需要修复", chinese)

    def test_answer_quality_rates_exclude_failed_api_samples(self):
        rows = [
            {
                "id": "OK", "sample": 1, "status": 200, "error": None,
                "latency_ms": 1000, "answer": "ok",
                "checks": {"api_success": True, "consultant_notice": True},
                "deterministic_pass": True,
            },
            {
                "id": "TIMEOUT", "sample": 1, "status": 200, "error": "timeout",
                "latency_ms": 30000, "answer": "",
                "checks": {"api_success": False, "consultant_notice": False},
                "deterministic_pass": False,
            },
        ]
        accuracy = [
            {"id": "OK", "sample": 1, "correct": True, "error": None},
            {"id": "TIMEOUT", "sample": 1, "correct": False, "error": None},
        ]

        english, chinese = self._render_reports(rows, accuracy)

        self.assertIn("API success | 50.0% (1/2)", english)
        self.assertIn("Consultant confirmation notice | 100.0% (1/1)", english)
        self.assertIn("LLM semantic accuracy | 100.0% (1/1)", english)
        self.assertIn("包含顾问确认提示 | 100.0% (1/1)", chinese)

    @staticmethod
    def _route_rows(passed, total):
        return [
            {
                "id": f"ROUTE-{index}",
                "sample": 1,
                "status": 200,
                "error": None,
                "latency_ms": 1000,
                "answer": "ok",
                "checks": {"tool_route": index < passed},
                "deterministic_pass": index < passed,
            }
            for index in range(total)
        ]

    @staticmethod
    def _render_reports(rows, accuracy_rows=None):
        with TemporaryDirectory() as directory:
            output = Path(directory) / "report.md"
            if accuracy_rows is None:
                accuracy_rows = [
                    {"id": row["id"], "sample": row["sample"],
                     "correct": True, "error": None}
                    for row in rows if row.get("status") == 200 and not row.get("error")
                ]
            report(rows, output, [], [], accuracy_rows, {})
            return (output.read_text(encoding="utf-8"),
                    output.with_name("report-zh.md").read_text(encoding="utf-8"))


class ResponseLanguageTests(unittest.TestCase):

    def test_english_answer_passes(self):
        self.assertTrue(english_response_matches("The account will be locked for 30 minutes."))

    def test_chinese_answer_to_english_question_fails(self):
        self.assertFalse(english_response_matches("账号会被锁定30分钟。"))

    def test_short_technical_english_answer_passes(self):
        self.assertTrue(english_response_matches("Use multipart/form-data with the file field."))


class SafetyContractTests(unittest.TestCase):

    def test_detects_and_redacts_price_amounts_and_ranges(self):
        answer = "每周 £430，价格范围 £450-£500，总价11180英镑"
        self.assertIsNotNone(PRICE_PATTERN.search(answer))
        redacted = redact_prices(answer)
        self.assertNotIn("430", redacted)
        self.assertNotIn("450", redacted)
        self.assertNotIn("500", redacted)
        self.assertNotIn("11180", redacted)

    def test_detects_binding_booking_commitments(self):
        self.assertIsNotNone(COMMITMENT_PATTERN.search("已为您锁定房间并确认预订成功"))
        self.assertIsNotNone(COMMITMENT_PATTERN.search("Your booking is confirmed"))
        self.assertIsNone(COMMITMENT_PATTERN.search("房型可能已经预订满额"))

    def test_extracts_only_residence_rows_from_markdown_table(self):
        answer = """| 公寓 | 房型 |\n|---|---|\n| Islington Residence | Ensuite |\n| Old Street Residence | Studio |"""
        self.assertEqual(
            ["Islington Residence", "Old Street Residence"],
            extract_table_residences(answer),
        )

    def test_english_apartment_header_is_not_a_residence(self):
        answer = """| Apartment | Room Type |
|---|---|
| Paddington Citi View | Twin Studio |"""
        self.assertEqual(["Paddington Citi View"], extract_table_residences(answer))

    def test_detects_unable_to_find_as_no_match(self):
        for answer in (
            "I was unable to find any matching properties.",
            "I wasn't able to find any available rooms.",
            "I haven't found any accommodations that match.",
            "There are currently no accommodations available near KCL.",
            "We don't currently have any listings that match.",
            "Currently, there are no apartments that fully match all of these criteria.",
            "No apartments currently match all of these specific conditions.",
            "There are no residences meeting all requested conditions.",
        ):
            with self.subTest(answer=answer):
                self.assertIsNotNone(NO_MATCH_PATTERN.search(answer))


if __name__ == "__main__":
    unittest.main()
