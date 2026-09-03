"""Lapis ekstraksi: gambar -> dict mentah -> ``MatchInput`` bertipe.

Urutan pengekstrak (turun otomatis kalau yang di atas tidak tersedia):

1. ``ClaudeVisionExtractor`` — akurat pada tata letak kartu statistik bandar
   yang berantakan, dan satu-satunya yang bisa membedakan kolom "babak 1" dari
   "pertandingan penuh" dengan andal.
2. ``TesseractExtractor`` — offline, jauh lebih lemah, hasilnya ditandai
   keandalan rendah.
3. Manual — pengguna mengisi sendiri lewat UI atau CLI.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Tuple

from ..models import Field, MatchInput, Provenance, TeamStats
from .normalize import normalize_team_name, parse_number
from .schema import EXTRACTION_SCHEMA, SYSTEM_PROMPT

__all__ = [
    "EXTRACTION_SCHEMA",
    "SYSTEM_PROMPT",
    "build_match_input",
    "extract_from_images",
    "available_extractors",
]

#: Medan numerik ``TeamStats`` yang bisa datang langsung dari ekstraksi.
_TEAM_FIELDS = (
    "matches_sampled",
    "corners_for_1h",
    "corners_against_1h",
    "corners_for_1h_venue",
    "corners_against_1h_venue",
    "corners_for_ft",
    "corners_against_ft",
    "corners_for_ft_venue",
    "corners_against_ft_venue",
    "shots_ft",
    "shots_on_target_ft",
    "dangerous_attacks_ft",
    "possession_pct",
)


def _team_from_dict(raw: Optional[Dict[str, Any]], prov: Provenance, conf: float) -> TeamStats:
    raw = raw or {}
    team = TeamStats(name=normalize_team_name(raw.get("name")))
    for key in _TEAM_FIELDS:
        val = parse_number(raw.get(key)) if not isinstance(raw.get(key), (int, float)) else raw.get(key)
        if val is None:
            continue
        setattr(team, key, Field(float(val), prov, confidence=conf, raw=str(raw.get(key))))
    history = raw.get("corner_1h_history") or []
    team.corner_1h_history = [int(x) for x in history if isinstance(x, (int, float))]
    return team


def build_match_input(
    raw: Dict[str, Any],
    provenance: Provenance = Provenance.VISION,
) -> Tuple[MatchInput, List[str]]:
    """Ubah dict hasil ekstraksi menjadi ``MatchInput``.

    Mengembalikan juga daftar catatan (angka tak terbaca, kontradiksi) yang
    harus ditampilkan ke pengguna apa adanya — jangan pernah ditelan diam-diam.
    """
    conf = float(raw.get("confidence") or 1.0)
    conf = max(0.0, min(1.0, conf))

    match = MatchInput(
        home=_team_from_dict(raw.get("home"), provenance, conf),
        away=_team_from_dict(raw.get("away"), provenance, conf),
        league=raw.get("league"),
        kickoff=raw.get("kickoff"),
    )

    base = parse_number(raw.get("league_corners_1h_per_team"))
    if base is not None:
        match.league_corners_1h_per_team = Field(float(base), provenance, confidence=conf)

    odds_raw = raw.get("odds") or {}
    if isinstance(odds_raw, dict):
        for line_key, sides in odds_raw.items():
            line_val = parse_number(line_key)
            if line_val is None or not isinstance(sides, dict):
                continue
            parsed = {
                side: float(v)
                for side in ("over", "under")
                if (v := parse_number(sides.get(side))) is not None
            }
            if parsed:
                match.odds[float(line_val)] = parsed

    h2h = raw.get("h2h_corner_1h") or []
    match.h2h_corner_1h = [int(x) for x in h2h if isinstance(x, (int, float))]

    notes: List[str] = list(raw.get("unreadable") or [])
    if raw.get("notes"):
        notes.append(str(raw["notes"]))
    if conf < 0.8:
        notes.append(
            f"Keyakinan pembacaan gambar hanya {conf:.0%}. Periksa ulang angka penting sebelum dipakai."
        )
    return match, notes


def available_extractors() -> Dict[str, bool]:
    """Pengekstrak mana yang bisa dipakai di lingkungan saat ini."""
    from .claude_vision import ClaudeVisionExtractor
    from .tesseract_ocr import TesseractExtractor

    return {
        "claude_vision": ClaudeVisionExtractor().available,
        "tesseract": TesseractExtractor().available,
    }


def extract_from_images(
    image_paths: Sequence[str],
    *,
    hint: Optional[str] = None,
    prefer: str = "auto",
) -> Tuple[MatchInput, List[str], str]:
    """Ekstrak dari gambar dengan penurunan bertingkat otomatis.

    Kembalikan ``(match_input, catatan, nama_pengekstrak_yang_dipakai)``.
    """
    from .claude_vision import ClaudeVisionExtractor, VisionUnavailable
    from .tesseract_ocr import OcrUnavailable, TesseractExtractor

    errors: List[str] = []

    if prefer in ("auto", "claude"):
        vision = ClaudeVisionExtractor()
        if vision.available:
            try:
                raw = vision.extract(image_paths, hint=hint)
                match, notes = build_match_input(raw, Provenance.VISION)
                return match, notes, "claude_vision"
            except (VisionUnavailable, ValueError) as exc:
                errors.append(f"claude_vision: {exc}")
        else:
            errors.append("claude_vision: SDK atau kredensial tidak tersedia")

    if prefer in ("auto", "tesseract"):
        ocr = TesseractExtractor()
        if ocr.available:
            try:
                raw = ocr.extract(image_paths, hint=hint)
                match, notes = build_match_input(raw, Provenance.OCR)
                notes.insert(0, "Memakai OCR lokal (cadangan) — keandalan lebih rendah dari Claude vision.")
                return match, notes, "tesseract"
            except (OcrUnavailable, ValueError) as exc:
                errors.append(f"tesseract: {exc}")
        else:
            errors.append("tesseract: pytesseract/Pillow atau biner tesseract tidak terpasang")

    raise RuntimeError(
        "Tidak ada pengekstrak gambar yang tersedia. Rincian:\n  - " + "\n  - ".join(errors)
    )
