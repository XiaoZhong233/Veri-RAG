import json
import unittest
from pathlib import Path


class GoldSetContractTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("gold_set.jsonl")
        cls.cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
                     if line.strip()]

    def test_contains_50_unique_londonist_cases(self):
        self.assertEqual(50, len(self.cases))
        self.assertEqual(50, len({case["id"] for case in self.cases}))

    def test_contains_ambiguous_property_clarification_case(self):
        case = next(case for case in self.cases if case["id"] == "NEG-06")
        self.assertEqual("PROPERTY", case["expected_route"])
        self.assertEqual("CLARIFY", case["expected_intent"])
        self.assertIn("公寓", case["expected_terms"])
        self.assertTrue(case["expected_any_terms"])

    def test_all_safety_cases_expect_restricted_intent(self):
        safety_cases = [case for case in self.cases if case["type"] == "safety"]
        self.assertTrue(all(case["expected_route"] == "PROPERTY" for case in safety_cases))
        self.assertTrue(all(case["expected_intent"] == "RESTRICTED" for case in safety_cases))

    def test_regression_cases_expect_recommend_intent(self):
        for case_id in ("REC-11", "REC-18"):
            case = next(case for case in self.cases if case["id"] == case_id)
            self.assertEqual("search_room_offers", case["expected_tool"])
            self.assertEqual("RECOMMEND", case["expected_intent"])

    def test_acknowledgement_does_not_trigger_clarification(self):
        case = next(case for case in self.cases if case["id"] == "NEG-07")
        self.assertEqual("PROPERTY", case["expected_route"])
        self.assertEqual("ACKNOWLEDGE", case["expected_intent"])
        self.assertIn("有需要", case["expected_terms"])

    def test_every_case_defines_expected_route(self):
        self.assertTrue(all(
            case.get("expected_tool") or case.get("expected_route") == "PROPERTY"
            for case in self.cases))
        self.assertFalse(any(case.get("expected_route") == "RAG" for case in self.cases))

    def test_has_bilingual_and_critical_safety_coverage(self):
        self.assertGreaterEqual(sum(case.get("language") == "en" for case in self.cases), 8)
        self.assertGreaterEqual(sum(case.get("type") == "safety" for case in self.cases), 5)
        self.assertGreaterEqual(sum(bool(case.get("requires_handoff")) for case in self.cases), 8)


if __name__ == "__main__":
    unittest.main()
