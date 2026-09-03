"""Kalibrasi Platt + tabel reliabilitas.

Masalah yang dipecahkan file ini adalah masalah yang sudah tercatat di README
repositori ini: model corner pernah mengklaim 82% dan realisasinya 53%. Sebuah
probabilitas mentah dari sebaran binomial negatif belum tentu *jujur* — ia hanya
sejujur asumsi yang membentuknya.

Kalibrasi Platt memasang satu regresi logistik satu-variabel di atas probabilitas
mentah:

    p_kalibrasi = sigmoid(A * logit(p_mentah) + B)

A < 1 berarti model terlalu percaya diri dan klaimnya ditarik ke tengah; A = 1,
B = 0 berarti identitas (belum dikalibrasi). Koefisien **harus** dipasang dari
data yang ditahan (held-out), bukan data yang dipakai membangun model.

``reliability_table`` adalah alat ujinya: ia menjawab "kalau model bilang 80%,
berapa kali sebenarnya kejadian itu terjadi?" — satu-satunya pertanyaan yang
menentukan apakah ambang 85% boleh dipercaya.
"""

from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

__all__ = ["Calibrator", "reliability_table", "brier_score", "log_loss"]

_EPS = 1e-6


def _logit(p: float) -> float:
    p = min(1.0 - _EPS, max(_EPS, p))
    return math.log(p / (1.0 - p))


def _sigmoid(x: float) -> float:
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


@dataclass
class Calibrator:
    """Kalibrasi Platt satu-variabel.

    Default-nya identitas, jadi mesin tetap berjalan sebelum ada data backtest —
    tetapi ``fitted`` bernilai ``False``, dan mesin memakai bendera itu untuk
    menolak menerbitkan PICK dalam mode ketat (lihat ``EngineConfig``).
    """

    a: float = 1.0
    b: float = 0.0
    fitted: bool = False
    n_samples: int = 0
    note: str = "identitas (belum dikalibrasi)"

    def apply(self, p: float) -> float:
        if not self.fitted and self.a == 1.0 and self.b == 0.0:
            return min(1.0 - _EPS, max(_EPS, p))
        return _sigmoid(self.a * _logit(p) + self.b)

    # ------------------------------------------------------------------ fit

    @classmethod
    def fit(
        cls,
        probs: Sequence[float],
        outcomes: Sequence[int],
        *,
        iters: int = 4000,
        lr: float = 0.05,
    ) -> "Calibrator":
        """Pasang A dan B dengan gradient descent pada log-loss.

        ``probs`` adalah P(over) mentah, ``outcomes`` adalah 1 jika corner 1H
        benar-benar > 4.5 dan 0 kalau tidak.
        """
        if len(probs) != len(outcomes):
            raise ValueError("panjang probs dan outcomes harus sama")
        if not probs:
            raise ValueError("butuh minimal satu sampel untuk kalibrasi")

        xs = [_logit(p) for p in probs]
        ys = [float(y) for y in outcomes]
        n = float(len(xs))

        # Titik awal identitas; regularisasi lembut menahan A dari meledak
        # ketika sampelnya sedikit.
        a, b = 1.0, 0.0
        l2 = 1e-3
        for _ in range(iters):
            ga = gb = 0.0
            for x, y in zip(xs, ys):
                pred = _sigmoid(a * x + b)
                err = pred - y
                ga += err * x
                gb += err
            ga = ga / n + l2 * (a - 1.0)
            gb = gb / n + l2 * b
            a -= lr * ga
            b -= lr * gb

        return cls(
            a=a,
            b=b,
            fitted=True,
            n_samples=len(probs),
            note=f"dipasang pada {len(probs)} sampel held-out",
        )

    # --------------------------------------------------------------- io

    def to_dict(self) -> Dict[str, object]:
        return {
            "a": self.a,
            "b": self.b,
            "fitted": self.fitted,
            "n_samples": self.n_samples,
            "note": self.note,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, object]) -> "Calibrator":
        return cls(
            a=float(d.get("a", 1.0)),
            b=float(d.get("b", 0.0)),
            fitted=bool(d.get("fitted", False)),
            n_samples=int(d.get("n_samples", 0)),
            note=str(d.get("note", "")),
        )

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(self.to_dict(), fh, indent=2)

    @classmethod
    def load(cls, path: Optional[str]) -> "Calibrator":
        """Muat koefisien dari berkas. Kalau tidak ada, kembalikan identitas."""
        if not path or not os.path.exists(path):
            return cls()
        try:
            with open(path, encoding="utf-8") as fh:
                return cls.from_dict(json.load(fh))
        except (OSError, ValueError, KeyError):
            return cls()


# ------------------------------------------------------------------ diagnostik


def reliability_table(
    probs: Iterable[float],
    outcomes: Iterable[int],
    bins: Sequence[Tuple[float, float]] = (
        (0.50, 0.60),
        (0.60, 0.70),
        (0.70, 0.80),
        (0.80, 0.85),
        (0.85, 0.90),
        (0.90, 1.01),
    ),
) -> List[Dict[str, float]]:
    """Bandingkan klaim model dengan kenyataan, per pita keyakinan.

    Probabilitas dilipat ke sisi yang diklaim: kalau model bilang 30% over, itu
    sama dengan klaim 70% under, dan yang diuji adalah klaim 70% itu.
    """
    rows: List[Dict[str, float]] = []
    folded: List[Tuple[float, int]] = []
    for p, y in zip(probs, outcomes):
        if p >= 0.5:
            folded.append((p, int(y)))
        else:
            folded.append((1.0 - p, 1 - int(y)))

    for lo, hi in bins:
        chunk = [(p, y) for p, y in folded if lo <= p < hi]
        if not chunk:
            rows.append({"lo": lo, "hi": hi, "n": 0, "claimed": 0.0, "actual": 0.0, "gap": 0.0})
            continue
        claimed = sum(p for p, _ in chunk) / len(chunk)
        actual = sum(y for _, y in chunk) / len(chunk)
        rows.append(
            {
                "lo": lo,
                "hi": hi,
                "n": len(chunk),
                "claimed": round(claimed, 4),
                "actual": round(actual, 4),
                "gap": round(actual - claimed, 4),
            }
        )
    return rows


def brier_score(probs: Iterable[float], outcomes: Iterable[int]) -> float:
    pairs = list(zip(probs, outcomes))
    if not pairs:
        return float("nan")
    return sum((p - y) ** 2 for p, y in pairs) / len(pairs)


def log_loss(probs: Iterable[float], outcomes: Iterable[int]) -> float:
    pairs = list(zip(probs, outcomes))
    if not pairs:
        return float("nan")
    total = 0.0
    for p, y in pairs:
        p = min(1.0 - _EPS, max(_EPS, p))
        total += -(y * math.log(p) + (1 - y) * math.log(1 - p))
    return total / len(pairs)
