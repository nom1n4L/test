"""Tipe data inti: masukan pertandingan, provenance, dan bentuk keluaran.

Prinsip desain terpenting di file ini: **setiap angka membawa asal-usulnya.**

Sebuah rata-rata corner yang dibaca OCR dari screenshot, yang diambil dari API,
dan yang diketik manual punya keandalan berbeda. Model harus tahu bedanya,
karena syarat "PICK hanya kalau >= 85%" tidak bisa dipenuhi kalau angkanya
sendiri bisa saja salah baca. ``Field`` membungkus nilai + sumber + keyakinan
ekstraksi, dan mesin menghukum keyakinan akhir berdasarkan itu.
"""

from __future__ import annotations

import enum
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, Generic, List, Optional, TypeVar

T = TypeVar("T")

__all__ = [
    "Provenance",
    "Field",
    "TeamStats",
    "MatchInput",
    "DataQuality",
    "Decision",
    "Verdict",
]


class Provenance(str, enum.Enum):
    """Dari mana sebuah angka berasal. Urutannya = urutan keandalan."""

    MANUAL = "manual"          #: diketik pengguna — dianggap paling dipercaya
    API = "api"                #: dari sumber statistik terstruktur
    VISION = "vision"          #: dibaca Claude vision dari screenshot
    OCR = "ocr"                #: dibaca OCR lokal (tesseract)
    DERIVED = "derived"        #: diturunkan dari angka lain (mis. split 1H dari full-match)
    ASSUMED = "assumed"        #: default liga / anggapan model — paling lemah

    @property
    def reliability(self) -> float:
        """Bobot keandalan 0..1, dipakai sebagai penalti keyakinan."""
        return _RELIABILITY[self]


_RELIABILITY: Dict["Provenance", float] = {
    Provenance.MANUAL: 1.00,
    Provenance.API: 1.00,
    Provenance.VISION: 0.95,
    Provenance.OCR: 0.80,
    Provenance.DERIVED: 0.70,
    Provenance.ASSUMED: 0.40,
}


@dataclass
class Field(Generic[T]):
    """Satu nilai beserta asal-usul dan keyakinan pembacaannya."""

    value: T
    provenance: Provenance = Provenance.ASSUMED
    #: Keyakinan pengekstrak terhadap pembacaan ini (0..1). Untuk vision/OCR
    #: nilainya dilaporkan pengekstrak; untuk manual/API selalu 1.0.
    confidence: float = 1.0
    #: Label mentah yang terbaca di screenshot, berguna untuk audit.
    raw: Optional[str] = None

    @property
    def weight(self) -> float:
        """Keandalan efektif = keandalan sumber x keyakinan pembacaan."""
        return self.provenance.reliability * max(0.0, min(1.0, self.confidence))

    def to_dict(self) -> Dict[str, Any]:
        return {
            "value": self.value,
            "provenance": self.provenance.value,
            "confidence": round(self.confidence, 4),
            "raw": self.raw,
        }


def _f(value: Optional[float], prov: Provenance = Provenance.ASSUMED) -> Optional[Field]:
    """Pembungkus ringkas untuk membuat ``Field`` opsional."""
    return None if value is None else Field(value, prov)


@dataclass
class TeamStats:
    """Statistik satu tim. Semua medan opsional — mesin menurunkan keyakinan
    ketika medan penting tidak ada, alih-alih menebak diam-diam.

    Konvensi penamaan:

    * ``*_1h``   — khusus babak pertama (paling bernilai untuk pasar ini)
    * ``*_ft``   — pertandingan penuh
    * ``*_home`` / ``*_away`` — dipisah kandang/tandang

    Angka corner selalu **per pertandingan**, bukan total musim.
    """

    name: str = ""

    #: Jumlah pertandingan yang menjadi basis rata-rata di bawah ini.
    matches_sampled: Optional[Field] = None

    # --- corner babak pertama (sinyal utama) ---
    corners_for_1h: Optional[Field] = None
    corners_against_1h: Optional[Field] = None
    corners_for_1h_venue: Optional[Field] = None      #: kandang utk tuan rumah, tandang utk tamu
    corners_against_1h_venue: Optional[Field] = None

    # --- corner pertandingan penuh (cadangan, dipakai lewat split 1H) ---
    corners_for_ft: Optional[Field] = None
    corners_against_ft: Optional[Field] = None
    corners_for_ft_venue: Optional[Field] = None
    corners_against_ft_venue: Optional[Field] = None

    # --- sinyal tempo sekunder ---
    shots_ft: Optional[Field] = None
    shots_on_target_ft: Optional[Field] = None
    dangerous_attacks_ft: Optional[Field] = None
    possession_pct: Optional[Field] = None

    #: Riwayat corner 1H per laga, kalau screenshot memuat daftarnya.
    #: Sampel langsung jauh lebih berharga daripada rata-rata, karena
    #: memberi varians empiris, bukan hanya rata-rata.
    corner_1h_history: List[int] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"name": self.name, "corner_1h_history": list(self.corner_1h_history)}
        for key, val in self.__dict__.items():
            if isinstance(val, Field):
                out[key] = val.to_dict()
            elif val is None:
                out[key] = None
        return out


@dataclass
class MatchInput:
    """Satu pertandingan siap dinilai."""

    home: TeamStats = field(default_factory=TeamStats)
    away: TeamStats = field(default_factory=TeamStats)

    league: Optional[str] = None
    kickoff: Optional[str] = None

    #: Rata-rata corner 1H per tim di liga ini. Kalau tidak ada, mesin memakai
    #: default global dan menandai medan ini sebagai ASSUMED.
    league_corners_1h_per_team: Optional[Field] = None
    #: Porsi corner yang jatuh di babak pertama (0..1) di liga ini.
    league_1h_share: Optional[Field] = None

    #: Riwayat corner 1H head-to-head, kalau tersedia.
    h2h_corner_1h: List[int] = field(default_factory=list)

    #: Harga bandar per garis, kalau Anda memasukkannya:
    #: ``{4.5: {"over": 1.90, "under": 1.90}}``. Format desimal.
    #:
    #: Tanpa odds, menyisir garis selalu berujung di garis paling pinggir —
    #: keyakinan naik terus saat garis menjauh dari mu, sampai jawabannya
    #: menjadi benar tapi tak berguna. Odds mengubah pertanyaannya dari
    #: "mana yang paling aman" menjadi "mana yang harganya salah".
    odds: Dict[float, Dict[str, float]] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "home": self.home.to_dict(),
            "away": self.away.to_dict(),
            "league": self.league,
            "kickoff": self.kickoff,
            "league_corners_1h_per_team": (
                self.league_corners_1h_per_team.to_dict() if self.league_corners_1h_per_team else None
            ),
            "league_1h_share": self.league_1h_share.to_dict() if self.league_1h_share else None,
            "h2h_corner_1h": list(self.h2h_corner_1h),
            "odds": {str(k): v for k, v in self.odds.items()},
        }


@dataclass
class DataQuality:
    """Ringkasan kelengkapan data — dasar untuk penalti keyakinan dan untuk
    pesan human-in-the-loop ketika sesuatu harus diminta ke pengguna."""

    #: Medan wajib yang tidak ada sama sekali.
    missing_required: List[str] = field(default_factory=list)
    #: Medan yang membantu tapi tidak wajib.
    missing_optional: List[str] = field(default_factory=list)
    #: Medan yang ada tapi lemah asal-usulnya (DERIVED/ASSUMED).
    weak_fields: List[str] = field(default_factory=list)
    #: Rata-rata bobot keandalan dari medan yang benar-benar dipakai.
    mean_reliability: float = 1.0
    #: Jumlah laga terkecil di antara kedua tim.
    min_sample: int = 0

    @property
    def is_sufficient(self) -> bool:
        return not self.missing_required

    def to_dict(self) -> Dict[str, Any]:
        return {
            **asdict(self),
            "mean_reliability": round(self.mean_reliability, 4),
            "is_sufficient": self.is_sufficient,
        }


class Decision(str, enum.Enum):
    """Vonis. Label garisnya ditambahkan saat dirender, bukan dipatri di sini,
    supaya satu enum melayani semua garis."""

    PICK_OVER = "PICK OVER"
    PICK_UNDER = "PICK UNDER"
    SKIP = "SKIP MATCH"
    NEED_DATA = "NEED DATA"

    def label(self, line: float) -> str:
        """Vonis lengkap dengan garisnya, mis. 'PICK UNDER 5.5'."""
        if self in (Decision.PICK_OVER, Decision.PICK_UNDER):
            return f"{self.value} {line}"
        return self.value


@dataclass
class Verdict:
    """Keluaran akhir mesin."""

    match: str
    #: Garis yang dinilai (mis. 4.5 untuk Over/Under 4,5).
    line: float
    decision: Decision
    confidence: float                      #: 0..100, sudah dikalibrasi & dihukum
    raw_probability_over: float            #: P(over 4.5) mentah dari sebaran
    calibrated_probability_over: float     #: setelah kalibrasi Platt
    expected_corners_1h: float             #: mu total
    vmr: float
    threshold: float
    quality: DataQuality
    reasoning: List[str] = field(default_factory=list)
    weights: Dict[str, Any] = field(default_factory=dict)
    distribution: List[float] = field(default_factory=list)
    prompts: List[str] = field(default_factory=list)   #: pertanyaan untuk pengguna

    #: Harga bandar untuk sisi yang dipilih, kalau dimasukkan.
    price: Optional[float] = None
    #: Nilai harapan per satuan taruhan: p x harga - 1. Positif berarti harga
    #: bandar lebih murah daripada peluang menurut model — dan itu hanya
    #: sebaik modelnya, jadi bacalah bersama keyakinan, bukan menggantikannya.
    expected_value: Optional[float] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "match": self.match,
            "line": self.line,
            "market": f"1H corner Over/Under {self.line}",
            "decision": self.decision.label(self.line),
            "confidence": round(self.confidence, 2),
            "raw_probability_over": round(self.raw_probability_over, 4),
            "calibrated_probability_over": round(self.calibrated_probability_over, 4),
            "expected_corners_1h": round(self.expected_corners_1h, 3),
            "vmr": round(self.vmr, 3),
            "threshold": self.threshold,
            "quality": self.quality.to_dict(),
            "reasoning": self.reasoning,
            "weights": self.weights,
            "distribution": [round(p, 5) for p in self.distribution],
            "prompts": self.prompts,
            "price": self.price,
            "expected_value": round(self.expected_value, 4) if self.expected_value is not None else None,
        }
