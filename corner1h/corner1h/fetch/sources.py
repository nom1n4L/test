"""Sumber data otomatis.

Kenyataan yang menentukan desain modul ini, dan yang sebaiknya diketahui sejak
awal: **tidak ada sumber publik gratis yang menyediakan pemisahan corner babak
pertama.** Arsip football-data.co.uk memberi corner pertandingan penuh (kolom HC
dan AC) tanpa kunci API, tetapi tidak memisahkan babak. API-Football memisahkan
statistik per babak hanya pada paket berbayar; endpoint gratisnya memberi corner
pertandingan penuh saja.

Konsekuensinya di sistem ini bukan disembunyikan, melainkan dikodekan:

* Angka pertandingan penuh dari sumber otomatis MEMANG membantu — ia
  memperbaiki estimasi mu dan mempersempit galat baku.
* Tapi angka itu ditandai ``Provenance.DERIVED`` ketika dipakai sebagai proksi
  1H, dan gerbang ``require_native_1h`` menolak menerbitkan PICK di atasnya.

Jadi pengambilan otomatis berfungsi sebagai pengisi celah dan pemeriksa silang,
bukan sebagai jalan pintas menuju PICK. Data 1H asli tetap harus datang dari
screenshot atau input manual.
"""

from __future__ import annotations

import csv
import io
import os
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from statistics import mean
from typing import Dict, List, Optional, Protocol, Sequence

from ..extract.normalize import team_key
from ..models import Field as StatField
from ..models import Provenance, TeamStats

__all__ = ["Source", "FootballDataCoUk", "ApiFootballSource", "SourceResult", "DEFAULT_SOURCES"]

_UA = "corner1h/0.1 (+statistik pribadi)"
_TIMEOUT = 20


@dataclass
class SourceResult:
    """Apa yang berhasil diambil sebuah sumber untuk satu tim."""

    source: str
    team: str
    matches: int = 0
    corners_for_ft: Optional[float] = None
    corners_against_ft: Optional[float] = None
    corners_for_ft_venue: Optional[float] = None
    corners_against_ft_venue: Optional[float] = None
    shots_ft: Optional[float] = None
    #: True hanya kalau sumbernya benar-benar memisahkan babak.
    has_native_1h: bool = False
    notes: List[str] = field(default_factory=list)


class Source(Protocol):
    """Kontrak sumber data. Tambahkan sumber baru dengan mengikuti ini."""

    name: str

    def available(self) -> bool:
        ...

    def lookup(self, team: str, *, venue: str, league_hint: Optional[str] = None) -> Optional[SourceResult]:
        """``venue`` adalah 'home' atau 'away'."""
        ...


def _http_get(url: str) -> str:
    """GET sederhana lewat stdlib. Menghormati variabel proxy lingkungan."""
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return resp.read().decode("utf-8", errors="replace")


@dataclass
class FootballDataCoUk:
    """Arsip CSV terbuka, tanpa kunci API.

    Memberi corner PERTANDINGAN PENUH per laga (kolom HC/AC), jadi hasilnya
    hanya bisa dipakai sebagai proksi 1H — ``has_native_1h`` selalu False.

    Catatan lapangan: sebagian ISP Indonesia mengarahkan domain ini ke server
    blokir karena arsipnya memuat data odds. Kegagalan koneksi ditangani sebagai
    "sumber tidak tersedia", bukan sebagai galat fatal.
    """

    name: str = "football-data.co.uk"
    season: str = "2526"
    #: Kode liga football-data.co.uk yang akan disisir.
    league_codes: Sequence[str] = ("E0", "E1", "SP1", "I1", "D1", "F1", "N1", "P1")
    _cache: Dict[str, List[dict]] = field(default_factory=dict, repr=False)

    def available(self) -> bool:
        return True  # ketersediaan sebenarnya baru ketahuan saat request

    def _rows(self, code: str) -> List[dict]:
        if code in self._cache:
            return self._cache[code]
        url = f"https://www.football-data.co.uk/mmz4281/{self.season}/{code}.csv"
        try:
            text = _http_get(url)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError):
            self._cache[code] = []
            return []
        rows = list(csv.DictReader(io.StringIO(text)))
        self._cache[code] = rows
        return rows

    def lookup(self, team: str, *, venue: str, league_hint: Optional[str] = None) -> Optional[SourceResult]:
        key = team_key(team)
        if not key:
            return None

        for code in self.league_codes:
            rows = self._rows(code)
            if not rows:
                continue

            cf: List[float] = []
            ca: List[float] = []
            cf_venue: List[float] = []
            ca_venue: List[float] = []
            shots: List[float] = []

            for row in rows:
                home, away = row.get("HomeTeam"), row.get("AwayTeam")
                try:
                    hc, ac = float(row.get("HC") or "nan"), float(row.get("AC") or "nan")
                except ValueError:
                    continue
                if hc != hc or ac != ac:  # NaN
                    continue

                if team_key(home) == key:
                    cf.append(hc)
                    ca.append(ac)
                    if venue == "home":
                        cf_venue.append(hc)
                        ca_venue.append(ac)
                    try:
                        shots.append(float(row.get("HS") or "nan"))
                    except ValueError:
                        pass
                elif team_key(away) == key:
                    cf.append(ac)
                    ca.append(hc)
                    if venue == "away":
                        cf_venue.append(ac)
                        ca_venue.append(hc)
                    try:
                        shots.append(float(row.get("AS") or "nan"))
                    except ValueError:
                        pass

            if len(cf) >= 3:
                clean_shots = [s for s in shots if s == s]
                return SourceResult(
                    source=self.name,
                    team=team,
                    matches=len(cf),
                    corners_for_ft=mean(cf),
                    corners_against_ft=mean(ca),
                    corners_for_ft_venue=mean(cf_venue) if len(cf_venue) >= 3 else None,
                    corners_against_ft_venue=mean(ca_venue) if len(ca_venue) >= 3 else None,
                    shots_ft=mean(clean_shots) if clean_shots else None,
                    has_native_1h=False,
                    notes=[
                        f"{len(cf)} laga dari {code} musim {self.season}. "
                        "Corner PERTANDINGAN PENUH — tidak ada pemisahan babak di arsip ini."
                    ],
                )
        return None


@dataclass
class ApiFootballSource:
    """API-Football lewat kunci gratis (opsional).

    Endpoint statistik tim pada paket gratis memberi total corner pertandingan
    penuh. Pemisahan per babak butuh paket berbayar, jadi ``has_native_1h``
    tetap False kecuali respons benar-benar memuat medan babak pertama.
    """

    name: str = "api-football"
    api_key: Optional[str] = None
    base: str = "https://v3.football.api-sports.io"

    def available(self) -> bool:
        return bool(self.api_key or os.environ.get("API_FOOTBALL_KEY"))

    def lookup(self, team: str, *, venue: str, league_hint: Optional[str] = None) -> Optional[SourceResult]:
        # Sengaja tidak diimplementasikan penuh: memerlukan pemetaan id liga dan
        # id tim yang menghabiskan kuota, dan tanpa kunci tidak bisa diuji.
        # Kontraknya sudah jelas sehingga bisa dilengkapi tanpa mengubah
        # pemanggilnya.
        return None


DEFAULT_SOURCES: List[Source] = [FootballDataCoUk(), ApiFootballSource()]


def apply_result(team: TeamStats, result: SourceResult) -> List[str]:
    """Tempelkan hasil sumber ke ``TeamStats``, tanpa menimpa data yang lebih kuat.

    Aturan: data yang sudah ada dari screenshot/manual selalu menang. Sumber
    otomatis hanya mengisi lubang.
    """
    filled: List[str] = []
    prov = Provenance.API

    def maybe(attr: str, value: Optional[float]) -> None:
        if value is None or getattr(team, attr) is not None:
            return
        setattr(team, attr, StatField(float(value), prov))
        filled.append(attr)

    maybe("corners_for_ft", result.corners_for_ft)
    maybe("corners_against_ft", result.corners_against_ft)
    maybe("corners_for_ft_venue", result.corners_for_ft_venue)
    maybe("corners_against_ft_venue", result.corners_against_ft_venue)
    maybe("shots_ft", result.shots_ft)
    if team.matches_sampled is None and result.matches:
        team.matches_sampled = StatField(float(result.matches), prov)
        filled.append("matches_sampled")
    return filled
