#!/usr/bin/env python3
"""Backtest, kalibrasi, dan pengukuran kejujuran ambang.

Tanpa langkah ini, angka "85%" hanyalah keluaran sebuah rumus. Skrip ini yang
mengubahnya menjadi klaim yang bisa diperiksa, dengan menjawab satu pertanyaan:

    Dari semua laga yang pernah di-PICK model pada ambang ini, berapa persen
    yang benar-benar menang?

Disiplin yang dijaga:

* **Pemisahan latih/uji secara kronologis.** Kalibrator dipasang HANYA pada
  bagian awal data, lalu diukur pada bagian akhir yang belum pernah dilihat.
  Memasang dan mengukur pada data yang sama akan membuat hasilnya terlalu cerah.
* **Tidak ada kebocoran masa depan.** Setiap laga dinilai memakai rata-rata yang
  ada di berkas untuk laga itu; siapkan CSV Anda sedemikian rupa.

Format CSV yang diharapkan (satu baris per pertandingan, urut waktu):

    date,home,away,home_cf_1h,home_ca_1h,home_n,away_cf_1h,away_ca_1h,away_n,actual_1h_corners

Kolom opsional: ``home_shots``, ``away_shots``, ``league_base_1h``.

Pemakaian:

    python scripts/backtest.py --csv data/riwayat.csv --write
    python scripts/backtest.py --simulate 6000        # uji mesinnya sendiri
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import sys
from typing import List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from corner1h.calibration import Calibrator, brier_score, log_loss, reliability_table
from corner1h.distributions import negbin_pmf
from corner1h.engine import CornerEngine, EngineConfig
from corner1h.models import Decision, Field, MatchInput, Provenance, TeamStats

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
DEFAULT_OUT = os.path.join(DATA_DIR, "calibration.json")


# --------------------------------------------------------------------- data


def _team(name: str, cf: float, ca: float, n: float, shots: Optional[float]) -> TeamStats:
    t = TeamStats(name=name)
    t.matches_sampled = Field(float(n), Provenance.API)
    t.corners_for_1h = Field(float(cf), Provenance.API)
    t.corners_against_1h = Field(float(ca), Provenance.API)
    if shots is not None:
        t.shots_ft = Field(float(shots), Provenance.API)
    return t


def load_csv(path: str) -> List[Tuple[MatchInput, int]]:
    """Baca CSV riwayat menjadi pasangan (masukan, hasil sebenarnya)."""
    rows: List[Tuple[MatchInput, int]] = []
    with open(path, encoding="utf-8") as fh:
        for i, row in enumerate(csv.DictReader(fh), start=2):
            try:
                match = MatchInput(
                    home=_team(
                        row.get("home", "home"), row["home_cf_1h"], row["home_ca_1h"],
                        row["home_n"], row.get("home_shots") or None,
                    ),
                    away=_team(
                        row.get("away", "away"), row["away_cf_1h"], row["away_ca_1h"],
                        row["away_n"], row.get("away_shots") or None,
                    ),
                )
                if row.get("league_base_1h"):
                    match.league_corners_1h_per_team = Field(
                        float(row["league_base_1h"]), Provenance.API
                    )
                actual = int(float(row["actual_1h_corners"]))
            except (KeyError, ValueError) as exc:
                print(f"  baris {i} dilewati: {exc}", file=sys.stderr)
                continue
            rows.append((match, 1 if actual >= 5 else 0))
    return rows


def simulate(n: int, seed: int = 20260903) -> List[Tuple[MatchInput, int]]:
    """Hasilkan data sintetis dari proses pembangkit yang diketahui.

    PENTING: ini menguji apakah *mesinnya* bekerja — apakah kalibrasi memperbaiki
    keyakinan berlebih, apakah gerbang berperilaku benar. Ini BUKAN bukti akurasi
    pada pertandingan nyata, dan tidak boleh dikutip seolah-olah begitu. Angka
    dari mode ini selalu diberi label 'SINTETIS' di keluaran.
    """
    rng = random.Random(seed)
    out: List[Tuple[MatchInput, int]] = []
    for _ in range(n):
        # Kekuatan corner tim ditarik dari sebaran yang mirip liga nyata.
        atk_h, atk_a = rng.gauss(2.45, 0.55), rng.gauss(2.45, 0.55)
        dfn_h, dfn_a = rng.gauss(2.45, 0.45), rng.gauss(2.45, 0.45)
        atk_h, atk_a = max(0.5, atk_h), max(0.5, atk_a)
        dfn_h, dfn_a = max(0.5, dfn_h), max(0.5, dfn_a)
        n_matches = rng.choice([4, 6, 8, 10, 14, 20, 28])

        # Rata-rata yang TERLIHAT model adalah estimasi berderau dari nilai
        # sebenarnya — persis situasi nyata dengan sampel terbatas.
        noise = lambda v: max(0.1, v + rng.gauss(0, v / (n_matches ** 0.5) * 0.9))

        match = MatchInput(
            home=_team("H", noise(atk_h), noise(dfn_h), n_matches, None),
            away=_team("A", noise(atk_a), noise(dfn_a), n_matches, None),
        )

        # Hasil sebenarnya dibangkitkan dari mu yang sebenarnya, dengan
        # overdispersi yang lebih lebar daripada anggapan default model.
        true_mu = atk_h * (dfn_a / 2.45) * 1.06 + atk_a * (dfn_h / 2.45) / 1.06
        actual = _draw_negbin(rng, true_mu, vmr=1.32)
        out.append((match, 1 if actual >= 5 else 0))
    return out


def _draw_negbin(rng: random.Random, mean: float, vmr: float) -> int:
    """Sampel satu cacahan dari binomial negatif lewat CDF inversi."""
    u = rng.random()
    acc = 0.0
    for k in range(0, 60):
        acc += negbin_pmf(k, mean, vmr)
        if u <= acc:
            return k
    return 60


# ----------------------------------------------------------------- evaluasi


def raw_probability(engine: CornerEngine, match: MatchInput) -> float:
    """P(over) sebelum kalibrasi — inilah yang dipasangi kalibrator."""
    return engine.predict(match).raw_probability_over


def evaluate(
    rows: Sequence[Tuple[MatchInput, int]],
    calibrator: Calibrator,
    threshold: float,
    *,
    relax_gates: bool,
) -> dict:
    cfg = EngineConfig(calibrator=calibrator, threshold=threshold)
    if relax_gates:
        # Data backtest tidak punya provenance screenshot; longgarkan gerbang
        # yang memang tidak berlaku di sini, tapi JANGAN longgarkan ambangnya.
        # Gerbang provenance tidak berlaku di sini: baris CSV tidak punya
        # riwayat screenshot. Ambangnya sendiri TIDAK pernah dilonggarkan —
        # itu justru yang sedang diukur.
        cfg.require_fitted_calibration = calibrator.fitted
        cfg.require_native_1h = False
    engine = CornerEngine(cfg)

    probs: List[float] = []
    outcomes: List[int] = []
    picks = 0
    pick_hits = 0

    for match, actual in rows:
        v = engine.predict(match)
        probs.append(v.calibrated_probability_over)
        outcomes.append(actual)
        if v.decision is Decision.PICK_OVER:
            picks += 1
            pick_hits += actual
        elif v.decision is Decision.PICK_UNDER:
            picks += 1
            pick_hits += 1 - actual

    return {
        "n": len(rows),
        "picks": picks,
        "pick_rate": picks / len(rows) if rows else 0.0,
        "pick_hit_rate": pick_hits / picks if picks else float("nan"),
        "brier": brier_score(probs, outcomes),
        "log_loss": log_loss(probs, outcomes),
        "reliability": reliability_table(probs, outcomes),
        "base_rate_over": sum(outcomes) / len(outcomes) if outcomes else float("nan"),
    }


def print_report(title: str, res: dict, threshold: float) -> None:
    print(f"\n{title}")
    print("-" * len(title))
    print(f"  laga                : {res['n']}")
    print(f"  basis Over 4,5      : {res['base_rate_over']:.1%}")
    print(f"  Brier               : {res['brier']:.4f}")
    print(f"  Log loss            : {res['log_loss']:.4f}")
    label = f"PICK pada {threshold:.0f}%"
    print(f"  {label:<20}: {res['picks']} ({res['pick_rate']:.1%} dari semua laga)")
    if res["picks"]:
        print(f"  akurasi PICK        : {res['pick_hit_rate']:.1%}")
    else:
        print("  akurasi PICK        : — (tidak ada PICK; ini hasil yang sah)")

    print("\n  Tabel reliabilitas — 'model bilang X, kenyataan Y':")
    print(f"    {'pita':<14}{'n':>7}{'model':>10}{'nyata':>10}{'selisih':>10}   vonis")
    for r in res["reliability"]:
        if not r["n"]:
            continue
        gap = r["gap"]
        verdict = "jujur" if abs(gap) <= 0.05 else ("kelewat pede" if gap < 0 else "terlalu malu")
        print(
            f"    {r['lo']:.2f}-{r['hi']:.2f}   {r['n']:>7}{r['claimed']:>10.1%}"
            f"{r['actual']:>10.1%}{gap:>+10.1%}   {verdict}"
        )


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Backtest dan kalibrasi model corner 1H.")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--csv", help="berkas riwayat pertandingan")
    src.add_argument("--simulate", type=int, metavar="N",
                     help="hasilkan N laga sintetis untuk menguji mesinnya sendiri")
    p.add_argument("--threshold", type=float, default=85.0)
    p.add_argument("--train-frac", type=float, default=0.6,
                   help="porsi awal data untuk memasang kalibrator (sisanya untuk uji)")
    p.add_argument("--write", action="store_true", help="simpan koefisien ke data/calibration.json")
    p.add_argument("--out", default=DEFAULT_OUT)
    args = p.parse_args(argv)

    if args.csv:
        if not os.path.exists(args.csv):
            print(f"berkas tidak ditemukan: {args.csv}", file=sys.stderr)
            return 1
        rows = load_csv(args.csv)
        label = f"data nyata ({args.csv})"
        synthetic = False
    else:
        rows = simulate(args.simulate)
        label = "DATA SINTETIS — menguji mesin, BUKAN bukti akurasi nyata"
        synthetic = True

    if len(rows) < 200:
        print(f"hanya {len(rows)} laga — terlalu sedikit untuk kalibrasi yang berarti.",
              file=sys.stderr)
        if len(rows) < 30:
            return 1

    split = int(len(rows) * args.train_frac)
    train, test = rows[:split], rows[split:]
    print(f"\n{'=' * 72}\n  BACKTEST CORNER 1H — {label}\n{'=' * 72}")
    print(f"  latih: {len(train)} laga (awal)   uji: {len(test)} laga (akhir, belum pernah dilihat)")

    # Kalibrator dipasang HANYA pada bagian latih.
    base_engine = CornerEngine(EngineConfig(calibrator=Calibrator()))
    train_probs = [raw_probability(base_engine, m) for m, _ in train]
    train_outcomes = [y for _, y in train]
    calibrator = Calibrator.fit(train_probs, train_outcomes)
    print(f"\n  Kalibrator terpasang: A={calibrator.a:.4f}  B={calibrator.b:+.4f}  "
          f"({calibrator.n_samples} sampel latih)")
    if calibrator.a < 0.85:
        print("  -> A jauh di bawah 1: model mentah memang kelewat percaya diri, "
              "persis pola yang tercatat untuk market corner.")

    print_report("HASIL PADA BAGIAN UJI (yang menentukan)",
                 evaluate(test, calibrator, args.threshold, relax_gates=True), args.threshold)
    print_report("Sebagai pembanding — bagian latih (terlalu cerah, jangan dikutip)",
                 evaluate(train, calibrator, args.threshold, relax_gates=True), args.threshold)

    if args.write:
        if synthetic:
            print("\n  MENOLAK menulis: koefisien dari data sintetis tidak boleh dipakai "
                  "untuk pertandingan nyata. Jalankan ulang dengan --csv data nyata.")
            return 1
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        calibrator.save(args.out)
        print(f"\n  Koefisien ditulis ke {args.out}")
        print("  Mesin sekarang mengizinkan PICK (gerbang kalibrasi terbuka).")
    else:
        print("\n  (tidak ditulis — tambahkan --write untuk menyimpan koefisien)")

    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
