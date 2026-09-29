// Tingkat keyakinan, deteksi sinyal yang bertentangan, dan bukti terkuat.

import { clamp } from "./math";
import { missingShare, parseForm, type Signal } from "./model";
import type { MatchInput, ModelParams, TeamInput } from "./types";
import { removeMargin } from "./odds";

export type ConfLabel = "SANGAT TINGGI" | "TINGGI" | "SEDANG" | "RENDAH" | "SANGAT RENDAH";

export interface Confidence {
  score: number; // 0..10
  label: ConfLabel;
  parts: { label: string; value: number; note: string }[];
}

export interface Conflict {
  title: string;
  detail: string;
  resolution: string;
  severity: 1 | 2 | 3;
}

export interface Evidence {
  text: string;
  favors: "H" | "A" | "goals" | "neutral";
  strength: number;
}

const ok = (x: number | null | undefined): x is number => x !== null && x !== undefined && Number.isFinite(x);

export function labelFromScore(s: number): ConfLabel {
  if (s >= 8) return "SANGAT TINGGI";
  if (s >= 6.5) return "TINGGI";
  if (s >= 5) return "SEDANG";
  if (s >= 3.5) return "RENDAH";
  return "SANGAT RENDAH";
}

export function marketConf(p: number, overall: number): ConfLabel {
  let lvl = p >= 0.8 ? 4 : p >= 0.68 ? 3 : p >= 0.56 ? 2 : p >= 0.45 ? 1 : 0;
  if (overall < 5) lvl -= 1;
  const labels: ConfLabel[] = ["SANGAT RENDAH", "RENDAH", "SEDANG", "TINGGI", "SANGAT TINGGI"];
  return labels[clamp(lvl, 0, 4)];
}

function teamCompleteness(t: TeamInput, venueRelevant: boolean): number {
  const items: [boolean, number][] = [
    [ok(t.played) && ok(t.gf) && ok(t.ga), 1.0],
    [!venueRelevant || (ok(t.venuePlayed) && ok(t.venueGF) && ok(t.venueGA)), 1.0],
    [parseForm(t.form).n >= 3, 0.6],
    [ok(t.formGF) && ok(t.formGA), 0.8],
    [ok(t.xgFor) && ok(t.xgAgainst), 1.0],
    [ok(t.sotFor) && ok(t.sotAgainst), 0.5],
    [t.players.length > 0, 0.6],
    [ok(t.cornersFor), 0.25],
    [ok(t.yellowPg), 0.25],
    [ok(t.fhGFPct), 0.25],
    [ok(t.position), 0.3],
    [ok(t.rating), 0.15],
  ];
  const tot = items.reduce((s, [, w]) => s + w, 0);
  return items.reduce((s, [v, w]) => s + (v ? w : 0), 0) / tot;
}

export function dataCompleteness(input: MatchInput): number {
  const c = (teamCompleteness(input.home, !input.neutral) + teamCompleteness(input.away, !input.neutral)) / 2;
  const o = input.odds;
  const extra = (input.h2h.length > 0 ? 0.03 : 0) + (ok(input.referee.yellowPg) ? 0.02 : 0) + (ok(o.home) && ok(o.draw) && ok(o.away) ? 0.18 : 0);
  return clamp(c + extra, 0, 1);
}

export function computeConfidence(input: MatchInput, signals: Signal[], x12: { home: number; draw: number; away: number }, params: ModelParams): Confidence {
  const comp = dataCompleteness(input);
  const sigs = signals.filter((s) => s.key !== "league");
  let agreement = 0.35;
  if (sigs.length >= 2) {
    const wsum = sigs.reduce((s, x) => s + x.weight, 0);
    const sup = sigs.map((s) => Math.log(s.lh / s.la));
    const tot = sigs.map((s) => Math.log(s.lh + s.la));
    const mean = (xs: number[]) => xs.reduce((s, v, i) => s + v * sigs[i].weight, 0) / wsum;
    const mSup = mean(sup), mTot = mean(tot);
    const sdSup = Math.sqrt(mean(sup.map((v) => (v - mSup) ** 2)));
    const sdTot = Math.sqrt(mean(tot.map((v) => (v - mTot) ** 2)));
    agreement = clamp(1 - sdSup / 0.7, 0, 1) * 0.7 + clamp(1 - sdTot / 0.4, 0, 1) * 0.3;
    // Sedikit sinyal = kesepakatan kurang bermakna
    agreement *= clamp(sigs.length / 4, 0.5, 1);
  }
  const sorted = [x12.home, x12.draw, x12.away].sort((a, b) => b - a);
  // Sepak bola penuh varians: favorit 80% baru dianggap "tegas" penuh
  const decisive = clamp((sorted[0] - 0.38) / 0.42, 0, 1);
  const mk = params.market["1X2"];
  let calib = 1;
  let calibNote = "Belum ada riwayat hasil (netral)";
  if (mk && mk.n >= 5) {
    const ratio = (mk.hits + 2) / (mk.sumP + 2);
    calib = clamp(0.9 + 0.1 * ratio, 0.85, 1.05);
    calibNote = `Akurasi 1X2 historis ${(mk.hits / mk.n * 100).toFixed(0)}% dari ${mk.n} laga`;
  }
  const raw = 10 * (0.3 * comp + 0.3 * agreement + 0.4 * decisive) * calib;
  const score = clamp(Math.round(raw * 10) / 10, 1, 9);
  return {
    score,
    label: labelFromScore(score),
    parts: [
      { label: "Kelengkapan data", value: comp, note: `${Math.round(comp * 100)}% kolom kunci terisi` },
      { label: "Kesepakatan sinyal", value: agreement, note: `${sigs.length} sumber data dibandingkan` },
      { label: "Ketegasan hasil", value: decisive, note: `Hasil teratas ${Math.round(sorted[0] * 100)}%, selisih ${(100 * (sorted[0] - sorted[1])).toFixed(0)} poin dari hasil kedua` },
      { label: "Kalibrasi historis", value: clamp((calib - 0.85) / 0.2, 0, 1), note: calibNote },
    ],
  };
}

function ppg(w: number | null, d: number | null, p: number | null) {
  if (!ok(w) || !ok(d) || !ok(p) || p <= 0) return null;
  return (3 * w + d) / p;
}

const f2 = (x: number) => x.toFixed(2);

export function detectConflicts(input: MatchInput, signals: Signal[], combined: { lh: number; la: number }, x12: { home: number; draw: number; away: number }): Conflict[] {
  const H = input.home, A = input.away;
  const out: Conflict[] = [];
  const fH = parseForm(H.form), fA = parseForm(A.form);

  // 1) Klasemen vs form
  if (ok(H.position) && ok(A.position) && fH.n >= 3 && fA.n >= 3 && H.position !== A.position) {
    const better = H.position < A.position ? { t: H, f: fH } : { t: A, f: fA };
    const worse = better.t === H ? { t: A, f: fA } : { t: H, f: fH };
    if (better.f.ppg < worse.f.ppg - 0.5) {
      out.push({
        title: "Klasemen vs form terbaru",
        detail: `${better.t.name} lebih tinggi di klasemen (#${better.t.position} vs #${worse.t.position}), tetapi form ${better.f.n} laga terakhirnya lebih buruk (${f2(better.f.ppg)} vs ${f2(worse.f.ppg)} poin/laga).`,
        resolution: "Form terbaru dan data kandang/tandang diberi bobot lebih besar daripada posisi klasemen. Namun 5 laga adalah sampel kecil, jadi koreksinya moderat.",
        severity: 2,
      });
    }
  }

  // 2) Venue vs keseluruhan
  if (!input.neutral) {
    const hAll = ppg(H.wins, H.draws, H.played), hVen = ppg(H.venueWins, H.venueDraws, H.venuePlayed);
    if (hAll !== null && hVen !== null && hVen < hAll - 0.35)
      out.push({
        title: `${H.name} lebih lemah di kandang`,
        detail: `Poin per laga kandang ${f2(hVen)} lebih rendah dari keseluruhan ${f2(hAll)}. Keunggulan kandang tidak bisa diasumsikan.`,
        resolution: "Model memakai rekor kandang spesifik (sinyal Kandang/Tandang), bukan asumsi umum bahwa tuan rumah selalu diuntungkan.",
        severity: 2,
      });
    const aAll = ppg(A.wins, A.draws, A.played), aVen = ppg(A.venueWins, A.venueDraws, A.venuePlayed);
    if (aAll !== null && aVen !== null && aVen > aAll + 0.3)
      out.push({
        title: `${A.name} kuat saat tandang`,
        detail: `Poin per laga tandang ${f2(aVen)} lebih tinggi dari keseluruhan ${f2(aAll)}.`,
        resolution: "Rekor tandang dipakai langsung; ini menekan peluang tuan rumah.",
        severity: 1,
      });
    if (hAll !== null && hVen !== null && aAll !== null && aVen !== null) {
      const favTable = hAll >= aAll ? "H" : "A";
      const favVenue = hVen >= aVen ? "H" : "A";
      if (favTable !== favVenue && Math.abs(hVen - aVen) > 0.4)
        out.push({
          title: "Performa keseluruhan vs kandang/tandang",
          detail: `Secara keseluruhan ${favTable === "H" ? H.name : A.name} lebih baik, tetapi berdasarkan rekor kandang/tandang ${favVenue === "H" ? H.name : A.name} yang unggul (${f2(hVen)} vs ${f2(aVen)} ppg).`,
          resolution: "Untuk laga ini data kandang (tuan rumah) dan tandang (tamu) lebih relevan, sehingga diberi bobot lebih tinggi.",
          severity: 2,
        });
    }
  }

  // 3) Gol vs xG
  for (const t of [H, A]) {
    if (ok(t.played) && t.played > 0 && ok(t.gf) && ok(t.xgFor)) {
      const g = t.gf / t.played;
      if (g - t.xgFor >= 0.35)
        out.push({
          title: `${t.name} mencetak gol di atas xG`,
          detail: `${f2(g)} gol/laga dari xG ${f2(t.xgFor)}. Penyelesaian akhir sangat efisien; secara statistik cenderung turun (regresi ke rata-rata).`,
          resolution: "xG diberi bobot tinggi sehingga ekspektasi gol tim ini sedikit ditekan dibanding angka gol aktualnya.",
          severity: 1,
        });
      else if (t.xgFor - g >= 0.35)
        out.push({
          title: `${t.name} mencetak gol di bawah xG`,
          detail: `${f2(g)} gol/laga padahal xG ${f2(t.xgFor)}. Peluang tercipta tetapi penyelesaian buruk; ada potensi gol "tertunda".`,
          resolution: "Model memadukan gol aktual dan xG, sehingga ekspektasi gol tim ini sedikit dinaikkan.",
          severity: 1,
        });
    }
    if (ok(t.played) && t.played > 0 && ok(t.ga) && ok(t.xgAgainst)) {
      const ga = t.ga / t.played;
      if (t.xgAgainst - ga >= 0.35)
        out.push({
          title: `Pertahanan ${t.name} lebih rapuh dari angka kebobolan`,
          detail: `Kebobolan ${f2(ga)}/laga, tetapi xGA ${f2(t.xgAgainst)}. Kiper/keberuntungan menutupi kelemahan.`,
          resolution: "Ekspektasi gol lawan dinaikkan mengikuti xGA.",
          severity: 1,
        });
    }
  }

  // 4) Sinyal tidak sepakat soal favorit
  const sigs = signals.filter((s) => s.key !== "league" && s.weight > 0.05);
  const favH = sigs.filter((s) => Math.log(s.lh / s.la) > 0.18);
  const favA = sigs.filter((s) => Math.log(s.lh / s.la) < -0.18);
  if (favH.length && favA.length) {
    const wH = favH.reduce((s, x) => s + x.weight, 0), wA = favA.reduce((s, x) => s + x.weight, 0);
    out.push({
      title: "Sumber data tidak sepakat soal favorit",
      detail: `Mendukung ${H.name}: ${favH.map((s) => s.label).join(", ")}. Mendukung ${A.name}: ${favA.map((s) => s.label).join(", ")}.`,
      resolution: `Dipakai rata-rata berbobot (bobot dipelajari dari akurasi historis tiap sumber). Bobot ${wH > wA ? H.name : A.name} lebih besar (${f2(Math.max(wH, wA))} vs ${f2(Math.min(wH, wA))}), namun keyakinan diturunkan.`,
      severity: 3,
    });
  }

  // 5) H2H vs model
  const h2hSig = signals.find((s) => s.key === "h2h");
  if (h2hSig && input.h2h.length >= 2) {
    const h2hSup = input.h2h.reduce((s, m) => s + (m.hg - m.ag), 0) / input.h2h.length;
    const modelSup = Math.log(combined.lh / combined.la);
    if (Math.abs(h2hSup) >= 0.8 && Math.sign(h2hSup) !== Math.sign(modelSup) && Math.abs(modelSup) > 0.1)
      out.push({
        title: "Head-to-head berlawanan dengan data terkini",
        detail: `Rata-rata selisih gol H2H ${h2hSup > 0 ? "+" : ""}${f2(h2hSup)} untuk ${H.name}, sedangkan data terkini mengunggulkan ${modelSup > 0 ? H.name : A.name}.`,
        resolution: "H2H diberi bobot rendah: sampel kecil dan komposisi skuad/pelatih biasanya sudah berubah.",
        severity: 1,
      });
  }

  // 6) Pasar vs model
  const o = input.odds;
  if (ok(o.home) && ok(o.draw) && ok(o.away) && o.home > 1 && o.draw > 1 && o.away > 1) {
    const fair = removeMargin([o.home, o.draw, o.away]);
    const diffs = [x12.home - fair[0], x12.draw - fair[1], x12.away - fair[2]];
    const maxD = Math.max(...diffs.map(Math.abs));
    const favMarket = fair.indexOf(Math.max(...fair));
    const favModel = [x12.home, x12.draw, x12.away].indexOf(Math.max(x12.home, x12.draw, x12.away));
    if (favMarket !== favModel || maxD > 0.1) {
      const names = [H.name, "Seri", A.name];
      out.push({
        title: "Model berbeda dengan pasar taruhan",
        detail: `Pasar (margin dihapus): ${names.map((n, i) => `${n} ${Math.round(fair[i] * 100)}%`).join(" · ")}. Model: ${Math.round(x12.home * 100)}% · ${Math.round(x12.draw * 100)}% · ${Math.round(x12.away * 100)}%.`,
        resolution: "Pasar sering sudah memasukkan berita terbaru (cedera, rotasi, susunan pemain). Cek berita dulu; bila tidak ada info baru, selisih ini bisa menjadi value.",
        severity: favMarket !== favModel ? 3 : 2,
      });
    }
  }

  // 7) Sampel kecil
  const minPlayed = Math.min(H.played ?? 0, A.played ?? 0);
  if ((ok(H.played) || ok(A.played)) && minPlayed > 0 && minPlayed < 6)
    out.push({
      title: "Sampel musim ini masih kecil",
      detail: `Baru ${minPlayed} laga dimainkan. Rata-rata gol mudah terdistorsi satu-dua skor ekstrem.`,
      resolution: "Angka disusutkan ke rata-rata liga (shrinkage) dan keyakinan diturunkan.",
      severity: 2,
    });

  // 8) Absen penting di tim favorit
  const favTeam = x12.home >= x12.away ? H : A;
  const ms = missingShare(favTeam);
  if (ms >= 0.25)
    out.push({
      title: `Tim unggulan (${favTeam.name}) kehilangan pencetak gol`,
      detail: `Pemain absen/diragukan menyumbang sekitar ${Math.round(ms * 100)}% gol tim.`,
      resolution: "Ekspektasi gol tim ini sudah dikurangi; pertimbangkan pasaran yang tidak bergantung pada kemenangan besar (DC/DNB/handicap kecil).",
      severity: 2,
    });

  // 9) Tren form vs musim
  for (const t of [H, A]) {
    const f = parseForm(t.form);
    const n = f.n || 5;
    if (ok(t.formGF) && ok(t.played) && t.played > 0 && ok(t.gf)) {
      const d = t.formGF / n - t.gf / t.played;
      if (Math.abs(d) >= 0.7)
        out.push({
          title: `Tren gol ${t.name} berubah`,
          detail: `Rata-rata gol ${n} laga terakhir ${f2(t.formGF / n)} vs musim ${f2(t.gf / t.played)}.`,
          resolution: "Keduanya dipadukan; form terbaru diberi bobot, tetapi tidak dianggap permanen.",
          severity: 1,
        });
    }
  }

  return out.sort((a, b) => b.severity - a.severity);
}

export function strongestEvidence(input: MatchInput, signals: Signal[]): Evidence[] {
  const H = input.home, A = input.away;
  const ev: Evidence[] = [];
  for (const s of signals) {
    if (s.key === "league") continue;
    const sup = Math.log(s.lh / s.la);
    const favors = sup > 0.1 ? "H" : sup < -0.1 ? "A" : "neutral";
    const who = favors === "H" ? `mendukung ${H.name}` : favors === "A" ? `mendukung ${A.name}` : "seimbang";
    ev.push({
      text: `${s.label}: ${s.detail} → ${who}; ekspektasi ${f2(s.lh)}-${f2(s.la)}.`,
      favors,
      strength: s.weight * (Math.abs(sup) + 0.3 * Math.abs(Math.log((s.lh + s.la) / 2.6))),
    });
  }
  const venue = (t: TeamInput, where: string) => {
    if (!ok(t.venuePlayed) || t.venuePlayed <= 0 || !ok(t.venueWins)) return;
    const wr = t.venueWins / t.venuePlayed;
    const lr = ok(t.venueLosses) ? t.venueLosses / t.venuePlayed : null;
    ev.push({
      text: `${t.name} menang ${t.venueWins}/${t.venuePlayed} laga ${where}${lr !== null ? `, kalah ${t.venueLosses}` : ""}.`,
      favors: t === H ? (wr > 0.5 ? "H" : "neutral") : wr > 0.4 ? "A" : "neutral",
      strength: Math.abs(wr - 0.4) * 1.6 * Math.min(1, t.venuePlayed / 6),
    });
  };
  if (!input.neutral) {
    venue(H, "kandang");
    venue(A, "tandang");
  }
  for (const t of [H, A]) {
    if (ok(t.cleanSheets) && ok(t.played) && t.played > 0) {
      const r = t.cleanSheets / t.played;
      if (r >= 0.4 || r <= 0.12)
        ev.push({ text: `${t.name} clean sheet di ${t.cleanSheets}/${t.played} laga (${Math.round(r * 100)}%).`, favors: "goals", strength: Math.abs(r - 0.28) * 2 });
    }
    if (ok(t.failedToScore) && ok(t.played) && t.played > 0) {
      const r = t.failedToScore / t.played;
      if (r >= 0.35)
        ev.push({ text: `${t.name} gagal mencetak gol di ${t.failedToScore}/${t.played} laga (${Math.round(r * 100)}%).`, favors: "goals", strength: (r - 0.2) * 2 });
    }
    const ms = missingShare(t);
    if (ms >= 0.2) ev.push({ text: `${t.name} kehilangan pemain yang menyumbang ±${Math.round(ms * 100)}% gol tim.`, favors: t === H ? "A" : "H", strength: ms * 2 });
  }
  return ev.sort((a, b) => b.strength - a.strength).slice(0, 6);
}
