"""Kalibrasi: apakah klaim model cocok dengan kenyataan."""

import random
import unittest

from corner1h.calibration import Calibrator, brier_score, log_loss, reliability_table


class TestCalibration(unittest.TestCase):
    def setUp(self):
        random.seed(20260903)

    def test_identity_by_default(self):
        c = Calibrator()
        self.assertFalse(c.fitted)
        for p in (0.05, 0.5, 0.87, 0.99):
            self.assertAlmostEqual(c.apply(p), p, places=5)

    def test_fit_corrects_overconfidence(self):
        """Model yang melebih-lebihkan 2,2x harus dikoreksi ke arah tengah."""
        probs, outcomes = [], []
        for _ in range(4000):
            true_p = random.uniform(0.35, 0.65)
            claimed = min(0.99, max(0.01, 0.5 + (true_p - 0.5) * 2.2))
            probs.append(claimed)
            outcomes.append(1 if random.random() < true_p else 0)

        cal = Calibrator.fit(probs, outcomes)
        self.assertTrue(cal.fitted)
        self.assertLess(cal.a, 1.0, "A harus < 1 untuk model yang kelewat pede")

        after = [cal.apply(p) for p in probs]
        self.assertLess(brier_score(after, outcomes), brier_score(probs, outcomes))
        self.assertLess(log_loss(after, outcomes), log_loss(probs, outcomes))
        # Setelah kalibrasi tidak ada lagi klaim ekstrem yang tidak didukung.
        self.assertLess(max(after), 0.85)

    def test_fit_leaves_honest_model_roughly_alone(self):
        probs, outcomes = [], []
        for _ in range(4000):
            p = random.uniform(0.2, 0.8)
            probs.append(p)
            outcomes.append(1 if random.random() < p else 0)
        cal = Calibrator.fit(probs, outcomes)
        self.assertGreater(cal.a, 0.75)
        self.assertLess(cal.a, 1.3)

    def test_reliability_table_detects_the_gap(self):
        """Ini alat yang dipakai README repo ini untuk memvonis market corner:
        'model bilang 73,7%, kenyataan 54,5%'. Harus terdeteksi."""
        probs = [0.80] * 1000
        outcomes = [1 if i < 530 else 0 for i in range(1000)]
        rows = [r for r in reliability_table(probs, outcomes) if r["n"]]
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0]["claimed"], 0.80, places=6)
        self.assertAlmostEqual(rows[0]["actual"], 0.53, places=6)
        self.assertLess(rows[0]["gap"], -0.2)

    def test_reliability_table_folds_both_sides(self):
        """Klaim 20% over sama dengan klaim 80% under; keduanya harus diuji."""
        probs = [0.2] * 100
        outcomes = [0] * 80 + [1] * 20
        rows = [r for r in reliability_table(probs, outcomes) if r["n"]]
        self.assertEqual(rows[0]["n"], 100)
        self.assertAlmostEqual(rows[0]["claimed"], 0.8, places=6)
        self.assertAlmostEqual(rows[0]["actual"], 0.8, places=6)

    def test_roundtrip_serialisation(self):
        cal = Calibrator(a=0.63, b=-0.12, fitted=True, n_samples=1234, note="uji")
        self.assertEqual(Calibrator.from_dict(cal.to_dict()).to_dict(), cal.to_dict())

    def test_load_missing_file_returns_identity(self):
        cal = Calibrator.load("/tidak/ada/berkas.json")
        self.assertFalse(cal.fitted)

    def test_fit_rejects_mismatched_lengths(self):
        with self.assertRaises(ValueError):
            Calibrator.fit([0.5, 0.6], [1])


if __name__ == "__main__":
    unittest.main()
