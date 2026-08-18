import unittest

from judge_accuracy import build_expected_facts
from run_evaluation import (
    PRICE_PATTERN,
    COMMITMENT_PATTERN,
    NO_MATCH_PATTERN,
    context_precision_at_k,
    extract_table_residences,
    english_response_matches,
    faithfulness_case_score,
    llm_accuracy,
    llm_context_precision,
    llm_faithfulness,
    redact_prices,
)


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
        ):
            with self.subTest(answer=answer):
                self.assertIsNotNone(NO_MATCH_PATTERN.search(answer))


if __name__ == "__main__":
    unittest.main()
