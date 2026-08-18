import unittest

from oracle import eligible_residences


class LiveOracleTests(unittest.TestCase):

    def setUp(self):
        self.snapshot = {
            "residences": [
                {"id": 1, "name": "Near House", "city": "London"},
                {"id": 2, "name": "Far House", "city": "London"},
            ],
            "details": {
                1: {"nearbyPlaces": [{"placeName": "University College London (UCL)",
                                       "maxMinutes": 15}]},
                2: {"nearbyPlaces": [{"placeName": "UCL", "maxMinutes": 35}]},
            },
            "offers": [
                {"residenceId": 1, "rootType": "Ensuite", "inventoryStatus": "AVAILABLE",
                 "earliestStartDate": "2026-08-29", "latestEndDate": "2027-08-24",
                 "priceTiers": [{"minWeeks": 20, "maxWeeks": 39, "weeklyPrice": 430}]},
                {"residenceId": 2, "rootType": "Studio", "inventoryStatus": "AVAILABLE",
                 "earliestStartDate": "2026-08-29", "latestEndDate": "2027-08-24",
                 "priceTiers": [{"minWeeks": 20, "maxWeeks": 39, "weeklyPrice": 390}]},
            ],
        }

    def test_applies_location_date_stay_and_budget_without_returning_prices(self):
        result = eligible_residences(self.snapshot, {
            "city": "London", "nearbyPlaceKeyword": "UCL", "maxTravelMinutes": 25,
            "startDateFrom": "2026-09-01", "startDateTo": "2026-09-30",
            "stayWeeks": 26, "maxWeeklyPrice": 450,
        })
        self.assertEqual(["Near House"], result)

    def test_rejects_unsupported_stay_and_sold_out_by_default(self):
        self.snapshot["offers"][0]["inventoryStatus"] = "SOLD_OUT"
        result = eligible_residences(self.snapshot, {
            "city": "London", "startDateFrom": "2026-09-01",
            "startDateTo": "2026-09-30", "stayWeeks": 2,
        })
        self.assertEqual([], result)


if __name__ == "__main__":
    unittest.main()
