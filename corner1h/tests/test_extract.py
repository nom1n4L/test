"""Ekstraksi: parsing angka, normalisasi nama, dan disiplin anti-karangan."""

import unittest

from corner1h.extract import build_match_input
from corner1h.extract.normalize import (
    find_labelled_number,
    normalize_team_name,
    parse_number,
    team_key,
)
from corner1h.extract.schema import EXTRACTION_SCHEMA
from corner1h.models import Provenance


class TestNormalize(unittest.TestCase):
    def test_parse_decimal_styles(self):
        self.assertEqual(parse_number("3.20"), 3.2)
        self.assertEqual(parse_number("3,20"), 3.2)
        self.assertEqual(parse_number("  5 "), 5.0)
        self.assertEqual(parse_number("1.250"), 1250.0)  # pemisah ribuan

    def test_parse_returns_none_instead_of_guessing(self):
        for junk in (None, "", "   ", "n/a", "—", "tidak ada"):
            self.assertIsNone(parse_number(junk), junk)

    def test_common_ocr_digit_confusions(self):
        self.assertEqual(parse_number("l2"), 12.0)
        self.assertEqual(parse_number("O.5"), 0.5)

    def test_team_key_matches_variants(self):
        self.assertEqual(team_key("Man Utd"), team_key("Manchester United FC"))
        self.assertEqual(team_key("Arsenal"), team_key("  arsenal  "))
        self.assertEqual(team_key("Spurs"), team_key("Tottenham Hotspur"))
        self.assertNotEqual(team_key("Arsenal"), team_key("Aston Villa"))

    def test_distinguishing_words_are_never_stripped(self):
        """Regresi: membuang 'united'/'city' pernah membuat dua klub Manchester
        punya kunci yang sama, yang akan menggabungkan statistik keduanya tanpa
        satu pun pesan galat."""
        self.assertNotEqual(team_key("Manchester United"), team_key("Manchester City"))
        self.assertNotEqual(team_key("Man Utd"), team_key("Man City"))
        self.assertNotEqual(team_key("Bristol City"), team_key("Bristol Rovers"))

    def test_corporate_suffixes_are_stripped(self):
        self.assertEqual(team_key("Ajax"), team_key("AFC Ajax"))
        self.assertEqual(team_key("Milan"), team_key("AC Milan"))

    def test_normalize_collapses_whitespace(self):
        self.assertEqual(normalize_team_name("  Real   Madrid \n"), "Real Madrid")

    def test_find_labelled_number(self):
        lines = ["Total Shots 14.5", "Corners for 5.2", "Possession 61%"]
        self.assertEqual(find_labelled_number(lines, "corners for"), 5.2)
        self.assertEqual(find_labelled_number(lines, "possession"), 61.0)
        self.assertIsNone(find_labelled_number(lines, "offside"))


class TestSchema(unittest.TestCase):
    def test_every_numeric_field_allows_null(self):
        """Ini pertahanan utama melawan angka karangan: model penglihatan harus
        selalu punya jalan keluar yang sah ketika angkanya tidak terlihat."""
        for side in ("home", "away"):
            props = EXTRACTION_SCHEMA["properties"][side]["properties"]
            for name, spec in props.items():
                types = spec.get("type")
                self.assertIsInstance(types, list, f"{side}.{name} harus mengizinkan null")
                self.assertIn("null", types, f"{side}.{name} harus mengizinkan null")

    def test_no_additional_properties(self):
        self.assertFalse(EXTRACTION_SCHEMA["additionalProperties"])
        self.assertFalse(EXTRACTION_SCHEMA["properties"]["home"]["additionalProperties"])


class TestBuildMatchInput(unittest.TestCase):
    def test_nulls_stay_missing(self):
        raw = {
            "home": {"name": "A", "corners_for_1h": 3.0, "corners_against_1h": None,
                     "matches_sampled": 10},
            "away": {"name": "B"},
            "confidence": 1.0,
        }
        match, _ = build_match_input(raw)
        self.assertIsNotNone(match.home.corners_for_1h)
        self.assertIsNone(match.home.corners_against_1h)
        self.assertIsNone(match.away.corners_for_1h)

    def test_low_confidence_produces_a_warning(self):
        raw = {"home": {"name": "A"}, "away": {"name": "B"}, "confidence": 0.55}
        _, notes = build_match_input(raw)
        self.assertTrue(any("55%" in n for n in notes))

    def test_unreadable_notes_are_surfaced(self):
        raw = {"home": {}, "away": {}, "confidence": 1.0,
               "unreadable": ["kolom corner tamu terpotong"]}
        _, notes = build_match_input(raw)
        self.assertIn("kolom corner tamu terpotong", notes)

    def test_provenance_is_recorded(self):
        raw = {"home": {"name": "A", "corners_for_1h": 3.0}, "away": {}, "confidence": 0.9}
        match, _ = build_match_input(raw, Provenance.VISION)
        self.assertIs(match.home.corners_for_1h.provenance, Provenance.VISION)
        self.assertAlmostEqual(match.home.corners_for_1h.confidence, 0.9)

    def test_history_is_parsed(self):
        raw = {"home": {"corner_1h_history": [5, 3, 6]}, "away": {}, "confidence": 1.0}
        match, _ = build_match_input(raw)
        self.assertEqual(match.home.corner_1h_history, [5, 3, 6])


if __name__ == "__main__":
    unittest.main()
