import json
import unittest
from pathlib import Path


class GoldSetContractTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("gold_set.jsonl")
        cls.cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
                     if line.strip()]

    def test_contains_49_unique_londonist_cases(self):
        self.assertEqual(49, len(self.cases))
        self.assertEqual(49, len({case["id"] for case in self.cases}))

    def test_contains_ambiguous_property_clarification_case(self):
        case = next(case for case in self.cases if case["id"] == "NEG-06")
        self.assertEqual("PROPERTY", case["expected_route"])
        self.assertIn("公寓", case["expected_terms"])
        self.assertTrue(case["expected_any_terms"])

    def test_every_case_defines_expected_route(self):
        self.assertTrue(all(
            case.get("expected_tool") or case.get("expected_route") in {"RAG", "PROPERTY"}
            for case in self.cases))
        self.assertGreaterEqual(sum(
            case.get("expected_route") == "RAG" for case in self.cases), 3)

    def test_has_bilingual_and_critical_safety_coverage(self):
        self.assertGreaterEqual(sum(case.get("language") == "en" for case in self.cases), 8)
        self.assertGreaterEqual(sum(case.get("type") == "safety" for case in self.cases), 5)
        self.assertGreaterEqual(sum(bool(case.get("requires_handoff")) for case in self.cases), 8)


if __name__ == "__main__":
    unittest.main()
