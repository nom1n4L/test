"""Mesin prediksi corner babak pertama, pasar Over/Under 4.5.

Alur perhitungan (setiap langkah bisa diaudit lewat ``Verdict.weights``):

1.  **Pilih sumber angka terbaik** per tim, menuruni tangga keandalan:
    corner 1H spesifik venue > corner 1H keseluruhan > corner penuh venue x porsi
    1H > corner penuh keseluruhan x porsi 1H. Turun satu anak tangga berarti
    provenance melemah, dan itu ikut menurunkan keyakinan akhir.

2.  **Rasio serangan/pertahanan** relatif terhadap patokan liga, lalu
    **shrinkage** ke arah 1,0 sesuai ukuran sampel: ``1 + (rasio-1) * n/(n+k)``.
    Tim dengan 3 laga tidak boleh menggeser proyeksi sejauh tim dengan 20 laga.

3.  **Faktor kandang** — tuan rumah rata-rata mendapat porsi corner lebih besar.

4.  **Penyesuaian tempo** dari tembakan / serangan berbahaya, dibatasi keras
    (default +-12%) supaya sinyal sekunder tidak pernah mengambil alih sinyal
    utama.

5.  **Sebaran binomial negatif** pada total corner 1H, dengan VMR empiris kalau
    riwayat per-laga tersedia, kalau tidak memakai default 1,25.

6.  **Marginalisasi ketidakpastian parameter**: mu bukan angka pasti, ia punya
    galat baku. Probabilitas dirata-ratakan pada kisi normal di sekitar mu,
    sehingga data tipis otomatis menghasilkan probabilitas yang lebih dekat ke
    50% — bukan lewat fudge factor, melainkan lewat integrasi yang benar.

7.  **Kalibrasi Platt**, lalu **penalti keandalan sumber**.

8.  **Gerbang keras**: sejumlah syarat yang, kalau tidak terpenuhi, memaksa SKIP
    berapa pun angka keyakinannya.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field as dc_field
from statistics import mean, pvariance
from typing import Any, Dict, List, Optional, Tuple

from .calibration import Calibrator
from .distributions import pmf_table, prob_at_least
from .models import (
    DataQuality,
    Decision,
    Field,
    MatchInput,
    Provenance,
    TeamStats,
    Verdict,
)

__all__ = ["EngineConfig", "CornerEngine", "TeamProjection"]

LINE = 4.5
#: Over 4.5 berarti minimal 5 corner di babak pertama.
OVER_THRESHOLD_COUNT = 5


def _missing(value: Optional[float]) -> bool:
    """True kalau nilainya tidak ada — None maupun NaN.

    ``_pick_corner_source`` memakai NaN untuk "tidak ketemu di tangga sumber",
    dan ``NaN is None`` bernilai False, jadi pemeriksaan ``is None`` saja akan
    meloloskan data yang sebenarnya kosong.
    """
    return value is None or value != value


@dataclass
class EngineConfig:
    """Semua parameter yang bisa disetel. Nilai default dipilih konservatif."""

    #: Ambang "PICK atau SKIP" dalam persen.
    threshold: float = 85.0

    #: Rata-rata corner 1H per tim di liga tak dikenal (total 1H ~4,9).
    league_corners_1h_per_team: float = 2.45
    #: Porsi corner yang terjadi di babak pertama.
    league_1h_share: float = 0.46

    #: Rasio varians-terhadap-mean default untuk total corner 1H.
    vmr: float = 1.25
    vmr_bounds: Tuple[float, float] = (1.05, 1.90)

    #: Konstanta shrinkage: n/(n+k). k=6 berarti sampel 6 laga -> bobot 50%.
    shrink_matches: float = 6.0
    #: Batas rasio serangan/pertahanan setelah shrinkage.
    ratio_bounds: Tuple[float, float] = (0.55, 1.75)

    #: Tuan rumah biasanya memenangi lebih banyak corner.
    home_advantage: float = 1.06
    #: Batas keras penyesuaian tempo.
    tempo_max_adjust: float = 0.12

    #: Batas keras mu total corner 1H (mencegah angka meledak).
    mu_bounds: Tuple[float, float] = (1.2, 10.0)

    #: Rentang di mana total corner 1H benar-benar hidup pada pertandingan nyata.
    #:
    #: Rata-rata liga sekitar 4,9 dan sebaran antar-pasangan tim cukup sempit,
    #: jadi proyeksi di luar rentang ini hampir selalu berarti masukannya yang
    #: salah — titik desimal tergeser, atau angka pertandingan penuh masuk ke
    #: medan 1H — bukan pertandingan yang benar-benar ekstrem. Di luar rentang
    #: ini mesin menolak PICK dan meminta pengguna memeriksa angkanya.
    plausible_mu_bounds: Tuple[float, float] = (2.0, 8.5)

    #: Galat baku minimum pada mu — mewakili ketidakpastian bentuk model itu
    #: sendiri, yang tidak pernah nol berapa pun banyaknya data.
    model_form_sigma: float = 0.45

    # --- gerbang keras ---
    #: Jumlah laga minimum (tim dengan sampel terkecil) untuk boleh PICK.
    min_matches_for_pick: int = 8
    #: Kalau True, PICK hanya boleh dari data 1H asli — bukan hasil membelah
    #: angka pertandingan penuh dengan porsi 1H.
    require_native_1h: bool = True
    #: Kalau True, PICK hanya boleh kalau kalibrator sudah dipasang pada data
    #: held-out. Ini yang mencegah ambang 85% menjadi angka kosong.
    require_fitted_calibration: bool = True

    #: Pangkat penalti keandalan; makin besar makin galak.
    reliability_power: float = 2.0

    calibrator: Calibrator = dc_field(default_factory=Calibrator)


@dataclass
class TeamProjection:
    """Hasil antara per tim — disimpan supaya penjelasan tidak pernah
    bertentangan dengan angka yang benar-benar dipakai."""

    name: str
    corners_for: float
    corners_against: float
    provenance: Provenance
    venue_specific: bool
    matches: int
    attack_ratio_raw: float
    attack_ratio: float
    defend_ratio_raw: float
    defend_ratio: float
    tempo_factor: float
    mu: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "corners_for_1h": round(self.corners_for, 3),
            "corners_against_1h": round(self.corners_against, 3),
            "provenance": self.provenance.value,
            "venue_specific": self.venue_specific,
            "matches": self.matches,
            "attack_ratio_raw": round(self.attack_ratio_raw, 3),
            "attack_ratio_shrunk": round(self.attack_ratio, 3),
            "defend_ratio_raw": round(self.defend_ratio_raw, 3),
            "defend_ratio_shrunk": round(self.defend_ratio, 3),
            "tempo_factor": round(self.tempo_factor, 3),
            "mu": round(self.mu, 3),
        }


class CornerEngine:
    """Mesin utama. Tanpa dependensi eksternal, sepenuhnya deterministik."""

    def __init__(self, config: Optional[EngineConfig] = None) -> None:
        self.cfg = config or EngineConfig()

    # ------------------------------------------------------ pemilihan sumber

    def _pick_corner_source(
        self, team: TeamStats, share: float
    ) -> Tuple[Optional[float], Optional[float], Provenance, bool, float]:
        """Kembalikan (corner_untuk_1H, corner_lawan_1H, provenance, venue, bobot).

        Menuruni tangga keandalan dan berhenti di anak tangga pertama yang
        lengkap. Nilai yang diturunkan dari pertandingan penuh ditandai
        ``Provenance.DERIVED`` sehingga gerbang ``require_native_1h`` bisa
        menolaknya.
        """
        ladder: List[Tuple[Optional[Field], Optional[Field], bool, bool]] = [
            (team.corners_for_1h_venue, team.corners_against_1h_venue, True, False),
            (team.corners_for_1h, team.corners_against_1h, False, False),
            (team.corners_for_ft_venue, team.corners_against_ft_venue, True, True),
            (team.corners_for_ft, team.corners_against_ft, False, True),
        ]
        for f_for, f_against, venue, derived in ladder:
            if f_for is None or f_against is None:
                continue
            scale = share if derived else 1.0
            prov = Provenance.DERIVED if derived else f_for.provenance
            weight = min(f_for.weight, f_against.weight)
            if derived:
                weight *= Provenance.DERIVED.reliability
            return (
                float(f_for.value) * scale,
                float(f_against.value) * scale,
                prov,
                venue,
                weight,
            )
        return None, None, Provenance.ASSUMED, False, 0.0

    def _tempo_factor(self, team: TeamStats) -> Tuple[float, List[str]]:
        """Faktor tempo dari tembakan / serangan berbahaya, dibatasi keras.

        Patokan kasar liga: ~12,5 tembakan dan ~55 serangan berbahaya per laga.
        Sinyal ini sengaja lemah — ia menyesuaikan, bukan menentukan.
        """
        signals: List[float] = []
        notes: List[str] = []
        if team.shots_ft is not None:
            ratio = float(team.shots_ft.value) / 12.5
            signals.append(ratio)
            notes.append(f"tembakan {float(team.shots_ft.value):.1f}/laga (patokan 12,5)")
        if team.dangerous_attacks_ft is not None:
            ratio = float(team.dangerous_attacks_ft.value) / 55.0
            signals.append(ratio)
            notes.append(
                f"serangan berbahaya {float(team.dangerous_attacks_ft.value):.0f}/laga (patokan 55)"
            )
        if not signals:
            return 1.0, notes

        raw = mean(signals)
        cap = self.cfg.tempo_max_adjust
        factor = max(1.0 - cap, min(1.0 + cap, raw))
        return factor, notes

    def _shrink(self, ratio: float, n: int) -> float:
        k = self.cfg.shrink_matches
        w = n / (n + k) if n > 0 else 0.0
        shrunk = 1.0 + (ratio - 1.0) * w
        lo, hi = self.cfg.ratio_bounds
        return max(lo, min(hi, shrunk))

    # ------------------------------------------------------------- kualitas

    def _assess(self, match: MatchInput, projections: List[TeamProjection], weights: List[float]) -> DataQuality:
        missing_required: List[str] = []
        missing_optional: List[str] = []
        weak: List[str] = []

        for side, team, proj in (("home", match.home, projections[0]), ("away", match.away, projections[1])):
            # Sumber yang tidak ketemu menghasilkan NaN, bukan None — dan
            # `NaN is None` bernilai False, jadi pemeriksaannya harus eksplisit.
            if _missing(proj.corners_for):
                missing_required.append(f"{side}.corners_for_1h")
            if _missing(proj.corners_against):
                missing_required.append(f"{side}.corners_against_1h")
            if team.matches_sampled is None:
                missing_required.append(f"{side}.matches_sampled")
            if proj.provenance is Provenance.DERIVED:
                weak.append(f"{side}.corners_1h (diturunkan dari pertandingan penuh)")
            if not proj.venue_specific:
                missing_optional.append(
                    f"{side}.corners_1h_venue (statistik {'kandang' if side == 'home' else 'tandang'})"
                )
            if team.shots_ft is None and team.dangerous_attacks_ft is None:
                missing_optional.append(f"{side}.shots_ft / {side}.dangerous_attacks_ft")
            if not team.corner_1h_history:
                missing_optional.append(f"{side}.corner_1h_history")

        if match.league_corners_1h_per_team is None:
            missing_optional.append("league_corners_1h_per_team")
            weak.append("patokan liga (memakai default global 2,45)")

        used = [w for w in weights if w > 0]
        return DataQuality(
            missing_required=missing_required,
            missing_optional=missing_optional,
            weak_fields=weak,
            mean_reliability=(sum(used) / len(used)) if used else 0.0,
            min_sample=min(projections[0].matches, projections[1].matches),
        )

    # ------------------------------------------------------------------ VMR

    def _empirical_vmr(self, match: MatchInput) -> Tuple[float, bool]:
        """Hitung VMR dari riwayat corner 1H kalau ada.

        Riwayat per-laga adalah satu-satunya cara mengukur dispersi sebenarnya.
        Tanpa itu kita memakai default konservatif, dan default yang lebih lebar
        selalu berarti keyakinan yang lebih rendah — arah yang aman.
        """
        samples: List[int] = []
        samples.extend(match.home.corner_1h_history)
        samples.extend(match.away.corner_1h_history)
        samples.extend(match.h2h_corner_1h)
        if len(samples) < 8:
            return self.cfg.vmr, False
        m = mean(samples)
        if m <= 0:
            return self.cfg.vmr, False
        vmr = pvariance(samples) / m
        lo, hi = self.cfg.vmr_bounds
        return max(lo, min(hi, vmr)), True

    # ------------------------------------------------------------ mu & galat

    def _mu_sigma(self, projections: List[TeamProjection], vmr: float) -> float:
        """Galat baku pada mu total.

        Dua sumber: (a) galat baku rata-rata sampel tiap tim, (b) ketidakpastian
        bentuk model yang tidak pernah hilang. Keduanya digabung secara
        kuadratik.
        """
        var = 0.0
        for proj in projections:
            n = max(1, proj.matches)
            # Var(rata-rata) = lambda * vmr / n untuk cacahan overdispersed.
            var += (proj.mu * vmr) / n
        return math.sqrt(var + self.cfg.model_form_sigma ** 2)

    def _marginalise(self, mu: float, sigma: float, vmr: float) -> float:
        """P(over 4.5) yang dirata-ratakan atas ketidakpastian mu.

        Kisi Gauss 5 titik pada mu +- {0, 1, 2} sigma dengan bobot normal.
        Marginalisasi ini yang membuat data tipis otomatis mendekati 50%.
        """
        nodes = (-2.0, -1.0, 0.0, 1.0, 2.0)
        raw_w = [math.exp(-0.5 * z * z) for z in nodes]
        total_w = sum(raw_w)
        lo, hi = self.cfg.mu_bounds

        acc = 0.0
        for z, w in zip(nodes, raw_w):
            mu_z = max(lo, min(hi, mu + z * sigma))
            acc += (w / total_w) * prob_at_least(OVER_THRESHOLD_COUNT, mu_z, vmr)
        return acc

    # ----------------------------------------------------------------- utama

    def predict(self, match: MatchInput) -> Verdict:
        cfg = self.cfg
        share = (
            float(match.league_1h_share.value)
            if match.league_1h_share is not None
            else cfg.league_1h_share
        )
        base = (
            float(match.league_corners_1h_per_team.value)
            if match.league_corners_1h_per_team is not None
            else cfg.league_corners_1h_per_team
        )

        projections: List[TeamProjection] = []
        weights: List[float] = []
        tempo_notes: Dict[str, List[str]] = {}

        for side, team in (("home", match.home), ("away", match.away)):
            cf, ca, prov, venue, w = self._pick_corner_source(team, share)
            n = int(team.matches_sampled.value) if team.matches_sampled is not None else 0
            tempo, notes = self._tempo_factor(team)
            tempo_notes[side] = notes

            atk_raw = (cf / base) if cf is not None and base > 0 else 1.0
            def_raw = (ca / base) if ca is not None and base > 0 else 1.0

            projections.append(
                TeamProjection(
                    name=team.name or side,
                    corners_for=cf if cf is not None else float("nan"),
                    corners_against=ca if ca is not None else float("nan"),
                    provenance=prov,
                    venue_specific=venue,
                    matches=n,
                    attack_ratio_raw=atk_raw,
                    attack_ratio=self._shrink(atk_raw, n),
                    defend_ratio_raw=def_raw,
                    defend_ratio=self._shrink(def_raw, n),
                    tempo_factor=tempo,
                )
            )
            weights.append(w)

        home_p, away_p = projections

        # mu tiap tim = patokan x serangan sendiri x kelemahan lawan x kandang x tempo
        home_p.mu = base * home_p.attack_ratio * away_p.defend_ratio * cfg.home_advantage * home_p.tempo_factor
        away_p.mu = base * away_p.attack_ratio * home_p.defend_ratio / cfg.home_advantage * away_p.tempo_factor

        mu = home_p.mu + away_p.mu
        lo, hi = cfg.mu_bounds
        mu_clamped = max(lo, min(hi, mu))

        vmr, vmr_empirical = self._empirical_vmr(match)
        sigma = self._mu_sigma(projections, vmr)

        p_point = prob_at_least(OVER_THRESHOLD_COUNT, mu_clamped, vmr)
        p_marginal = self._marginalise(mu_clamped, sigma, vmr)
        p_calibrated = cfg.calibrator.apply(p_marginal)

        quality = self._assess(match, projections, weights)

        # Penalti keandalan: tarik probabilitas ke 50% sebanding dengan seberapa
        # lemah asal-usul angkanya.
        rel = quality.mean_reliability if quality.mean_reliability > 0 else 0.0
        rel_factor = rel ** cfg.reliability_power
        p_final = 0.5 + (p_calibrated - 0.5) * rel_factor

        confidence = 100.0 * max(p_final, 1.0 - p_final)
        lean_over = p_final >= 0.5

        decision, gate_notes, prompts = self._decide(
            confidence, lean_over, quality, projections, mu_raw=mu
        )

        weights_out: Dict[str, Any] = {
            "league_base_corners_1h_per_team": round(base, 3),
            "league_1h_share": round(share, 3),
            "home_advantage": cfg.home_advantage,
            "home": home_p.to_dict(),
            "away": away_p.to_dict(),
            "mu_total_raw": round(mu, 3),
            "mu_total_used": round(mu_clamped, 3),
            "mu_sigma": round(sigma, 3),
            "vmr": round(vmr, 3),
            "vmr_source": "empiris dari riwayat" if vmr_empirical else "default konservatif",
            "p_over_point_estimate": round(p_point, 4),
            "p_over_marginalised": round(p_marginal, 4),
            "p_over_calibrated": round(p_calibrated, 4),
            "reliability_factor": round(rel_factor, 4),
            "p_over_final": round(p_final, 4),
            "calibrator": cfg.calibrator.to_dict(),
            "tempo_notes": tempo_notes,
            "gates": gate_notes,
        }

        from .explain import build_reasoning  # impor lokal: hindari siklus

        verdict = Verdict(
            match=f"{home_p.name} vs {away_p.name}",
            decision=decision,
            confidence=confidence,
            raw_probability_over=p_marginal,
            calibrated_probability_over=p_final,
            expected_corners_1h=mu_clamped,
            vmr=vmr,
            threshold=cfg.threshold,
            quality=quality,
            weights=weights_out,
            distribution=pmf_table(mu_clamped, vmr, max_k=12),
            prompts=prompts,
        )
        verdict.reasoning = build_reasoning(verdict, projections, weights_out, gate_notes)
        return verdict

    # -------------------------------------------------------------- gerbang

    def _decide(
        self,
        confidence: float,
        lean_over: bool,
        quality: DataQuality,
        projections: List[TeamProjection],
        *,
        mu_raw: float,
    ) -> Tuple[Decision, List[str], List[str]]:
        """Terapkan gerbang keras. Semua gerbang berlaku *setelah* angka jadi,
        dan setiap gerbang yang gagal dicatat supaya alasannya bisa dibaca."""
        cfg = self.cfg
        gates: List[str] = []
        prompts: List[str] = []

        if not quality.is_sufficient:
            for f in quality.missing_required:
                prompts.append(
                    f"Data '{f}' tidak ada. Kirim screenshot tambahan atau ketik nilainya manual."
                )
            gates.append("GAGAL: data wajib tidak lengkap")
            return Decision.NEED_DATA, gates, prompts

        passed = True
        if quality.min_sample < cfg.min_matches_for_pick:
            gates.append(
                f"GAGAL: sampel terkecil {quality.min_sample} laga < minimum {cfg.min_matches_for_pick}"
            )
            passed = False
        else:
            gates.append(f"LULUS: sampel terkecil {quality.min_sample} laga")

        if cfg.require_native_1h and any(p.provenance is Provenance.DERIVED for p in projections):
            gates.append(
                "GAGAL: angka 1H diturunkan dari statistik pertandingan penuh, bukan data 1H asli"
            )
            prompts.append(
                "Untuk boleh PICK, dibutuhkan rata-rata corner BABAK PERTAMA yang asli "
                "(bukan hasil membagi corner pertandingan penuh). Kirim screenshot statistik 1H."
            )
            passed = False
        else:
            gates.append("LULUS: memakai data corner 1H asli")

        lo_p, hi_p = cfg.plausible_mu_bounds
        if not (lo_p <= mu_raw <= hi_p):
            gates.append(
                f"GAGAL: proyeksi {mu_raw:.2f} corner 1H di luar rentang wajar "
                f"{lo_p:.1f}-{hi_p:.1f} — masukannya patut dicurigai, bukan pertandingannya"
            )
            prompts.append(
                f"Proyeksi {mu_raw:.1f} corner babak pertama tidak masuk akal untuk sepak bola "
                f"(rata-rata liga sekitar 4,9). Periksa apakah angka yang dimasukkan benar-benar "
                f"rata-rata BABAK PERTAMA per laga — bukan total pertandingan penuh, dan bukan "
                f"total semusim."
            )
            passed = False
        else:
            gates.append(f"LULUS: proyeksi {mu_raw:.2f} corner 1H berada di rentang wajar")

        if cfg.require_fitted_calibration and not cfg.calibrator.fitted:
            gates.append(
                "GAGAL: kalibrator belum dipasang pada data held-out — ambang 85% belum terbukti jujur"
            )
            passed = False
        else:
            gates.append("LULUS: kalibrator terpasang")

        if confidence < cfg.threshold:
            gates.append(f"GAGAL: keyakinan {confidence:.1f}% < ambang {cfg.threshold:.0f}%")
            passed = False
        else:
            gates.append(f"LULUS: keyakinan {confidence:.1f}% >= ambang {cfg.threshold:.0f}%")

        if not passed:
            return Decision.SKIP, gates, prompts
        return (Decision.PICK_OVER if lean_over else Decision.PICK_UNDER), gates, prompts
