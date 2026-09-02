import os
import sys
import unittest
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import text

backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

sqlite_path = backend_dir / "test_stats.sqlite3"
if sqlite_path.exists():
    sqlite_path.unlink()
os.environ["RECEPTIONQR_DATABASE_URL"] = f"sqlite:///{sqlite_path.as_posix()}"

import main


class StatsListTests(unittest.TestCase):
    def setUp(self):
        main.initialize_database()
        with main.db_transaction() as connection:
            connection.execute(text("DELETE FROM qr_codes"))
            connection.execute(text("DELETE FROM scan_logs"))

    def test_stats_returns_valid_scanned_players_only(self):
        client = TestClient(main.app)

        first = client.post("/api/qr/issue", json={
            "player_id": "player_01",
            "points": 50,
            "reward_name": "Nagroda 1",
            "match_id": "match_01",
        })
        second = client.post("/api/qr/issue", json={
            "player_id": "player_02",
            "points": 30,
            "reward_name": "Nagroda 2",
            "match_id": "match_02",
        })

        first_code = first.json()["code"]
        second_code = second.json()["code"]

        client.post("/api/qr/redeem", json={"code": first_code, "staff_id": "recepcja_1"})
        client.post("/api/qr/redeem", json={"code": first_code, "staff_id": "recepcja_1"})
        client.post("/api/qr/redeem", json={"code": second_code, "staff_id": "recepcja_1"})

        response = client.get("/api/stats")
        self.assertEqual(response.status_code, 200)
        payload = response.json()

        self.assertIn("scanned_players", payload)
        self.assertNotIn("total_scan_attempts", payload)
        self.assertNotIn("duplicate_scan_attempts", payload)
        self.assertNotIn("invalid_scan_attempts", payload)
        self.assertNotIn("redeemed_points_total", payload)

        players = payload["scanned_players"]
        self.assertEqual([entry["rank"] for entry in players], [1, 2])
        self.assertEqual([entry["player_id"] for entry in players], ["player_01", "player_02"])
        self.assertEqual([entry["points"] for entry in players], [50, 30])
        self.assertEqual(len(players), 2)


if __name__ == "__main__":
    unittest.main()
