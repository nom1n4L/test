"""Sebaran diskrit untuk jumlah sepak pojok — tanpa numpy/scipy.

Kenapa binomial negatif, bukan Poisson?

Poisson mengunci varians sama dengan rata-rata (VMR = 1). Sepak pojok tidak
begitu: satu serangan bertubi-tubi menghasilkan rentetan corner beruntun, jadi
sebarannya *overdispersed* — VMR empiris berkisar 1,15–1,40 untuk corner babak
pertama. Memakai Poisson pada data seperti ini membuat ekor sebaran terlalu
tipis, sehingga model mengaku 85% padahal kenyataannya jauh lebih dekat ke 60%.
Itu persis mode kegagalan yang tercatat di README repositori ini (model corner
mengklaim 82%, realisasinya 53%).

Parameterisasi yang dipakai: rata-rata ``mean`` dan rasio varians-terhadap-mean
``vmr`` (variance-to-mean ratio), karena ``vmr`` yang bisa diukur langsung dari
data historis. Konversi ke bentuk baku (r, p):

    var = mean * vmr
    r   = mean^2 / (var - mean) = mean / (vmr - 1)
    p   = r / (r + mean)
"""

from __future__ import annotations

import math
from typing import List

__all__ = [
    "MIN_VMR",
    "negbin_pmf",
    "negbin_cdf",
    "negbin_sf",
    "poisson_pmf",
    "poisson_cdf",
    "prob_at_least",
    "pmf_table",
]

#: Di bawah nilai ini binomial negatif runtuh menjadi Poisson (r -> tak hingga).
MIN_VMR = 1.0 + 1e-9


def poisson_pmf(k: int, mean: float) -> float:
    """P(X = k) untuk X ~ Poisson(mean)."""
    if k < 0:
        return 0.0
    if mean <= 0:
        return 1.0 if k == 0 else 0.0
    return math.exp(-mean + k * math.log(mean) - math.lgamma(k + 1))


def poisson_cdf(k: int, mean: float) -> float:
    """P(X <= k) untuk X ~ Poisson(mean)."""
    if k < 0:
        return 0.0
    return sum(poisson_pmf(i, mean) for i in range(k + 1))


def negbin_pmf(k: int, mean: float, vmr: float) -> float:
    """P(X = k) untuk binomial negatif dengan rata-rata ``mean`` dan VMR ``vmr``.

    Kalau ``vmr`` <= 1 sebarannya tidak terdefinisi (varians < mean), jadi kita
    turun ke Poisson — batas alami binomial negatif saat dispersi menghilang.
    """
    if k < 0:
        return 0.0
    if mean <= 0:
        return 1.0 if k == 0 else 0.0
    if vmr <= MIN_VMR:
        return poisson_pmf(k, mean)

    r = mean / (vmr - 1.0)
    p = r / (r + mean)
    # log C(k+r-1, k) + r*log(p) + k*log(1-p)
    log_coef = math.lgamma(k + r) - math.lgamma(r) - math.lgamma(k + 1)
    return math.exp(log_coef + r * math.log(p) + k * math.log1p(-p))


def negbin_cdf(k: int, mean: float, vmr: float) -> float:
    """P(X <= k)."""
    if k < 0:
        return 0.0
    return sum(negbin_pmf(i, mean, vmr) for i in range(k + 1))


def negbin_sf(k: int, mean: float, vmr: float) -> float:
    """P(X > k) — fungsi survival, dihitung sebagai 1 - CDF(k).

    Untuk k kecil (kita selalu memakai k = 4) selisih presisinya dapat
    diabaikan, jadi tidak perlu penjumlahan ekor yang mahal.
    """
    return max(0.0, min(1.0, 1.0 - negbin_cdf(k, mean, vmr)))


def prob_at_least(n: int, mean: float, vmr: float) -> float:
    """P(X >= n). Untuk pasar Over 4.5 corner 1H, ``n`` = 5."""
    return negbin_sf(n - 1, mean, vmr)


def pmf_table(mean: float, vmr: float, max_k: int = 15) -> List[float]:
    """Tabel PMF 0..max_k — dipakai UI untuk menggambar sebarannya."""
    return [negbin_pmf(k, mean, vmr) for k in range(max_k + 1)]
