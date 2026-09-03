"""Antarmuka baris perintah — jalur tanpa dependensi apa pun.

Contoh:

    # dari screenshot
    python -m corner1h.cli --image kartu1.png --image kartu2.png

    # dari angka manual
    python -m corner1h.cli --manual contoh.json

    # lihat angka mentah tanpa gerbang keras (mode belajar)
    python -m corner1h.cli --manual contoh.json --no-strict
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import List, Optional

from .calibration import Calibrator
from .engine import EngineConfig
from .explain import render_text
from .extract import build_match_input
from .models import Provenance
from .pipeline import analyse

DEFAULT_CALIBRATION = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "calibration.json"
)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="corner1h",
        description="Prediktor sepak pojok babak pertama — pasar Over/Under 4.5.",
    )
    p.add_argument("--image", action="append", default=[], metavar="PATH",
                   help="screenshot statistik (boleh diulang untuk beberapa gambar)")
    p.add_argument("--manual", metavar="JSON",
                   help="berkas JSON berisi angka manual (bentuknya sama dengan skema ekstraksi)")
    p.add_argument("--hint", help="konteks tambahan, mis. 'tim kiri adalah tuan rumah'")
    p.add_argument("--threshold", type=float, default=85.0, help="ambang PICK dalam persen (default 85)")
    p.add_argument("--line", type=float, default=4.5, metavar="X.5",
                   help="garis yang dinilai (default 4.5)")
    p.add_argument("--scan", action="store_true",
                   help="sisir semua garis yang ditawarkan, urut dari yang terbaik")
    p.add_argument("--no-autofetch", action="store_true", help="matikan pengambilan data otomatis")
    p.add_argument("--no-strict", action="store_true",
                   help="longgarkan gerbang keras — untuk melihat angka mentah, bukan untuk taruhan")
    p.add_argument("--calibration", default=DEFAULT_CALIBRATION, help="berkas koefisien kalibrasi")
    p.add_argument("--json", action="store_true", help="keluarkan JSON, bukan teks")
    p.add_argument("--extractor", choices=["auto", "claude", "tesseract"], default="auto")
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if not args.image and not args.manual:
        build_parser().print_help()
        return 2

    cfg = EngineConfig(calibrator=Calibrator.load(args.calibration))
    cfg.threshold = args.threshold
    cfg.line = args.line
    if args.no_strict:
        cfg.require_fitted_calibration = False
        cfg.require_native_1h = False
        cfg.min_matches_for_pick = 3

    manual = None
    if args.manual:
        try:
            with open(args.manual, encoding="utf-8") as fh:
                payload = json.load(fh)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"gagal membaca {args.manual}: {exc}", file=sys.stderr)
            return 1
        manual, _ = build_match_input(payload, Provenance.MANUAL)

    result = analyse(
        images=args.image,
        manual=manual,
        hint=args.hint,
        autofetch=not args.no_autofetch,
        config=cfg,
        prefer_extractor=args.extractor,
    )

    if args.scan:
        return _print_scan(result, cfg, args)

    if args.json:
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
    else:
        print(render_text(result.verdict))
        if result.extraction_notes:
            print("\n  CATATAN EKSTRAKSI")
            for n in result.extraction_notes:
                print(f"    • {n}")
        if result.resolution and result.resolution.source_notes:
            print("\n  SUMBER OTOMATIS")
            for n in result.resolution.source_notes:
                print(f"    • {n}")
    return 0


def _print_scan(result, cfg, args) -> int:
    """Cetak seluruh tangga garis, bukan hanya satu jawaban.

    Menyisir garis selalu memenangkan garis terjauh dari mu — di sana
    probabilitasnya mendekati 1 dan bayarannya mendekati nol. Karena itu
    tabelnya menampilkan semuanya, dan nilai harapan ikut dicetak begitu odds
    dimasukkan, supaya "paling aman" tidak tertukar dengan "paling bernilai".
    """
    from .engine import CornerEngine

    verdicts = CornerEngine(cfg).scan_lines(result.match_input)
    has_odds = any(v.expected_value is not None for v in verdicts)

    if args.json:
        print(json.dumps([v.to_dict() for v in verdicts], indent=2, ensure_ascii=False))
        return 0

    print(f"\n{'=' * 72}")
    print(f"  {verdicts[0].match} — sisir semua garis corner babak pertama")
    print(f"  Proyeksi {verdicts[0].expected_corners_1h:.2f} corner 1H "
          f"(VMR {verdicts[0].vmr:.2f}, {verdicts[0].weights['vmr_source']})")
    print("=" * 72)
    header = f"  {'garis':>6}{'keyakinan':>11}{'sensitivitas':>14}"
    if has_odds:
        header += f"{'harga':>8}{'nilai':>9}"
    print(header + "   vonis")
    print("  " + "-" * 68)
    for v in verdicts:
        row = f"  {v.line:>6}{v.confidence:>10.1f}%{v.weights['vmr_swing']:>13.1%}"
        if has_odds:
            price = f"{v.price:.2f}" if v.price else "—"
            ev = f"{v.expected_value:+.1%}" if v.expected_value is not None else "—"
            row += f"{price:>8}{ev:>9}"
        print(row + f"   {v.decision.label(v.line)}")

    picks = [v for v in verdicts if v.decision.value.startswith("PICK")]
    print()
    if not picks:
        print("  Tidak ada garis yang lolos. Itu jawaban yang sah, bukan kegagalan.")
    else:
        best = picks[0]
        print(f"  Terbaik: {best.decision.label(best.line)} pada {best.confidence:.1f}%")
        if not has_odds:
            print("  Tanpa odds, peringkat ini hanya soal keamanan — bukan nilai.")
            print("  Masukkan harga bandar lewat medan 'odds' untuk peringkat berbasis nilai.")
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
