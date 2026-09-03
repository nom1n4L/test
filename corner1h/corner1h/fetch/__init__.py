"""Resolver: isi celah data secara otomatis, lalu minta sisanya ke pengguna.

Ini implementasi dua persyaratan yang saling menyambung:

* *Autonomous data fetching* — kalau screenshot kurang lengkap, cari sendiri.
* *Human-in-the-loop backup* — kalau pencarian otomatis gagal, tanya dengan
  jelas apa yang kurang.

Yang penting, keduanya tidak pernah menaikkan keandalan secara diam-diam. Angka
dari sumber otomatis ditandai ``Provenance.API``; kalau ia hanya proksi
pertandingan penuh untuk pasar 1H, mesin tetap memperlakukannya sebagai
``DERIVED`` saat memilih sumber, dan gerbang PICK tetap tertutup.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from ..models import MatchInput, Provenance
from .sources import DEFAULT_SOURCES, Source, apply_result

__all__ = ["Resolver", "ResolutionReport", "missing_field_prompts"]


@dataclass
class ResolutionReport:
    """Apa yang berhasil dan gagal diisi otomatis."""

    filled: Dict[str, List[str]] = field(default_factory=dict)
    source_notes: List[str] = field(default_factory=list)
    failures: List[str] = field(default_factory=list)
    prompts: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, object]:
        return {
            "filled": self.filled,
            "source_notes": self.source_notes,
            "failures": self.failures,
            "prompts": self.prompts,
        }


#: Medan wajib untuk sebuah penilaian yang layak, dengan penjelasan bahasa manusia.
REQUIRED_FIELDS: Dict[str, str] = {
    "corners_for_1h": "rata-rata corner BABAK PERTAMA yang dibuat, per laga",
    "corners_against_1h": "rata-rata corner BABAK PERTAMA yang dikebobolan, per laga",
    "matches_sampled": "jumlah laga yang menjadi dasar rata-rata di atas",
}

OPTIONAL_FIELDS: Dict[str, str] = {
    "corners_for_1h_venue": "corner 1H dibuat, khusus kandang/tandang (menaikkan ketepatan)",
    "corners_against_1h_venue": "corner 1H dikebobolan, khusus kandang/tandang",
    "shots_ft": "rata-rata tembakan per laga (sinyal tempo)",
    "dangerous_attacks_ft": "rata-rata serangan berbahaya per laga (sinyal tempo)",
    "corner_1h_history": "daftar corner 1H per laga (memberi varians empiris, bukan hanya rata-rata)",
}


@dataclass
class Resolver:
    """Isi ``MatchInput`` yang belum lengkap dari sumber otomatis."""

    sources: Sequence[Source] = tuple(DEFAULT_SOURCES)
    enabled: bool = True

    def resolve(self, match: MatchInput) -> ResolutionReport:
        report = ResolutionReport()
        if not self.enabled:
            report.failures.append("pengambilan otomatis dimatikan")
            report.prompts = missing_field_prompts(match)
            return report

        for side, team in (("home", match.home), ("away", match.away)):
            if not team.name:
                report.failures.append(f"{side}: nama tim tidak diketahui, pencarian otomatis dilewati")
                continue
            if self._is_complete(team):
                continue

            for source in self.sources:
                try:
                    if not source.available():
                        continue
                    result = source.lookup(team.name, venue=side, league_hint=match.league)
                except Exception as exc:  # sumber pihak ketiga tidak boleh menjatuhkan aplikasi
                    report.failures.append(f"{source.name} gagal untuk {team.name}: {exc}")
                    continue

                if result is None:
                    continue
                filled = apply_result(team, result)
                if filled:
                    report.filled.setdefault(side, []).extend(filled)
                    report.source_notes.extend(f"{team.name}: {n}" for n in result.notes)
                if not result.has_native_1h:
                    report.source_notes.append(
                        f"{team.name}: sumber {source.name} tidak memisahkan babak — angkanya hanya "
                        "bisa dipakai sebagai proksi, tidak cukup untuk membuka PICK."
                    )
                if self._is_complete(team):
                    break
            else:
                report.failures.append(
                    f"{team.name}: tidak ada sumber otomatis yang punya corner 1H asli"
                )

        report.prompts = missing_field_prompts(match)
        return report

    @staticmethod
    def _is_complete(team) -> bool:
        return all(getattr(team, f) is not None for f in REQUIRED_FIELDS)


def missing_field_prompts(match: MatchInput) -> List[str]:
    """Pesan human-in-the-loop yang menyebut persis apa yang kurang.

    Format pesannya sengaja menyebut nama tim dan nama medan dalam bahasa
    manusia, bukan nama atribut Python, karena yang membacanya adalah pengguna.
    """
    prompts: List[str] = []
    for side, team, label in (("home", match.home, "tuan rumah"), ("away", match.away, "tamu")):
        name = team.name or f"tim {label}"
        missing = [desc for f, desc in REQUIRED_FIELDS.items() if getattr(team, f) is None]
        if missing:
            prompts.append(
                f"Data untuk {name} ({label}) belum lengkap: {', '.join(missing)}. "
                "Kirim screenshot tambahan atau ketik nilainya manual."
            )
        derived_only = (
            team.corners_for_1h is None
            and team.corners_for_ft is not None
        )
        if derived_only:
            prompts.append(
                f"{name}: yang tersedia baru corner PERTANDINGAN PENUH. Untuk boleh PICK, "
                "dibutuhkan corner BABAK PERTAMA asli — kirim screenshot statistik babak 1."
            )
        weak = [
            desc
            for f, desc in OPTIONAL_FIELDS.items()
            if f != "corner_1h_history" and getattr(team, f, None) is None
        ]
        if not team.corner_1h_history:
            weak.append(OPTIONAL_FIELDS["corner_1h_history"])
        if weak:
            prompts.append(f"{name}: opsional tapi menaikkan ketepatan — {', '.join(weak)}.")
    return prompts
