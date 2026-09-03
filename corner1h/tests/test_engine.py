"""Perilaku mesin: shrinkage, ketidakpastian, dan proyeksi."""

import unittest

from corner1h.calibration import Calibrator
from corner1h.engine import CornerEngine, EngineConfig
from corner1h.models import Decision, Field, MatchInput, Provenance, TeamStats


def team(name, cf, ca, n, prov=Provenance.MANUAL, **kw):
    t = TeamStats(name=name)
    t.matches_sampled = Field(n, prov)
    t.corners_for_1h = Field(cf, prov)
    t.corners_against_1h = Field(ca, prov)
    for k, v in kw.items():
        setattr(t, k, Field(v, prov))
    return t


def fitted_cfg(**kw):
    cfg = EngineConfig(calibrator=Calibrator(a=1.0, b=0.0, fitted=True, n_samples=2000))
    for k, v in kw.items():
        setattr(cfg, k, v)
    return cfg


class TestEngine(unittest.TestCase):
    def test_thin_sample_pulls_toward_league_average(self):
        """Dua tim dengan angka identik ekstrem, beda hanya ukuran sampel.
        Yang sampelnya tipis harus menghasilkan proyeksi lebih dekat ke liga."""
        eng = CornerEngine(fitted_cfg())
        thin = eng.predict(MatchInput(home=team("A", 4.5, 4.5, 2), away=team("B", 4.5, 4.5, 2)))
        thick = eng.predict(MatchInput(home=team("A", 4.5, 4.5, 30), away=team("B", 4.5, 4.5, 30)))
        league = 2.45 * 2
        self.assertLess(
            abs(thin.expected_corners_1h - league),
            abs(thick.expected_corners_1h - league),
        )

    def test_thin_sample_lowers_confidence(self):
        eng = CornerEngine(fitted_cfg())
        thin = eng.predict(MatchInput(home=team("A", 1.0, 1.0, 3), away=team("B", 1.0, 1.0, 3)))
        thick = eng.predict(MatchInput(home=team("A", 1.0, 1.0, 30), away=team("B", 1.0, 1.0, 30)))
        self.assertLess(thin.confidence, thick.confidence)

    def test_home_advantage_favours_home_projection(self):
        eng = CornerEngine(fitted_cfg())
        v = eng.predict(MatchInput(home=team("A", 2.45, 2.45, 20), away=team("B", 2.45, 2.45, 20)))
        self.assertGreater(v.weights["home"]["mu"], v.weights["away"]["mu"])

    def test_tempo_adjustment_is_capped(self):
        """Tembakan absurd tidak boleh menggeser proyeksi lebih dari batas."""
        eng = CornerEngine(fitted_cfg())
        calm = eng.predict(MatchInput(home=team("A", 2.45, 2.45, 20), away=team("B", 2.45, 2.45, 20)))
        wild = eng.predict(
            MatchInput(
                home=team("A", 2.45, 2.45, 20, shots_ft=99.0, dangerous_attacks_ft=400.0),
                away=team("B", 2.45, 2.45, 20),
            )
        )
        cap = EngineConfig().tempo_max_adjust
        # Faktor tempo itu sendiri harus terpotong tepat di batas.
        self.assertAlmostEqual(wild.weights["home"]["tempo_factor"], 1.0 + cap, places=9)
        self.assertAlmostEqual(calm.weights["home"]["tempo_factor"], 1.0, places=9)
        # mu ikut naik paling banyak sebesar batas itu (toleransi pembulatan 3 desimal).
        ratio = wild.weights["home"]["mu"] / calm.weights["home"]["mu"]
        self.assertLessEqual(ratio, 1.0 + cap + 1e-3)

    def test_derived_from_full_match_is_flagged(self):
        t_home = TeamStats(name="A")
        t_home.matches_sampled = Field(20, Provenance.API)
        t_home.corners_for_ft = Field(6.0, Provenance.API)
        t_home.corners_against_ft = Field(5.0, Provenance.API)
        t_away = TeamStats(name="B")
        t_away.matches_sampled = Field(20, Provenance.API)
        t_away.corners_for_ft = Field(5.0, Provenance.API)
        t_away.corners_against_ft = Field(6.0, Provenance.API)

        v = CornerEngine(fitted_cfg()).predict(MatchInput(home=t_home, away=t_away))
        self.assertEqual(v.weights["home"]["provenance"], "derived")
        self.assertIn("diturunkan", " ".join(v.quality.weak_fields))

    def test_venue_stats_preferred_over_overall(self):
        t = team("A", 2.0, 2.0, 20)
        t.corners_for_1h_venue = Field(4.0, Provenance.MANUAL)
        t.corners_against_1h_venue = Field(4.0, Provenance.MANUAL)
        v = CornerEngine(fitted_cfg()).predict(MatchInput(home=t, away=team("B", 2.45, 2.45, 20)))
        self.assertTrue(v.weights["home"]["venue_specific"])
        self.assertAlmostEqual(v.weights["home"]["corners_for_1h"], 4.0, places=6)

    def test_empirical_vmr_used_when_history_present(self):
        h = team("A", 3.0, 2.0, 20)
        a = team("B", 2.0, 3.0, 20)
        # Riwayat sangat menyebar -> VMR empiris tinggi.
        h.corner_1h_history = [0, 8, 1, 9, 0, 7, 2, 8]
        a.corner_1h_history = [9, 0, 8, 1, 7, 0, 9, 1]
        v = CornerEngine(fitted_cfg()).predict(MatchInput(home=h, away=a))
        self.assertEqual(v.weights["vmr_source"], "empiris dari riwayat")
        self.assertGreater(v.vmr, EngineConfig().vmr)

    def test_wider_vmr_lowers_confidence(self):
        base = MatchInput(home=team("A", 1.1, 1.1, 25), away=team("B", 1.1, 1.1, 25))
        narrow = CornerEngine(fitted_cfg(vmr=1.05)).predict(base)
        wide = CornerEngine(fitted_cfg(vmr=1.85)).predict(base)
        self.assertGreater(narrow.confidence, wide.confidence)

    def test_low_reliability_source_lowers_confidence(self):
        manual = MatchInput(
            home=team("A", 1.0, 1.0, 25, prov=Provenance.MANUAL),
            away=team("B", 1.0, 1.0, 25, prov=Provenance.MANUAL),
        )
        ocr = MatchInput(
            home=team("A", 1.0, 1.0, 25, prov=Provenance.OCR),
            away=team("B", 1.0, 1.0, 25, prov=Provenance.OCR),
        )
        eng = CornerEngine(fitted_cfg())
        self.assertGreater(eng.predict(manual).confidence, eng.predict(ocr).confidence)

    def test_mu_is_clamped_to_plausible_range(self):
        eng = CornerEngine(fitted_cfg())
        v = eng.predict(MatchInput(home=team("A", 40.0, 40.0, 30), away=team("B", 40.0, 40.0, 30)))
        lo, hi = EngineConfig().mu_bounds
        self.assertLessEqual(v.expected_corners_1h, hi)
        self.assertGreaterEqual(v.expected_corners_1h, lo)

    def test_missing_required_data_asks_instead_of_guessing(self):
        v = CornerEngine(fitted_cfg()).predict(MatchInput(home=TeamStats(name="A"), away=TeamStats(name="B")))
        self.assertIs(v.decision, Decision.NEED_DATA)
        self.assertTrue(v.prompts)


if __name__ == "__main__":
    unittest.main()
