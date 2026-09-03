"""Penjelasan naratif yang dibangun dari angka yang benar-benar dipakai mesin.

Aturan keras di modul ini: **setiap kalimat harus berasal dari nilai di
``weights`` atau ``TeamProjection``.** Tidak ada kalimat template yang bisa
bertentangan dengan angkanya, dan tidak ada klaim taktis yang tidak punya angka
pendukung. Ada test (``tests/test_explain.py``) yang memeriksa hal ini.
"""

from __future__ import annotations

from typing import Any, Dict, List

from .models import Decision, Provenance, Verdict

__all__ = ["build_reasoning", "render_text"]


def _fmt(x: float, n: int = 2) -> str:
    """Format angka gaya Indonesia (koma desimal)."""
    return f"{x:.{n}f}".replace(".", ",")


def _strength(ratio: float) -> str:
    if ratio >= 1.25:
        return "jauh di atas"
    if ratio >= 1.10:
        return "di atas"
    if ratio >= 0.90:
        return "sekitar"
    if ratio >= 0.75:
        return "di bawah"
    return "jauh di bawah"


def build_reasoning(
    verdict: Verdict,
    projections: List[Any],
    weights: Dict[str, Any],
    gates: List[str],
) -> List[str]:
    """Susun alasan analitis berlapis, dari fakta ke inferensi ke keputusan."""
    home, away = projections
    lines: List[str] = []
    base = weights["league_base_corners_1h_per_team"]

    # --- FAKTA: dari mana angkanya ---
    for label, proj in (("Tuan rumah", home), ("Tamu", away)):
        venue = "kandang" if proj is home else "tandang"
        scope = f"statistik {venue}" if proj.venue_specific else "statistik keseluruhan"
        src = {
            Provenance.MANUAL: "input manual",
            Provenance.API: "API statistik",
            Provenance.VISION: "pembacaan screenshot",
            Provenance.OCR: "OCR lokal",
            Provenance.DERIVED: "DITURUNKAN dari corner pertandingan penuh",
            Provenance.ASSUMED: "asumsi default",
        }[proj.provenance]
        lines.append(
            f"FAKTA — {label} {proj.name}: {_fmt(proj.corners_for)} corner 1H dibuat dan "
            f"{_fmt(proj.corners_against)} corner 1H dikebobolan per laga, dari {proj.matches} laga "
            f"({scope}, sumber: {src})."
        )

    lines.append(
        f"FAKTA — Patokan liga: {_fmt(base)} corner 1H per tim, jadi laga rata-rata di liga ini "
        f"menghasilkan {_fmt(base * 2)} corner sebelum babak kedua."
    )

    # --- INTERPRETASI: rasio mentah vs setelah shrinkage ---
    for label, proj in (("Tuan rumah", home), ("Tamu", away)):
        lines.append(
            f"INTERPRETASI — Serangan corner {proj.name} {_strength(proj.attack_ratio_raw)} rata-rata liga "
            f"(rasio mentah {_fmt(proj.attack_ratio_raw)}), tapi dengan sampel {proj.matches} laga rasio itu "
            f"ditarik ke {_fmt(proj.attack_ratio)}. Pertahanannya melepas corner pada rasio "
            f"{_fmt(proj.defend_ratio_raw)} mentah -> {_fmt(proj.defend_ratio)} setelah shrinkage."
        )
        if abs(proj.tempo_factor - 1.0) > 0.005:
            notes = ", ".join(weights["tempo_notes"].get("home" if proj is home else "away", []))
            arah = "menaikkan" if proj.tempo_factor > 1 else "menurunkan"
            lines.append(
                f"INTERPRETASI — Tempo {proj.name} {arah} proyeksi sebesar "
                f"{_fmt(abs(proj.tempo_factor - 1) * 100, 1)}% ({notes}). Batas keras penyesuaian ini "
                f"±12%, jadi sinyal tembakan tidak pernah bisa menggeser kesimpulan sendirian."
            )

    # --- INFERENSI MODEL ---
    lines.append(
        f"MODEL — Proyeksi corner 1H: {_fmt(home.mu)} (tuan rumah, sudah termasuk faktor kandang "
        f"{_fmt(weights['home_advantage'])}) + {_fmt(away.mu)} (tamu) = "
        f"{_fmt(weights['mu_total_used'])} total."
    )
    lines.append(
        f"MODEL — Sebaran binomial negatif dengan VMR {_fmt(weights['vmr'])} "
        f"({weights['vmr_source']}), bukan Poisson: corner datang berkelompok, jadi ekornya lebih tebal "
        f"dan klaim keyakinan jadi lebih rendah — itu memang yang benar."
    )
    line_txt = _fmt(verdict.line, 1)
    lines.append(
        f"MODEL — Estimasi titik P(over {line_txt}) = {_fmt(weights['p_over_point_estimate'] * 100, 1)}%. "
        f"Setelah dirata-ratakan atas ketidakpastian mu (galat baku ±{_fmt(weights['mu_sigma'])} corner), "
        f"angkanya menjadi {_fmt(weights['p_over_marginalised'] * 100, 1)}%."
    )

    cal = weights["calibrator"]
    if cal.get("fitted"):
        lines.append(
            f"MODEL — Kalibrasi Platt (A={_fmt(cal['a'])}, B={_fmt(cal['b'])}, {cal['n_samples']} sampel) "
            f"menggesernya ke {_fmt(weights['p_over_calibrated'] * 100, 1)}%."
        )
    else:
        lines.append(
            "MODEL — Kalibrator BELUM dipasang pada data held-out, jadi probabilitas ini belum terbukti "
            "jujur. Inilah alasan utama mesin menolak menerbitkan PICK: ambang 85% tanpa kalibrasi "
            "hanyalah angka, bukan bukti."
        )

    if weights["reliability_factor"] < 0.999:
        lines.append(
            f"MODEL — Penalti keandalan sumber ×{_fmt(weights['reliability_factor'])} menarik probabilitas "
            f"ke arah 50%, menghasilkan {_fmt(weights['p_over_final'] * 100, 1)}% final. "
            f"Angka yang dibaca dari screenshot tidak diperlakukan sama dengan angka yang diketik manual."
        )

    # --- KEPUTUSAN ---
    lines.append("GERBANG — " + "; ".join(gates))

    if verdict.decision is Decision.NEED_DATA:
        lines.append(
            "KEPUTUSAN — Data wajib belum lengkap. Mesin tidak menebak; ia meminta angka yang kurang."
        )
    elif verdict.decision is Decision.SKIP:
        failed = [g for g in gates if g.startswith("GAGAL")]
        lines.append(
            f"KEPUTUSAN — SKIP. {len(failed)} gerbang gagal. Keyakinan {_fmt(verdict.confidence, 1)}% "
            f"terhadap ambang {_fmt(verdict.threshold, 0)}%."
        )
    else:
        side = "OVER" if verdict.decision is Decision.PICK_OVER else "UNDER"
        lines.append(
            f"KEPUTUSAN — PICK {side} {line_txt} pada keyakinan {_fmt(verdict.confidence, 1)}%. "
            f"Semua gerbang lulus. Ini tetap probabilitas, bukan kepastian."
        )

    return lines


def render_text(verdict: Verdict) -> str:
    """Render terminal — dipakai CLI dan sebagai fallback API."""
    d = verdict
    bar = "=" * 68
    out: List[str] = [
        bar,
        f"  {d.match}",
        f"  PASAR: Sepak pojok babak pertama, garis {_fmt(d.line, 1)}",
        bar,
        "",
        f"  VONIS        : [ {d.decision.label(d.line)} ]",
        f"  KEYAKINAN    : {_fmt(d.confidence, 1)}%  (ambang {_fmt(d.threshold, 0)}%)",
        f"  PROYEKSI 1H  : {_fmt(d.expected_corners_1h)} corner (VMR {_fmt(d.vmr)})",
        f"  P(OVER {_fmt(d.line, 1)})  : {_fmt(d.calibrated_probability_over * 100, 1)}%",
        f"  P(UNDER {_fmt(d.line, 1)}) : {_fmt((1 - d.calibrated_probability_over) * 100, 1)}%",
        "",
        "  ALASAN ANALITIS",
        "  " + "-" * 64,
    ]
    for line in d.reasoning:
        out.extend(_wrap(line, 66, indent="  "))
    if d.prompts:
        out += ["", "  DIBUTUHKAN DARI ANDA", "  " + "-" * 64]
        for p in d.prompts:
            out.extend(_wrap("• " + p, 66, indent="  "))
    out += ["", bar]
    return "\n".join(out)


def _wrap(text: str, width: int, indent: str = "") -> List[str]:
    words = text.split()
    lines: List[str] = []
    cur = ""
    for w in words:
        if len(cur) + len(w) + 1 > width:
            lines.append(indent + cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    if cur:
        lines.append(indent + cur)
    return lines
