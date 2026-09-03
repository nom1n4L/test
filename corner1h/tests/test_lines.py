"""Dukungan banyak garis, gerbang sensitivitas bentuk sebaran, dan nilai harapan.

Pindah dari satu garis (4,5) ke tujuh garis membuka satu risiko baru yang tidak
ada sebelumnya: garis di tepi sebaran jauh lebih ditentukan oleh *asumsi* bentuk
ekor daripada oleh data. Berkas ini menguji bahwa risiko itu benar-benar
dijaga, bukan sekadar disebut di dokumentasi.
"""

import unittest

from corner1h.calibration import Calibrator
from corner1h.distributions import prob_at_least
from corner1h.engine import OFFERED_LINES, CornerEngine, EngineConfig, count_for_line
from corner1h.models import Decision, Field, MatchInput, Provenance, TeamStats


def team(name, cf, ca, n, hist=None, prov=Provenance.MANUAL):
    t = TeamStats(name=name)
    t.matches_sampled = Field(n, prov)
    t.corners_for_1h = Field(cf, prov)
    t.corners_against_1h = Field(ca, prov)
    if hist:
        t.corner_1h_history = list(hist)
    return t


FITTED = Calibrator(a=1.0, b=0.0, fitted=True, n_samples=3000)


def cfg(**kw):
    c = EngineConfig(calibrator=FITTED)
    for k, v in kw.items():
        setattr(c, k, v)
    return c


#: Riwayat corner 1H yang cukup panjang untuk memberi VMR empiris.
HIST_H = [2, 1, 3, 2, 0, 4, 2, 1, 3, 2, 1, 2]
HIST_A = [1, 2, 2, 0, 3, 1, 2, 2, 1, 3, 2, 1]


class TestLineArithmetic(unittest.TestCase):
    def test_count_for_line(self):
        """Over 4,5 berarti >= 5; Over 1,5 berarti >= 2."""
        self.assertEqual(count_for_line(1.5), 2)
        self.assertEqual(count_for_line(4.5), 5)
        self.assertEqual(count_for_line(7.5), 8)

    def test_line_is_carried_into_the_verdict(self):
        for line in OFFERED_LINES:
            v = CornerEngine(cfg(line=line)).predict(
                MatchInput(home=team("A", 2.0, 2.0, 20), away=team("B", 2.0, 2.0, 20))
            )
            self.assertEqual(v.line, line)
            self.assertEqual(v.to_dict()["market"], f"1H corner Over/Under {line}")

    def test_decision_label_includes_the_line(self):
        self.assertEqual(Decision.PICK_UNDER.label(5.5), "PICK UNDER 5.5")
        self.assertEqual(Decision.PICK_OVER.label(2.5), "PICK OVER 2.5")
        self.assertEqual(Decision.SKIP.label(4.5), "SKIP MATCH")

    def test_probability_falls_monotonically_as_the_line_rises(self):
        """Sifat sebaran: makin tinggi garisnya, makin kecil P(over)."""
        eng_probs = []
        for line in OFFERED_LINES:
            v = CornerEngine(cfg(line=line)).predict(
                MatchInput(home=team("A", 2.45, 2.45, 25), away=team("B", 2.45, 2.45, 25))
            )
            eng_probs.append(v.calibrated_probability_over)
        for earlier, later in zip(eng_probs, eng_probs[1:]):
            self.assertGreater(earlier, later)


class TestScan(unittest.TestCase):
    def test_scan_covers_every_offered_line(self):
        match = MatchInput(home=team("A", 2.1, 1.5, 24), away=team("B", 1.6, 1.8, 24))
        verdicts = CornerEngine(cfg()).scan_lines(match)
        self.assertEqual(sorted(v.line for v in verdicts), sorted(OFFERED_LINES))

    def test_scan_without_odds_is_ordered_by_confidence(self):
        match = MatchInput(home=team("A", 2.1, 1.5, 24), away=team("B", 1.6, 1.8, 24))
        verdicts = CornerEngine(cfg()).scan_lines(match)
        confs = [v.confidence for v in verdicts]
        self.assertEqual(confs, sorted(confs, reverse=True))

    def test_scan_without_odds_structurally_favours_the_furthest_line(self):
        """Ini keterbatasan yang harus tetap terlihat, bukan bug yang ditutup:
        tanpa harga, menyisir garis selalu mendarat di garis terjauh dari mu —
        paling aman, dan bayarannya paling kecil."""
        match = MatchInput(home=team("A", 1.6, 1.4, 24), away=team("B", 1.5, 1.6, 24))
        verdicts = CornerEngine(cfg()).scan_lines(match)
        self.assertEqual(verdicts[0].line, max(OFFERED_LINES))

    def test_odds_reorder_the_scan_by_value(self):
        match = MatchInput(
            home=team("A", 2.1, 1.5, 24, HIST_H), away=team("B", 1.6, 1.8, 24, HIST_A)
        )
        # Harga khas: garis terjauh dibayar sangat murah.
        match.odds = {
            3.5: {"over": 1.85, "under": 1.95},
            5.5: {"over": 4.60, "under": 1.18},
            7.5: {"over": 14.0, "under": 1.03},
        }
        verdicts = CornerEngine(cfg()).scan_lines(match)
        evs = [v.expected_value for v in verdicts if v.expected_value is not None]
        self.assertEqual(evs, sorted(evs, reverse=True))
        # Yang paling aman bukan lagi yang teratas.
        self.assertNotEqual(verdicts[0].line, 7.5)

    def test_expected_value_arithmetic(self):
        match = MatchInput(
            home=team("A", 2.1, 1.5, 24, HIST_H), away=team("B", 1.6, 1.8, 24, HIST_A)
        )
        match.odds = {5.5: {"over": 4.60, "under": 1.18}}
        v = CornerEngine(cfg(line=5.5)).predict(match)
        p_under = 1.0 - v.calibrated_probability_over
        self.assertAlmostEqual(v.price, 1.18, places=6)
        self.assertAlmostEqual(v.expected_value, p_under * 1.18 - 1.0, places=9)

    def test_odds_absent_leaves_value_unset(self):
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 2.0, 2.0, 20), away=team("B", 2.0, 2.0, 20))
        )
        self.assertIsNone(v.price)
        self.assertIsNone(v.expected_value)


class TestVmrSensitivityGate(unittest.TestCase):
    """Penjaga yang khusus dibutuhkan begitu garis pinggir ikut dinilai."""

    def test_swing_is_reported_for_every_line(self):
        match = MatchInput(home=team("A", 2.1, 1.5, 24), away=team("B", 1.6, 1.8, 24))
        for v in CornerEngine(cfg()).scan_lines(match):
            self.assertIn("vmr_swing", v.weights)
            self.assertGreaterEqual(v.weights["vmr_swing"], 0.0)

    def test_high_sensitivity_without_measured_vmr_blocks_pick(self):
        """Garis yang jawabannya ditentukan tebakan bentuk sebaran tidak boleh
        menghasilkan PICK selama VMR-nya belum diukur dari riwayat."""
        match = MatchInput(home=team("A", 2.0, 1.9, 30), away=team("B", 1.8, 2.0, 30))
        blocked = 0
        for v in CornerEngine(cfg(max_vmr_swing=0.02)).scan_lines(match):
            if v.weights["vmr_swing"] > 0.02:
                self.assertNotIn(v.decision, (Decision.PICK_OVER, Decision.PICK_UNDER))
                self.assertTrue(any("bentuk sebaran" in g for g in v.weights["gates"]))
                blocked += 1
        self.assertGreater(blocked, 0, "ambang uji terlalu longgar untuk menguji apa pun")

    def test_measured_vmr_opens_the_gate(self):
        """Riwayat corner per laga mengubah tebakan menjadi pengukuran, dan
        gerbang ini memang dirancang untuk terbuka karenanya."""
        bare = MatchInput(home=team("A", 1.7, 1.6, 30), away=team("B", 1.5, 1.7, 30))
        with_hist = MatchInput(
            home=team("A", 1.7, 1.6, 30, HIST_H), away=team("B", 1.5, 1.7, 30, HIST_A)
        )
        engine = CornerEngine(cfg(line=5.5, max_vmr_swing=0.01))

        v_bare = engine.predict(bare)
        v_hist = engine.predict(with_hist)
        self.assertTrue(any("bentuk sebaran" in g for g in v_bare.weights["gates"]))
        self.assertTrue(any("VMR diukur dari riwayat" in g for g in v_hist.weights["gates"]))

    def test_gate_prompt_asks_for_the_right_thing(self):
        v = CornerEngine(cfg(line=1.5, max_vmr_swing=0.01)).predict(
            MatchInput(home=team("A", 2.0, 1.9, 30), away=team("B", 1.8, 2.0, 30))
        )
        self.assertTrue(any("per laga" in p for p in v.prompts))


class TestThresholdHoldsAcrossLines(unittest.TestCase):
    def test_no_line_ever_picks_below_threshold(self):
        """Sifat inti yang tidak boleh melemah karena garisnya bertambah."""
        eng = CornerEngine(cfg())
        checked = 0
        for cf_h in (0.8, 1.5, 2.45, 3.5, 5.0):
            for cf_a in (0.8, 1.5, 2.45, 3.5, 5.0):
                for n in (4, 10, 25):
                    match = MatchInput(
                        home=team("A", cf_h, 2.0, n, HIST_H),
                        away=team("B", cf_a, 2.0, n, HIST_A),
                    )
                    for v in eng.scan_lines(match):
                        checked += 1
                        if v.decision in (Decision.PICK_OVER, Decision.PICK_UNDER):
                            self.assertGreaterEqual(v.confidence, v.threshold)
        self.assertGreater(checked, 400)

    def test_unfitted_calibration_still_blocks_every_line(self):
        eng = CornerEngine(EngineConfig())
        match = MatchInput(
            home=team("A", 1.6, 1.4, 30, HIST_H), away=team("B", 1.5, 1.6, 30, HIST_A)
        )
        for v in eng.scan_lines(match):
            self.assertNotIn(v.decision, (Decision.PICK_OVER, Decision.PICK_UNDER))

    def test_implausible_input_blocks_every_line(self):
        """Angka pertandingan penuh yang salah tempat harus menutup SEMUA garis,
        bukan cuma 4,5 — kalau tidak, menyisir garis jadi jalan memutar."""
        eng = CornerEngine(cfg())
        match = MatchInput(home=team("A", 6.0, 5.5, 30), away=team("B", 5.5, 6.0, 30))
        for v in eng.scan_lines(match):
            self.assertIs(v.decision, Decision.SKIP)


if __name__ == "__main__":
    unittest.main()
