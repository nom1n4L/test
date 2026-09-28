// Corner, kartu, penalti, kartu merah, dan pencetak gol.

import { leagueByKey } from "./leagues";
import { clamp, convolve, negBinPmf } from "./math";
import { teamGoalTotal } from "./model";
import type { MatchInput, ModelParams, Player, TeamInput } from "./types";

export interface CountPred {
  lambda: number;
  home: number;
  away: number;
  dist: number[];
  lines: { line: number; over: number }[];
  firstHalf: number; // ekspektasi babak 1
  fhLines: { line: number; over: number }[];
  homeMore: number;
  awayMore: number;
  equal: number;
  dataQuality: "baik" | "sebagian" | "default liga";
}

function overLine(dist: number[], line: number) {
  let p = 0;
  dist.forEach((v, k) => {
    if (k > line) p += v;
  });
  return p;
}

function countModel(home: number, away: number, rTeam: number, lines: number[], fhShare: number, fhLinesArr: number[], max: number, quality: CountPred["dataQuality"]): CountPred {
  const dh = negBinPmf(home, rTeam, max);
  const da = negBinPmf(away, rTeam, max);
  const dist = convolve(dh, da);
  let hm = 0, am = 0, eq = 0;
  for (let i = 0; i < dh.length; i++)
    for (let j = 0; j < da.length; j++) {
      const p = dh[i] * da[j];
      if (i > j) hm += p;
      else if (j > i) am += p;
      else eq += p;
    }
  const fhL = (home + away) * fhShare;
  const fhDist = convolve(negBinPmf(home * fhShare, rTeam, max), negBinPmf(away * fhShare, rTeam, max));
  return {
    lambda: home + away,
    home,
    away,
    dist,
    lines: lines.map((line) => ({ line, over: overLine(dist, line) })),
    firstHalf: fhL,
    fhLines: fhLinesArr.map((line) => ({ line, over: overLine(fhDist, line) })),
    homeMore: hm,
    awayMore: am,
    equal: eq,
    dataQuality: quality,
  };
}

export function rawCorners(input: MatchInput) {
  const lg = leagueByKey(input.leagueKey);
  const half = lg.corners / 2;
  const H = input.home, A = input.away;
  const homeBias = input.neutral ? 1 : 1.06;
  const pick = (v: number | null) => (v !== null && Number.isFinite(v) && v > 0 ? v : null);
  const hf = pick(H.cornersFor), ha = pick(H.cornersAgainst), af = pick(A.cornersFor), aa = pick(A.cornersAgainst);
  const known = [hf, ha, af, aa].filter((x) => x !== null).length;
  // rata-rata "dibuat" dan "lawan kebobolan", disusutkan ke rata-rata liga
  const est = (forV: number | null, agV: number | null, bias: number) => {
    const xs = [forV, agV].filter((x): x is number => x !== null);
    const mean = (xs.reduce((s, v) => s + v, 0) + half * 0.6) / (xs.length + 0.6);
    return mean * bias;
  };
  const home = est(hf, aa, homeBias);
  const away = est(af, ha, 1 / homeBias);
  const quality: CountPred["dataQuality"] = known >= 4 ? "baik" : known >= 2 ? "sebagian" : "default liga";
  return { home, away, quality };
}

export function predictCorners(input: MatchInput, params: ModelParams): CountPred {
  const r = rawCorners(input);
  return countModel(r.home * params.cornerScale, r.away * params.cornerScale, 14, [7.5, 8.5, 9.5, 10.5, 11.5, 12.5], 0.46, [3.5, 4.5, 5.5], 30, r.quality);
}

/** Kedekatan kekuatan dua tim (0 = timpang, 1 = seimbang). */
export function closeness(pHome: number, pAway: number) {
  return clamp(1 - Math.abs(pHome - pAway), 0, 1);
}

export function rawCards(input: MatchInput, pHome: number, pAway: number) {
  const lg = leagueByKey(input.leagueKey);
  const half = lg.cards / 2;
  const H = input.home, A = input.away;
  const pick = (v: number | null) => (v !== null && Number.isFinite(v) && v >= 0 ? v : null);
  const hy = pick(H.yellowPg), ay = pick(A.yellowPg);
  const known = [hy, ay].filter((x) => x !== null).length;
  let home = hy !== null ? (hy * 3 + half) / 4 : half;
  let away = ay !== null ? (ay * 3 + half) / 4 : half;
  // Tim tamu rata-rata sedikit lebih banyak kartu
  if (!input.neutral) {
    home *= 0.95;
    away *= 1.05;
  }
  let factor = 1;
  const reasons: string[] = [];
  const ref = pick(input.referee.yellowPg);
  if (ref !== null) {
    const rf = clamp(0.5 + 0.5 * (ref / lg.cards), 0.7, 1.4);
    factor *= rf;
    reasons.push(`wasit rata-rata ${ref.toFixed(2)} kartu/laga`);
  }
  if (input.derby) {
    factor *= 1.18;
    reasons.push("derby");
  }
  if (input.importance === "final") factor *= 1.1;
  else if (input.importance === "high") factor *= 1.06;
  if (input.competition === "friendly") factor *= 0.7;
  const cl = closeness(pHome, pAway);
  factor *= 0.92 + 0.16 * cl;
  if (cl > 0.75) reasons.push("kekuatan tim seimbang");
  const quality: CountPred["dataQuality"] = known === 2 ? (ref !== null ? "baik" : "sebagian") : known === 1 ? "sebagian" : "default liga";
  return { home: home * factor, away: away * factor, quality, reasons };
}

export function predictCards(input: MatchInput, params: ModelParams, pHome: number, pAway: number): CountPred & { reasons: string[] } {
  const r = rawCards(input, pHome, pAway);
  const c = countModel(r.home * params.cardScale, r.away * params.cardScale, 9, [2.5, 3.5, 4.5, 5.5, 6.5], 0.38, [0.5, 1.5, 2.5], 20, r.quality);
  return { ...c, reasons: r.reasons };
}

export interface EventPred {
  lambda: number;
  p: number;
  homeShare: number;
  reasons: string[];
}

export function rawPenalty(input: MatchInput, lh: number, la: number) {
  const lg = leagueByKey(input.leagueKey);
  const base = lg.pens;
  const H = input.home, A = input.away;
  const half = base / 2;
  const reasons: string[] = [];
  const rate = (forV: number | null, agV: number | null, fallback: number) => {
    const xs = [forV, agV].filter((x): x is number => x !== null && Number.isFinite(x) && x >= 0);
    return (xs.reduce((s, v) => s + v, 0) + fallback * 1.5) / (xs.length + 1.5);
  };
  // Tim yang lebih menyerang (lambda tinggi) lebih sering masuk kotak penalti
  const T = (input.homeAvg + input.awayAvg) / 2;
  let home = rate(H.pensForPg, A.pensAgainstPg, half) * Math.pow(lh / T, 0.6);
  let away = rate(A.pensForPg, H.pensAgainstPg, half) * Math.pow(la / T, 0.6);
  if (H.pensForPg !== null && H.pensForPg > half * 1.4) reasons.push(`${H.name} sering mendapat penalti (${H.pensForPg.toFixed(2)}/laga)`);
  if (A.pensForPg !== null && A.pensForPg > half * 1.4) reasons.push(`${A.name} sering mendapat penalti (${A.pensForPg.toFixed(2)}/laga)`);
  const ref = input.referee.pensPg;
  if (ref !== null && Number.isFinite(ref)) {
    const f = clamp(0.5 + 0.5 * (ref / base), 0.6, 1.6);
    home *= f;
    away *= f;
    reasons.push(`wasit rata-rata ${ref.toFixed(2)} penalti/laga`);
  }
  if (input.derby) {
    home *= 1.08;
    away *= 1.08;
  }
  return { home, away, reasons };
}

export function predictPenalty(input: MatchInput, params: ModelParams, lh: number, la: number): EventPred {
  const r = rawPenalty(input, lh, la);
  const lambda = (r.home + r.away) * params.penScale;
  return { lambda, p: 1 - Math.exp(-lambda), homeShare: r.home / Math.max(r.home + r.away, 1e-9), reasons: r.reasons };
}

export function rawRed(input: MatchInput, pHome: number, pAway: number) {
  const lg = leagueByKey(input.leagueKey);
  const half = lg.reds / 2;
  const H = input.home, A = input.away;
  const reasons: string[] = [];
  const t = (v: number | null) => (v !== null && Number.isFinite(v) && v >= 0 ? (v * 2 + half * 2) / 4 : half);
  let home = t(H.redPg), away = t(A.redPg);
  const ref = input.referee.redPg;
  if (ref !== null && Number.isFinite(ref)) {
    const f = clamp(0.5 + 0.5 * (ref / lg.reds), 0.6, 1.8);
    home *= f;
    away *= f;
    reasons.push(`wasit rata-rata ${ref.toFixed(2)} kartu merah/laga`);
  }
  if (input.derby) {
    home *= 1.35;
    away *= 1.35;
    reasons.push("derby / rivalitas");
  }
  if (input.importance !== "normal") {
    home *= 1.08;
    away *= 1.08;
  }
  const cl = closeness(pHome, pAway);
  home *= 0.9 + 0.2 * cl;
  away *= 0.9 + 0.2 * cl;
  if (H.redPg !== null && H.redPg > half * 1.6) reasons.push(`${H.name} disiplin rendah (${H.redPg.toFixed(2)} merah/laga)`);
  if (A.redPg !== null && A.redPg > half * 1.6) reasons.push(`${A.name} disiplin rendah (${A.redPg.toFixed(2)} merah/laga)`);
  return { home, away, reasons };
}

export function predictRed(input: MatchInput, params: ModelParams, pHome: number, pAway: number): EventPred {
  const r = rawRed(input, pHome, pAway);
  const lambda = (r.home + r.away) * params.redScale;
  return { lambda, p: 1 - Math.exp(-lambda), homeShare: r.home / Math.max(r.home + r.away, 1e-9), reasons: r.reasons };
}

// ---------- Pencetak gol ----------

export interface ScorerPred {
  id: string;
  name: string;
  team: "H" | "A";
  pos: Player["pos"];
  lambda: number; // ekspektasi gol di laga ini
  anytime: number;
  first: number;
  twoPlus: number;
  playProb: number;
  basis: string;
}

const POS_PRIOR: Record<Player["pos"], number> = { FWD: 0.2, MID: 0.08, DEF: 0.035, GK: 0.001 };

export function predictScorers(team: TeamInput, side: "H" | "A", lambdaTeam: number, lambdaTotal: number, p00: number, penLambdaTeam: number): ScorerPred[] {
  const total = teamGoalTotal(team);
  const played = team.played && team.played > 0 ? team.played : null;
  const teamPerMatch = played ? total / played : null;
  const res: ScorerPred[] = [];
  for (const p of team.players) {
    if (!p.name.trim()) continue;
    const playProb = p.status === "out" ? 0 : p.status === "doubt" ? 0.45 : 0.88;
    let share: number;
    let basis: string;
    const apps = p.apps && p.apps > 0 ? p.apps : p.minutes && p.minutes > 0 ? Math.max(1, Math.round(p.minutes / 80)) : null;
    if (p.xg !== null && p.xg > 0 && apps && teamPerMatch) {
      // xG per penampilan relatif terhadap gol tim per laga
      const s = p.xg / apps / teamPerMatch;
      share = (s * apps + POS_PRIOR[p.pos] * 3) / (apps + 3);
      basis = `xG ${p.xg.toFixed(1)} dari ${apps} laga`;
    } else if (p.goals !== null && apps && teamPerMatch) {
      const s = p.goals / apps / teamPerMatch;
      share = (s * apps + POS_PRIOR[p.pos] * 4) / (apps + 4);
      basis = `${p.goals} gol dari ${apps} laga`;
    } else if (p.goals !== null) {
      const s = p.goals / total;
      share = (s * 3 + POS_PRIOR[p.pos]) / 4;
      basis = `${p.goals} gol (${Math.round(s * 100)}% gol tim)`;
    } else {
      share = POS_PRIOR[p.pos];
      basis = `prior posisi ${p.pos}`;
    }
    // Pemain inti (rata-rata menit tinggi) lebih mungkin tampil penuh
    if (p.minutes && apps && p.minutes / apps < 45) basis += " · sering dari bangku cadangan";
    share = clamp(share, 0, 0.75);
    let lambda = lambdaTeam * share * playProb;
    if (p.penTaker) lambda += penLambdaTeam * 0.78 * playProb * 0.5; // sebagian penalti sudah ada di gol musim ini
    const anytime = 1 - Math.exp(-lambda);
    res.push({
      id: p.id,
      name: p.name,
      team: side,
      pos: p.pos,
      lambda,
      anytime,
      first: (lambda / Math.max(lambdaTotal, 1e-9)) * (1 - p00),
      twoPlus: 1 - Math.exp(-lambda) * (1 + lambda),
      playProb,
      basis: p.penTaker ? `${basis} · algojo penalti` : basis,
    });
  }
  res.sort((a, b) => b.anytime - a.anytime);
  return res;
}

export function likelihoodLabel(p: number): string {
  if (p >= 0.5) return "TINGGI";
  if (p >= 0.35) return "SEDANG-TINGGI";
  if (p >= 0.22) return "SEDANG";
  if (p >= 0.12) return "RENDAH";
  return "SANGAT RENDAH";
}
