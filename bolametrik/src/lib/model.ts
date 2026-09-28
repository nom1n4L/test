// Model inti: estimasi ekspektasi gol (lambda) dari banyak sinyal data, lalu
// matriks skor Dixon-Coles untuk FT, babak 1 dan babak 2.

import type { MatchInput, ModelParams, SignalKey, TeamInput } from "./types";
import { clamp, indepMatrix, type Matrix, scoreMatrix } from "./math";

export const SIGNAL_KEYS: SignalKey[] = ["season", "venue", "form", "xg", "shots", "table", "formPts", "h2h", "league"];

export const BASE_WEIGHTS: Record<SignalKey, number> = {
  season: 1.0,
  venue: 1.1,
  form: 1.0,
  xg: 1.3,
  shots: 0.7,
  table: 0.4,
  formPts: 0.35,
  h2h: 0.35,
  league: 0.45,
};

export const SIGNAL_LABEL: Record<SignalKey, string> = {
  season: "Gol musim ini (keseluruhan)",
  venue: "Rekor kandang / tandang",
  form: "Gol di laga-laga terakhir",
  xg: "xG / xGA",
  shots: "Tembakan tepat sasaran",
  table: "Poin per laga musim ini",
  formPts: "Poin dari form terakhir",
  h2h: "Head-to-head",
  league: "Rata-rata liga (prior)",
};

export function defaultParams(): ModelParams {
  const weights = {} as Record<SignalKey, number>;
  for (const k of SIGNAL_KEYS) weights[k] = 1;
  return {
    goalScale: 1,
    homeAdv: 1,
    drawBoost: 1.04,
    rho: -0.07,
    fhShare: 0.445,
    cornerScale: 1,
    cardScale: 1,
    penScale: 1,
    redScale: 1,
    weights,
    leagueFactor: {},
    market: {},
    aiWeight: 0.3,
    learned: 0,
  };
}

const has = (...xs: (number | null | undefined)[]) => xs.every((x) => x !== null && x !== undefined && Number.isFinite(x));
const shrink = (total: number, n: number, avg: number, k: number) => (total + k * avg) / (n + k);

export interface FormStats {
  n: number;
  w: number;
  d: number;
  l: number;
  ppg: number;
  letters: string;
}

export function parseForm(form: string): FormStats {
  const letters = (form || "")
    .toUpperCase()
    .replace(/M/g, "W")
    .replace(/S/g, "D")
    .replace(/K/g, "L")
    .replace(/[^WDL]/g, "");
  let w = 0, d = 0, l = 0;
  for (const c of letters) {
    if (c === "W") w++;
    else if (c === "D") d++;
    else l++;
  }
  const n = letters.length;
  return { n, w, d, l, ppg: n ? (3 * w + d) / n : 0, letters };
}

export interface Signal {
  key: SignalKey;
  label: string;
  lh: number;
  la: number;
  rel: number; // reliabilitas berdasar ukuran sampel (0..1)
  weight: number; // bobot efektif = dasar x dipelajari x reliabilitas
  detail: string;
}

function teamPpg(t: TeamInput): number | null {
  if (!has(t.played, t.wins, t.draws) || !t.played) return null;
  return (3 * (t.wins as number) + (t.draws as number)) / (t.played as number);
}

export function computeSignals(input: MatchInput, params: ModelParams): Signal[] {
  const { home: H, away: A } = input;
  const hAvg = input.homeAvg || 1.45;
  const aAvg = input.awayAvg || 1.15;
  const T = (hAvg + aAvg) / 2;
  const baseH = input.neutral ? T : hAvg;
  const baseA = input.neutral ? T : aAvg;
  const out: Omit<Signal, "weight">[] = [];

  // 1) Musim keseluruhan
  if (has(H.played, H.gf, H.ga, A.played, A.gf, A.ga) && (H.played as number) > 0 && (A.played as number) > 0) {
    const pH = H.played as number, pA = A.played as number;
    const attH = shrink(H.gf as number, pH, T, 3) / T;
    const defH = shrink(H.ga as number, pH, T, 3) / T;
    const attA = shrink(A.gf as number, pA, T, 3) / T;
    const defA = shrink(A.ga as number, pA, T, 3) / T;
    const n = Math.min(pH, pA);
    out.push({
      key: "season",
      label: SIGNAL_LABEL.season,
      lh: baseH * attH * defA,
      la: baseA * attA * defH,
      rel: n / (n + 8),
      detail: `${H.name}: ${fmt(H.gf! / pH)} gol / ${fmt(H.ga! / pH)} kebobolan per laga · ${A.name}: ${fmt(A.gf! / pA)} / ${fmt(A.ga! / pA)}`,
    });
  }

  // 2) Kandang (tuan rumah) vs tandang (tamu)
  if (!input.neutral && has(H.venuePlayed, H.venueGF, H.venueGA, A.venuePlayed, A.venueGF, A.venueGA) && (H.venuePlayed as number) > 0 && (A.venuePlayed as number) > 0) {
    const vH = H.venuePlayed as number, vA = A.venuePlayed as number;
    const attH = shrink(H.venueGF as number, vH, hAvg, 3) / hAvg;
    const defA = shrink(A.venueGA as number, vA, hAvg, 3) / hAvg;
    const attA = shrink(A.venueGF as number, vA, aAvg, 3) / aAvg;
    const defH = shrink(H.venueGA as number, vH, aAvg, 3) / aAvg;
    const n = Math.min(vH, vA);
    out.push({
      key: "venue",
      label: SIGNAL_LABEL.venue,
      lh: hAvg * attH * defA,
      la: aAvg * attA * defH,
      rel: n / (n + 5),
      detail: `${H.name} di kandang: ${fmt(H.venueGF! / vH)} / ${fmt(H.venueGA! / vH)} per laga · ${A.name} tandang: ${fmt(A.venueGF! / vA)} / ${fmt(A.venueGA! / vA)}`,
    });
  }

  // 3) Gol di laga terakhir
  const fH = parseForm(H.form);
  const fA = parseForm(A.form);
  if (has(H.formGF, H.formGA, A.formGF, A.formGA)) {
    const nH = fH.n || 5, nA = fA.n || 5;
    const attH = shrink(H.formGF as number, nH, T, 2) / T;
    const defH = shrink(H.formGA as number, nH, T, 2) / T;
    const attA = shrink(A.formGF as number, nA, T, 2) / T;
    const defA = shrink(A.formGA as number, nA, T, 2) / T;
    const n = Math.min(nH, nA);
    out.push({
      key: "form",
      label: SIGNAL_LABEL.form,
      lh: baseH * attH * defA,
      la: baseA * attA * defH,
      rel: n / (n + 4),
      detail: `${nH} laga terakhir ${H.name}: ${H.formGF}-${H.formGA} · ${nA} laga terakhir ${A.name}: ${A.formGF}-${A.formGA}`,
    });
  }

  // 4) xG
  if (has(H.xgFor, H.xgAgainst, A.xgFor, A.xgAgainst)) {
    const nH = has(H.played) ? (H.played as number) : 6;
    const nA = has(A.played) ? (A.played as number) : 6;
    const attH = shrink((H.xgFor as number) * nH, nH, T, 2) / T;
    const defH = shrink((H.xgAgainst as number) * nH, nH, T, 2) / T;
    const attA = shrink((A.xgFor as number) * nA, nA, T, 2) / T;
    const defA = shrink((A.xgAgainst as number) * nA, nA, T, 2) / T;
    const n = Math.min(nH, nA);
    out.push({
      key: "xg",
      label: SIGNAL_LABEL.xg,
      lh: baseH * attH * defA,
      la: baseA * attA * defH,
      rel: n / (n + 3),
      detail: `xG ${H.name} ${fmt(H.xgFor!)} / xGA ${fmt(H.xgAgainst!)} · xG ${A.name} ${fmt(A.xgFor!)} / xGA ${fmt(A.xgAgainst!)}`,
    });
  }

  // 5) Tembakan tepat sasaran (proxy peluang)
  if (has(H.sotFor, H.sotAgainst, A.sotFor, A.sotAgainst)) {
    // ~31% tembakan tepat sasaran berbuah gol; pangkat 0.85 = penyusutan ringan ke rata-rata
    const conv = 0.31;
    const attH = (conv * (H.sotFor as number)) / T;
    const defH = (conv * (H.sotAgainst as number)) / T;
    const attA = (conv * (A.sotFor as number)) / T;
    const defA = (conv * (A.sotAgainst as number)) / T;
    out.push({
      key: "shots",
      label: SIGNAL_LABEL.shots,
      lh: baseH * Math.pow(Math.max(attH * defA, 0.05), 0.85),
      la: baseA * Math.pow(Math.max(attA * defH, 0.05), 0.85),
      rel: 0.6,
      detail: `SoT ${H.name} ${fmt(H.sotFor!)} (lawan ${fmt(H.sotAgainst!)}) · SoT ${A.name} ${fmt(A.sotFor!)} (lawan ${fmt(A.sotAgainst!)})`,
    });
  }

  // 6) Poin per laga musim ini
  const ppgH = teamPpg(H), ppgA = teamPpg(A);
  if (ppgH !== null && ppgA !== null) {
    const diff = ppgH - ppgA;
    const n = Math.min(H.played as number, A.played as number);
    out.push({
      key: "table",
      label: SIGNAL_LABEL.table,
      lh: baseH * Math.exp(0.2 * diff),
      la: baseA * Math.exp(-0.2 * diff),
      rel: n / (n + 8),
      detail: `PPG ${H.name} ${fmt(ppgH)} vs ${A.name} ${fmt(ppgA)}`,
    });
  }

  // 7) Poin form
  if (fH.n >= 3 && fA.n >= 3) {
    const diff = fH.ppg - fA.ppg;
    const n = Math.min(fH.n, fA.n);
    out.push({
      key: "formPts",
      label: SIGNAL_LABEL.formPts,
      lh: baseH * Math.exp(0.14 * diff),
      la: baseA * Math.exp(-0.14 * diff),
      rel: n / (n + 5),
      detail: `Form ${H.name} ${fH.letters} (${fmt(fH.ppg)} ppg) vs ${A.name} ${fA.letters} (${fmt(fA.ppg)} ppg)`,
    });
  }

  // 8) Head-to-head
  const h2h = input.h2h.filter((m) => Number.isFinite(m.hg) && Number.isFinite(m.ag));
  if (h2h.length > 0) {
    const ratio = hAvg / aAvg;
    let sh = 0, sa = 0;
    for (const m of h2h) {
      if (m.homeAtHome || input.neutral) {
        sh += m.hg;
        sa += m.ag;
      } else {
        sh += m.hg * ratio;
        sa += m.ag / ratio;
      }
    }
    const n = h2h.length;
    out.push({
      key: "h2h",
      label: SIGNAL_LABEL.h2h,
      lh: shrink(sh, n, baseH, 3),
      la: shrink(sa, n, baseA, 3),
      rel: n / (n + 6),
      detail: `${n} laga H2H: rata-rata ${fmt(h2h.reduce((s, m) => s + m.hg, 0) / n)} - ${fmt(h2h.reduce((s, m) => s + m.ag, 0) / n)}`,
    });
  }

  // 9) Prior liga
  out.push({ key: "league", label: SIGNAL_LABEL.league, lh: baseH, la: baseA, rel: 1, detail: `Rata-rata ${input.leagueName}: ${fmt(hAvg)} - ${fmt(aAvg)}` });

  return out.map((s) => ({ ...s, lh: clamp(s.lh, 0.1, 6), la: clamp(s.la, 0.1, 6), weight: BASE_WEIGHTS[s.key] * (params.weights[s.key] ?? 1) * s.rel }));
}

export function combineSignals(signals: Signal[]): { lh: number; la: number } {
  let wsum = 0, lh = 0, la = 0;
  for (const s of signals) {
    wsum += s.weight;
    lh += s.weight * Math.log(s.lh);
    la += s.weight * Math.log(s.la);
  }
  return { lh: Math.exp(lh / wsum), la: Math.exp(la / wsum) };
}

const ABS_ATT = [1, 0.95, 0.89, 0.82];
const ABS_DEF = [1, 1.05, 1.11, 1.18];

export interface Adjustment {
  label: string;
  home: number; // faktor pengali lambda tuan rumah
  away: number;
}

export function teamGoalTotal(t: TeamInput): number {
  const fromPlayers = t.players.reduce((s, p) => s + (p.goals ?? 0), 0);
  return Math.max(t.gf ?? 0, fromPlayers, 1);
}

/** Pangsa gol yang hilang karena pemain absen / diragukan. */
export function missingShare(t: TeamInput): number {
  const total = teamGoalTotal(t);
  let s = 0;
  for (const p of t.players) {
    const g = p.goals ?? 0;
    if (p.status === "out") s += g / total;
    else if (p.status === "doubt") s += (0.45 * g) / total;
  }
  return clamp(s, 0, 0.8);
}

export function contextAdjustments(input: MatchInput): Adjustment[] {
  const { home: H, away: A } = input;
  const adj: Adjustment[] = [];
  const idx = (x: number) => clamp(Math.round(x || 0), 0, 3);
  if (idx(H.absAttack) || idx(A.absDefense))
    adj.push({ label: `Absen lini serang ${H.name} / lini belakang ${A.name}`, home: ABS_ATT[idx(H.absAttack)] * ABS_DEF[idx(A.absDefense)], away: 1 });
  if (idx(A.absAttack) || idx(H.absDefense))
    adj.push({ label: `Absen lini serang ${A.name} / lini belakang ${H.name}`, home: 1, away: ABS_ATT[idx(A.absAttack)] * ABS_DEF[idx(H.absDefense)] });
  const mH = missingShare(H), mA = missingShare(A);
  if (mH > 0.01) adj.push({ label: `Pencetak gol ${H.name} absen/diragukan (${Math.round(mH * 100)}% gol tim)`, home: Math.max(0.72, 1 - 0.5 * mH), away: 1 });
  if (mA > 0.01) adj.push({ label: `Pencetak gol ${A.name} absen/diragukan (${Math.round(mA * 100)}% gol tim)`, home: 1, away: Math.max(0.72, 1 - 0.5 * mA) });
  if (H.motivation || A.motivation)
    adj.push({ label: "Motivasi / kepentingan laga", home: 1 + 0.03 * (H.motivation || 0) - 0.012 * (A.motivation || 0), away: 1 + 0.03 * (A.motivation || 0) - 0.012 * (H.motivation || 0) });
  if (H.restDays !== null && H.restDays < 3) adj.push({ label: `${H.name} hanya ${H.restDays} hari istirahat`, home: 0.96, away: 1.03 });
  if (A.restDays !== null && A.restDays < 3) adj.push({ label: `${A.name} hanya ${A.restDays} hari istirahat`, home: 1.03, away: 0.96 });
  if (input.derby) adj.push({ label: "Derby: laga cenderung lebih ketat", home: 0.96, away: 0.96 });
  if (input.importance === "final") adj.push({ label: "Final: tempo cenderung hati-hati", home: 0.92, away: 0.92 });
  else if (input.importance === "high") adj.push({ label: "Laga penting: sedikit lebih hati-hati", home: 0.97, away: 0.97 });
  if (input.competition === "friendly") adj.push({ label: "Laga persahabatan: pertahanan lebih longgar", home: 1.08, away: 1.08 });
  return adj;
}

export interface Lambdas {
  signals: Signal[];
  combined: { lh: number; la: number };
  adjustments: Adjustment[];
  raw: { lh: number; la: number }; // sebelum parameter global yang dipelajari
  lh: number;
  la: number;
  fh: number; // porsi gol babak 1 tuan rumah
  fa: number;
}

export function leagueFactorOf(params: ModelParams, key: string) {
  return params.leagueFactor[key] ?? 1;
}

export function applyLearned(raw: { lh: number; la: number }, neutral: boolean, leagueKey: string, params: ModelParams) {
  const lf = leagueFactorOf(params, leagueKey);
  const ha = neutral ? 1 : params.homeAdv;
  return {
    lh: clamp(raw.lh * params.goalScale * lf * ha, 0.12, 5.5),
    la: clamp(raw.la * params.goalScale * lf / ha, 0.12, 5.5),
  };
}

export function halfShares(input: MatchInput, params: ModelParams) {
  const f = params.fhShare;
  const blend = (a: number | null, b: number | null) => {
    const xs = [a, b].filter((x): x is number => x !== null && Number.isFinite(x) && x > 0 && x < 100).map((x) => x / 100);
    return clamp((xs.reduce((s, v) => s + v, 0) + f * 1.5) / (xs.length + 1.5), 0.3, 0.6);
  };
  return {
    fh: blend(input.home.fhGFPct, input.away.fhGAPct),
    fa: blend(input.away.fhGFPct, input.home.fhGAPct),
  };
}

export function computeLambdas(input: MatchInput, params: ModelParams): Lambdas {
  const signals = computeSignals(input, params);
  const combined = combineSignals(signals);
  const adjustments = contextAdjustments(input);
  let lh = combined.lh, la = combined.la;
  for (const a of adjustments) {
    lh *= a.home;
    la *= a.away;
  }
  const raw = { lh, la };
  const fin = applyLearned(raw, input.neutral, input.leagueKey, params);
  const { fh, fa } = halfShares(input, params);
  return { signals, combined, adjustments, raw, lh: fin.lh, la: fin.la, fh, fa };
}

export interface Matrices {
  ft: Matrix;
  ht: Matrix;
  h2: Matrix;
}

export function buildMatrices(l: { lh: number; la: number; fh: number; fa: number }, params: ModelParams): Matrices {
  return {
    ft: scoreMatrix(l.lh, l.la, params.rho, params.drawBoost),
    ht: scoreMatrix(l.lh * l.fh, l.la * l.fa, params.rho * 0.6, 1),
    h2: scoreMatrix(l.lh * (1 - l.fh), l.la * (1 - l.fa), params.rho * 0.4, 1),
  };
}

export { indepMatrix };

function fmt(x: number) {
  return (Math.round(x * 100) / 100).toFixed(2);
}
