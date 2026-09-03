"""Gerbang "PICK atau SKIP" — bagian paling penting di seluruh sistem.

Persyaratannya adalah presisi nol-kesalahan: lebih baik SKIP 9 dari 10 laga
asalkan yang di-PICK benar-benar terkunci. Test di berkas ini menyatakan hal itu
sebagai sifat yang bisa diperiksa mesin, bukan sekadar niat baik di dokumentasi.
"""

import unittest

from corner1h.calibration import Calibrator
from corner1h.engine import CornerEngine, EngineConfig
from corner1h.models import Decision, Field, MatchInput, Provenance, TeamStats


def team(name, cf, ca, n, prov=Provenance.MANUAL):
    t = TeamStats(name=name)
    t.matches_sampled = Field(n, prov)
    t.corners_for_1h = Field(cf, prov)
    t.corners_against_1h = Field(ca, prov)
    return t


FITTED = Calibrator(a=1.0, b=0.0, fitted=True, n_samples=3000)


def cfg(**kw):
    c = EngineConfig(calibrator=FITTED)
    for k, v in kw.items():
        setattr(c, k, v)
    return c


#: Sekumpulan pertandingan yang menyapu ruang masukan yang masuk akal.
def sweep():
    for cf_h in (0.4, 1.2, 2.45, 3.6, 5.0, 7.0):
        for cf_a in (0.4, 1.2, 2.45, 3.6, 5.0, 7.0):
            for ca in (0.6, 2.45, 4.5):
                for n in (1, 5, 8, 15, 40):
                    yield MatchInput(
                        home=team("A", cf_h, ca, n),
                        away=team("B", cf_a, ca, n),
                    )


class TestGate(unittest.TestCase):
    def test_never_picks_below_threshold(self):
        """Sifat inti: tidak ada satu pun masukan yang menghasilkan PICK
        sementara keyakinannya di bawah ambang. Disapu, bukan dicontohkan."""
        eng = CornerEngine(cfg())
        checked = 0
        for match in sweep():
            v = eng.predict(match)
            checked += 1
            if v.decision in (Decision.PICK_OVER, Decision.PICK_UNDER):
                self.assertGreaterEqual(v.confidence, v.threshold, msg=str(v.weights))
        self.assertGreater(checked, 100)

    def test_84_9_percent_is_a_skip(self):
        """Persyaratan menyebut angka ini secara khusus: 84% pun tetap SKIP."""
        eng = CornerEngine(cfg(threshold=85.0))
        v = eng.predict(MatchInput(home=team("A", 2.45, 2.45, 20), away=team("B", 2.45, 2.45, 20)))
        # Paksa keyakinan tepat di bawah ambang lewat ambang yang dinaikkan.
        eng2 = CornerEngine(cfg(threshold=v.confidence + 0.1))
        v2 = eng2.predict(MatchInput(home=team("A", 2.45, 2.45, 20), away=team("B", 2.45, 2.45, 20)))
        self.assertIs(v2.decision, Decision.SKIP)

    def test_unfitted_calibration_blocks_every_pick(self):
        """Tanpa kalibrasi yang dipasang pada data held-out, ambang 85% tidak
        punya arti empiris — jadi tidak boleh ada PICK sama sekali."""
        eng = CornerEngine(EngineConfig())  # kalibrator default = belum dipasang
        for match in sweep():
            v = eng.predict(match)
            self.assertNotIn(v.decision, (Decision.PICK_OVER, Decision.PICK_UNDER))

    def test_derived_1h_blocks_pick(self):
        t_home = TeamStats(name="A")
        t_home.matches_sampled = Field(40, Provenance.API)
        t_home.corners_for_ft = Field(1.0, Provenance.API)
        t_home.corners_against_ft = Field(1.0, Provenance.API)
        t_away = TeamStats(name="B")
        t_away.matches_sampled = Field(40, Provenance.API)
        t_away.corners_for_ft = Field(1.0, Provenance.API)
        t_away.corners_against_ft = Field(1.0, Provenance.API)

        v = CornerEngine(cfg()).predict(MatchInput(home=t_home, away=t_away))
        self.assertIs(v.decision, Decision.SKIP)
        self.assertTrue(any("diturunkan" in g for g in v.weights["gates"]))

    def test_small_sample_blocks_pick(self):
        """Angka ekstrem + sampel 2 laga tidak boleh pernah jadi PICK."""
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 0.2, 0.2, 2), away=team("B", 0.2, 0.2, 2))
        )
        self.assertIs(v.decision, Decision.SKIP)

    def test_missing_data_returns_need_data_not_a_guess(self):
        t = TeamStats(name="A")
        t.matches_sampled = Field(20, Provenance.MANUAL)
        v = CornerEngine(cfg()).predict(MatchInput(home=t, away=team("B", 2.0, 2.0, 20)))
        self.assertIs(v.decision, Decision.NEED_DATA)
        self.assertTrue(v.prompts, "harus ada permintaan eksplisit ke pengguna")

    def test_gate_reasons_are_always_recorded(self):
        eng = CornerEngine(cfg())
        for match in list(sweep())[:40]:
            v = eng.predict(match)
            self.assertTrue(v.weights["gates"], "setiap keputusan harus punya jejak gerbang")

    def test_pick_is_reachable_when_everything_is_ideal(self):
        """Gerbang harus ketat, tapi tidak mustahil — kalau tidak, sistemnya
        bukan konservatif melainkan rusak.

        Angka di sini sengaja dipilih dari wilayah yang benar-benar ada di sepak
        bola: dua tim bertempo rendah yang masing-masing membuat dan melepas
        sekitar 1,5-1,7 corner per babak pertama, dengan sampel 30 laga. Itu
        memberi proyeksi ~2,5 corner 1H — rendah, tapi bukan mustahil."""
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 1.7, 1.6, 30), away=team("B", 1.5, 1.7, 30))
        )
        self.assertIs(v.decision, Decision.PICK_UNDER)
        self.assertGreaterEqual(v.confidence, 85.0)

    def test_over_side_is_far_harder_to_reach_than_under(self):
        """Temuan struktural yang dikunci test: dengan garis 4,5 di sekitar
        rata-rata liga, sisi OVER butuh mu yang jauh lebih ekstrem daripada
        sisi UNDER untuk mencapai keyakinan yang sama."""
        eng = CornerEngine(cfg())
        # Kedua kasus berada di dalam rentang proyeksi yang wajar, jadi yang
        # dibandingkan murni bentuk sebarannya — bukan gerbang kelayakan.
        under = eng.predict(MatchInput(home=team("A", 1.7, 1.6, 30), away=team("B", 1.5, 1.7, 30)))
        over = eng.predict(MatchInput(home=team("A", 3.0, 3.0, 30), away=team("B", 3.0, 3.0, 30)))
        self.assertIs(under.decision, Decision.PICK_UNDER)
        # Sisi over, meski proyeksinya jauh lebih tinggi dari rata-rata liga,
        # tetap tidak sampai ke ambang: garis 4,5 duduk di sisi bawah sebaran.
        self.assertIs(over.decision, Decision.SKIP)
        self.assertLess(over.confidence, under.confidence)

    def test_threshold_is_respected_when_changed(self):
        match = MatchInput(home=team("A", 1.5, 1.4, 30), away=team("B", 1.3, 1.5, 30))
        strict = CornerEngine(cfg(threshold=99.0)).predict(match)
        loose = CornerEngine(cfg(threshold=55.0)).predict(match)
        self.assertIs(strict.decision, Decision.SKIP)
        self.assertIn(loose.decision, (Decision.PICK_OVER, Decision.PICK_UNDER))


if __name__ == "__main__":
    unittest.main()


class TestImplausibilityGate(unittest.TestCase):
    """Penjaga terhadap penyebab kesalahan yang paling mungkin: angka yang salah
    masuk, bukan pertandingan yang benar-benar ekstrem."""

    def test_absurdly_low_projection_is_blocked(self):
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 0.3, 0.3, 40), away=team("B", 0.3, 0.3, 40))
        )
        self.assertIs(v.decision, Decision.SKIP)
        self.assertTrue(any("di luar rentang wajar" in g for g in v.weights["gates"]))
        self.assertTrue(any("tidak masuk akal" in p for p in v.prompts))

    def test_full_match_numbers_pasted_into_1h_fields_are_caught(self):
        """Kesalahan paling lazim: angka corner pertandingan penuh (~5,5 per tim)
        diketik ke medan babak pertama. Harus tertangkap, bukan jadi PICK."""
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 6.0, 5.5, 30), away=team("B", 5.5, 6.0, 30))
        )
        self.assertIs(v.decision, Decision.SKIP)
        self.assertTrue(any("di luar rentang wajar" in g for g in v.weights["gates"]))

    def test_decimal_point_error_is_caught(self):
        """3,2 salah ketik jadi 32 — tanpa gerbang ini akan lolos jadi PICK OVER."""
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 32.0, 2.6, 30), away=team("B", 2.9, 28.0, 30))
        )
        self.assertIs(v.decision, Decision.SKIP)

    def test_plausible_low_scoring_match_still_allowed(self):
        """Gerbang tidak boleh terlalu galak: pasangan bertempo rendah yang nyata
        harus tetap bisa lolos."""
        v = CornerEngine(cfg()).predict(
            MatchInput(home=team("A", 1.7, 1.6, 30), away=team("B", 1.5, 1.7, 30))
        )
        self.assertIs(v.decision, Decision.PICK_UNDER)

    def test_no_pick_ever_lands_on_a_clamped_projection(self):
        """Sifat menyeluruh: tidak ada PICK yang berdiri di atas mu yang
        terpotong batas, karena angka terpotong berarti masukannya di luar
        jangkauan yang dipercaya."""
        eng = CornerEngine(cfg())
        lo, hi = EngineConfig().mu_bounds
        for match in sweep():
            v = eng.predict(match)
            if v.decision in (Decision.PICK_OVER, Decision.PICK_UNDER):
                self.assertNotAlmostEqual(v.expected_corners_1h, lo, places=6)
                self.assertNotAlmostEqual(v.expected_corners_1h, hi, places=6)
