"""Penjelasan tidak boleh bertentangan dengan angka yang dipakai mesin.

Repositori ini sudah memegang disiplin yang sama untuk model golnya; pasar
corner tidak boleh lebih longgar.
"""

import re
import unittest

from corner1h.calibration import Calibrator
from corner1h.engine import CornerEngine, EngineConfig
from corner1h.explain import render_text
from corner1h.models import Decision, Field, MatchInput, Provenance, TeamStats


def team(name, cf, ca, n):
    t = TeamStats(name=name)
    t.matches_sampled = Field(n, Provenance.MANUAL)
    t.corners_for_1h = Field(cf, Provenance.MANUAL)
    t.corners_against_1h = Field(ca, Provenance.MANUAL)
    return t


FITTED = EngineConfig(calibrator=Calibrator(a=1.0, b=0.0, fitted=True, n_samples=3000))


class TestExplain(unittest.TestCase):
    def test_reported_mu_matches_computed_mu(self):
        v = CornerEngine(FITTED).predict(
            MatchInput(home=team("A", 3.1, 2.2, 20), away=team("B", 2.0, 2.9, 20))
        )
        text = " ".join(v.reasoning)
        shown = re.search(r"= (\d+,\d+) total", text)
        self.assertIsNotNone(shown)
        self.assertAlmostEqual(
            float(shown.group(1).replace(",", ".")), round(v.expected_corners_1h, 2), places=2
        )

    def test_decision_line_matches_decision(self):
        for match, _ in (
            (MatchInput(home=team("A", 0.9, 0.8, 40), away=team("B", 0.7, 0.9, 40)), 1),
            (MatchInput(home=team("A", 2.45, 2.45, 20), away=team("B", 2.45, 2.45, 20)), 2),
        ):
            v = CornerEngine(FITTED).predict(match)
            decision_line = [r for r in v.reasoning if r.startswith("KEPUTUSAN")][0]
            if v.decision is Decision.PICK_UNDER:
                self.assertIn("PICK UNDER", decision_line)
            elif v.decision is Decision.PICK_OVER:
                self.assertIn("PICK OVER", decision_line)
            else:
                self.assertIn("SKIP", decision_line)

    def test_uncalibrated_state_is_stated_plainly(self):
        v = CornerEngine(EngineConfig()).predict(
            MatchInput(home=team("A", 1.0, 1.0, 30), away=team("B", 1.0, 1.0, 30))
        )
        text = " ".join(v.reasoning)
        self.assertIn("BELUM dipasang", text)

    def test_every_reasoning_line_is_labelled(self):
        v = CornerEngine(FITTED).predict(
            MatchInput(home=team("A", 3.0, 2.0, 20), away=team("B", 2.0, 3.0, 20))
        )
        allowed = ("FAKTA", "INTERPRETASI", "MODEL", "GERBANG", "KEPUTUSAN")
        for line in v.reasoning:
            self.assertTrue(line.startswith(allowed), f"tanpa label: {line[:60]}")

    def test_sample_size_in_text_matches_input(self):
        v = CornerEngine(FITTED).predict(
            MatchInput(home=team("A", 3.0, 2.0, 17), away=team("B", 2.0, 3.0, 23))
        )
        text = " ".join(v.reasoning)
        self.assertIn("dari 17 laga", text)
        self.assertIn("dari 23 laga", text)

    def test_render_text_does_not_crash_on_need_data(self):
        v = CornerEngine(FITTED).predict(MatchInput(home=TeamStats(name="A"), away=TeamStats(name="B")))
        out = render_text(v)
        self.assertIn("NEED DATA", out)
        self.assertIn("DIBUTUHKAN DARI ANDA", out)

    def test_confidence_in_text_matches_verdict(self):
        v = CornerEngine(FITTED).predict(
            MatchInput(home=team("A", 1.1, 1.0, 30), away=team("B", 0.9, 1.2, 30))
        )
        out = render_text(v)
        self.assertIn(f"{v.confidence:.1f}".replace(".", ","), out)


if __name__ == "__main__":
    unittest.main()
