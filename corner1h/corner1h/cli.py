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


if __name__ == "__main__":
    raise SystemExit(main())
