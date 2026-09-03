"""Perekat: gambar dan/atau angka manual -> data lengkap -> vonis.

Satu fungsi ``analyse`` yang dipakai bersama oleh API web dan CLI, supaya
keduanya tidak pernah berbeda perilaku.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence

from .engine import CornerEngine, EngineConfig
from .fetch import Resolver, ResolutionReport
from .models import MatchInput, Provenance, Verdict

__all__ = ["AnalysisResult", "analyse"]


@dataclass
class AnalysisResult:
    verdict: Verdict
    #: Masukan yang benar-benar dipakai — dibutuhkan untuk menyisir garis lain
    #: tanpa mengulang ekstraksi dan pengambilan data.
    match_input: Optional[MatchInput] = None
    extractor: Optional[str] = None
    extraction_notes: List[str] = field(default_factory=list)
    resolution: Optional[ResolutionReport] = None

    def to_dict(self) -> Dict[str, Any]:
        out = self.verdict.to_dict()
        out["extractor"] = self.extractor
        out["extraction_notes"] = self.extraction_notes
        out["resolution"] = self.resolution.to_dict() if self.resolution else None
        # Gabungkan semua permintaan ke pengguna jadi satu daftar tanpa duplikat.
        prompts: List[str] = list(out.get("prompts") or [])
        if self.resolution:
            for p in self.resolution.prompts:
                if p not in prompts:
                    prompts.append(p)
        out["prompts"] = prompts
        return out


def analyse(
    *,
    images: Sequence[str] = (),
    manual: Optional[MatchInput] = None,
    hint: Optional[str] = None,
    autofetch: bool = True,
    config: Optional[EngineConfig] = None,
    prefer_extractor: str = "auto",
) -> AnalysisResult:
    """Jalankan pipeline lengkap.

    Urutan penggabungan sengaja begini: angka manual selalu menang atas
    screenshot, dan screenshot selalu menang atas sumber otomatis.
    """
    match: Optional[MatchInput] = None
    extractor: Optional[str] = None
    notes: List[str] = []

    if images:
        from .extract import extract_from_images

        try:
            match, notes, extractor = extract_from_images(images, hint=hint, prefer=prefer_extractor)
        except RuntimeError as exc:
            notes.append(str(exc))

    if manual is not None:
        match = _merge(manual, match)

    if match is None:
        match = MatchInput()
        notes.append("Tidak ada gambar maupun input manual — tidak ada yang bisa dinilai.")

    resolution = None
    if autofetch:
        resolution = Resolver().resolve(match)

    verdict = CornerEngine(config).predict(match)
    return AnalysisResult(
        verdict=verdict,
        match_input=match,
        extractor=extractor,
        extraction_notes=notes,
        resolution=resolution,
    )


def _merge(primary: MatchInput, secondary: Optional[MatchInput]) -> MatchInput:
    """Gabung dua ``MatchInput``; ``primary`` menang di setiap medan."""
    if secondary is None:
        return primary
    for side in ("home", "away"):
        p_team = getattr(primary, side)
        s_team = getattr(secondary, side)
        if not p_team.name and s_team.name:
            p_team.name = s_team.name
        for attr, val in s_team.__dict__.items():
            if val is None or attr in ("name", "corner_1h_history"):
                continue
            if getattr(p_team, attr, None) is None:
                setattr(p_team, attr, val)
        if not p_team.corner_1h_history and s_team.corner_1h_history:
            p_team.corner_1h_history = list(s_team.corner_1h_history)
    for attr in ("league", "kickoff", "league_corners_1h_per_team", "league_1h_share"):
        if getattr(primary, attr, None) is None:
            setattr(primary, attr, getattr(secondary, attr, None))
    if not primary.h2h_corner_1h and secondary.h2h_corner_1h:
        primary.h2h_corner_1h = list(secondary.h2h_corner_1h)
    return primary
