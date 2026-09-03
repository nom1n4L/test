"""Sifat matematis sebaran — kalau ini salah, semua angka di atasnya salah."""

import math
import unittest

from corner1h.distributions import (
    negbin_cdf,
    negbin_pmf,
    poisson_pmf,
    pmf_table,
    prob_at_least,
)


class TestDistributions(unittest.TestCase):
    def test_pmf_sums_to_one(self):
        for mean in (1.0, 3.0, 5.0, 9.0):
            total = sum(negbin_pmf(k, mean, 1.3) for k in range(0, 120))
            self.assertAlmostEqual(total, 1.0, places=6, msg=f"mean={mean}")

    def test_mean_and_variance_match_parameters(self):
        mean, vmr = 4.8, 1.35
        ks = range(0, 200)
        pmf = [negbin_pmf(k, mean, vmr) for k in ks]
        emp_mean = sum(k * p for k, p in zip(ks, pmf))
        emp_var = sum((k - emp_mean) ** 2 * p for k, p in zip(ks, pmf))
        self.assertAlmostEqual(emp_mean, mean, places=5)
        self.assertAlmostEqual(emp_var / emp_mean, vmr, places=5)

    def test_collapses_to_poisson_when_vmr_is_one(self):
        for k in range(0, 12):
            self.assertAlmostEqual(negbin_pmf(k, 4.5, 1.0), poisson_pmf(k, 4.5), places=9)

    def test_overdispersion_widens_tails(self):
        """VMR lebih besar harus membuat ekor lebih tebal — inilah alasan kita
        memakai binomial negatif, jadi sifat ini harus dijaga test."""
        narrow = prob_at_least(9, 4.9, 1.05)
        wide = prob_at_least(9, 4.9, 1.60)
        self.assertGreater(wide, narrow)

    def test_monotone_in_mean(self):
        prev = -1.0
        for mean in [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]:
            p = prob_at_least(5, mean, 1.25)
            self.assertGreater(p, prev)
            prev = p

    def test_cdf_and_sf_are_complements(self):
        for mean in (2.2, 5.0, 8.1):
            self.assertAlmostEqual(negbin_cdf(4, mean, 1.25) + prob_at_least(5, mean, 1.25), 1.0, places=9)

    def test_probabilities_stay_in_range(self):
        for mean in (0.0, 0.1, 15.0):
            p = prob_at_least(5, mean, 1.25)
            self.assertGreaterEqual(p, 0.0)
            self.assertLessEqual(p, 1.0)

    def test_zero_mean_puts_all_mass_at_zero(self):
        self.assertEqual(negbin_pmf(0, 0.0, 1.3), 1.0)
        self.assertEqual(negbin_pmf(1, 0.0, 1.3), 0.0)

    def test_pmf_table_length(self):
        self.assertEqual(len(pmf_table(5.0, 1.25, max_k=12)), 13)

    def test_line_45_sits_near_league_average(self):
        """Temuan penting yang layak dikunci test: garis 4,5 hampir persis di
        rata-rata liga, sehingga probabilitasnya mendekati lemparan koin.
        Kalau default patokan liga diubah, test ini akan memberi tahu."""
        league_total = 2.45 * 2
        p = prob_at_least(5, league_total, 1.25)
        self.assertTrue(0.45 < p < 0.60, f"p={p}")


if __name__ == "__main__":
    unittest.main()
