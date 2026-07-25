import unittest

from run_evaluation import (
    context_precision_at_k,
    english_response_matches,
    faithfulness_case_score,
    llm_accuracy,
    llm_context_precision,
    llm_faithfulness,
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


class ResponseLanguageTests(unittest.TestCase):

    def test_english_answer_passes(self):
        self.assertTrue(english_response_matches("The account will be locked for 30 minutes."))

    def test_chinese_answer_to_english_question_fails(self):
        self.assertFalse(english_response_matches("账号会被锁定30分钟。"))

    def test_short_technical_english_answer_passes(self):
        self.assertTrue(english_response_matches("Use multipart/form-data with the file field."))


if __name__ == "__main__":
    unittest.main()
