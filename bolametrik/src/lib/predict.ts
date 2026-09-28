// Agregator: dari input + parameter → prediksi lengkap.

import { effectiveP, topScores } from "./math";
import { buildMatrices, computeLambdas, type Lambdas, type Matrices } from "./model";
import { computeMarkets, type Markets } from "./markets";
import {
  type CountPred,
  type EventPred,
  likelihoodLabel,
  predictCards,
  predictCorners,
  predictPenalty,
  predictRed,
  predictScorers,
  type ScorerPred,
} from "./extras";
import {
  computeConfidence,
  type Confidence,
  type Conflict,
  detectConflicts,
  type Evidence,
  marketConf,
  strongestEvidence,
  type ConfLabel,
} from "./insights";
import { buildCandidates, type Rec, selectRecs } from "./recommend";
import { ahIndo, fmtLine, fmtTotalLine } from "./odds";
import type { AIOpinion, MatchInput, ModelParams } from "./types";

export interface Scenario {
  ht: [number, number];
  h2: [number, number];
  ft: [number, number];
  p: number; // peluang skor FT ini
  pSplit: number; // peluang pembagian babak ini, bila FT tersebut terjadi
  scorers: { name: string; team: "H" | "A"; goals: number }[];
}

export interface QuickItem {
  key: string;
  label: string;
  answer: string;
  p: number | null;
  conf: ConfLabel | null;
  note?: string;
}

export interface Consensus {
  home: number;
  draw: number;
  away: number;
  over25: number;
  btts: number;
  w: number;
}

export interface Prediction {
  lam: Lambdas;
  M: Matrices;
  mk: Markets;
  corners: CountPred;
  cards: CountPred & { reasons: string[] };
  pen: EventPred;
  red: EventPred;
  scorers: { home: ScorerPred[]; away: ScorerPred[] };
  topFT: { h: number; a: number; p: number }[];
  topHT: { h: number; a: number; p: number }[];
  top2H: { h: number; a: number; p: number }[];
  pick: "1" | "X" | "2";
  scenario: Scenario;
  confidence: Confidence;
  conflicts: Conflict[];
  evidence: Evidence[];
  candidates: Rec[];
  recs: Rec[];
  quick: QuickItem[];
  consensus: Consensus | null;
  goalEnv: "Rendah" | "Sedang" | "Tinggi";
}

function assignScorers(list: ScorerPred[], goals: number, team: "H" | "A") {
  const cand = list.filter((s) => s.playProb > 0 && s.lambda > 0);
  const count = new Map<string, number>();
  const out: { name: string; team: "H" | "A"; goals: number }[] = [];
  for (let g = 0; g < goals; g++) {
    let best: ScorerPred | null = null, bv = -1;
    for (const s of cand) {
      const v = s.lambda * Math.pow(0.4, count.get(s.name) ?? 0);
      if (v > bv) {
        bv = v;
        best = s;
      }
    }
    if (!best) break;
    count.set(best.name, (count.get(best.name) ?? 0) + 1);
  }
  for (const [name, n] of count) out.push({ name, team, goals: n });
  return out;
}

export function predict(input: MatchInput, params: ModelParams, ai: AIOpinion | null = null): Prediction {
  const lam = computeLambdas(input, params);
  const M = buildMatrices(lam, params);
  const mk = computeMarkets(M, lam.lh, lam.la, lam.fh, lam.fa);
  const corners = predictCorners(input, params);
  const cards = predictCards(input, params, mk.x12.home, mk.x12.away);
  const pen = predictPenalty(input, params, lam.lh, lam.la);
  const red = predictRed(input, params, mk.x12.home, mk.x12.away);
  const lt = lam.lh + lam.la;
  const scorers = {
    home: predictScorers(input.home, "H", lam.lh, lt, M.ft[0][0], pen.lambda * pen.homeShare),
    away: predictScorers(input.away, "A", lam.la, lt, M.ft[0][0], pen.lambda * (1 - pen.homeShare)),
  };
  const topFT = topScores(M.ft, 8);
  const topHT = topScores(M.ht, 5);
  const top2H = topScores(M.h2, 5);

  const x = mk.x12;
  const pick: "1" | "X" | "2" = x.home >= x.draw && x.home >= x.away ? "1" : x.away >= x.draw ? "2" : "X";
  // Skor FT utama = skor paling mungkin di dalam hasil yang paling mungkin
  const inPick = topScores(M.ft, 121).find((s) => (pick === "1" ? s.h > s.a : pick === "2" ? s.a > s.h : s.h === s.a))!;
  const [fx, fy] = [inPick.h, inPick.a];
  let best = -1, bi = 0, bj = 0;
  let splitSum = 0;
  for (let i = 0; i <= fx; i++)
    for (let j = 0; j <= fy; j++) {
      const v = M.ht[i][j] * M.h2[fx - i][fy - j];
      splitSum += v;
      if (v > best) {
        best = v;
        bi = i;
        bj = j;
      }
    }
  const scenario: Scenario = {
    ht: [bi, bj],
    h2: [fx - bi, fy - bj],
    ft: [fx, fy],
    p: inPick.p,
    pSplit: best / Math.max(splitSum, 1e-12),
    scorers: [...assignScorers(scorers.home, fx, "H"), ...assignScorers(scorers.away, fy, "A")],
  };

  const confidence = computeConfidence(input, lam.signals, x, params);
  const conflicts = detectConflicts(input, lam.signals, lam.combined, x);
  // Konflik berat menurunkan keyakinan
  const penalty = conflicts.reduce((s, c) => s + (c.severity === 3 ? 0.6 : c.severity === 2 ? 0.25 : 0.08), 0);
  confidence.score = Math.max(1, Math.round((confidence.score - Math.min(penalty, 2)) * 10) / 10);
  confidence.label = confidence.score >= 8 ? "SANGAT TINGGI" : confidence.score >= 6.5 ? "TINGGI" : confidence.score >= 5 ? "SEDANG" : confidence.score >= 3.5 ? "RENDAH" : "SANGAT RENDAH";
  const evidence = strongestEvidence(input, lam.signals);

  const candidates = buildCandidates({ input, mk, corners, cards, lh: lam.lh, la: lam.la, conf: confidence.score });
  const htftTop = Object.entries(mk.htft).sort((a, b) => b[1] - a[1])[0] as [string, number];
  const allScorers = [...scorers.home, ...scorers.away].sort((a, b) => b.anytime - a.anytime);
  const recs = selectRecs(candidates, params, input, { corners, cards, topScore: topFT[0], htft: htftTop, scorer: allScorers[0] });

  const goalEnv = lt < 2.25 ? "Rendah" : lt < 2.95 ? "Sedang" : "Tinggi";

  let consensus: Consensus | null = null;
  if (ai?.probs) {
    const w = params.aiWeight;
    const o25 = mk.totals.find((t) => t.line === 2.5)!.over;
    const s = ai.probs.home + ai.probs.draw + ai.probs.away || 1;
    consensus = {
      home: (1 - w) * x.home + (w * ai.probs.home) / s,
      draw: (1 - w) * x.draw + (w * ai.probs.draw) / s,
      away: (1 - w) * x.away + (w * ai.probs.away) / s,
      over25: ai.probs.over25 !== null ? (1 - w) * o25 + w * ai.probs.over25 : o25,
      btts: ai.probs.btts !== null ? (1 - w) * mk.btts.yes + w * ai.probs.btts : mk.btts.yes,
      w,
    };
  }

  const quick = buildQuick(input, { mk, pick, scenario, topFT, topHT, top2H, htM: M.ht, corners, cards, pen, red, allScorers, htftTop, conf: confidence.score, lh: lam.lh, la: lam.la });

  return { lam, M, mk, corners, cards, pen, red, scorers, topFT, topHT, top2H, pick, scenario, confidence, conflicts, evidence, candidates, recs, quick, consensus, goalEnv };
}

const pct = (p: number) => `${Math.round(p * 100)}%`;

/** Skor tepat selalu berpeluang kecil; label disesuaikan dengan skala itu. */
function exactConf(p: number, good: number): ConfLabel {
  if (p >= good) return "SEDANG";
  if (p >= good * 0.65) return "RENDAH";
  return "SANGAT RENDAH";
}

function levelWord(p: number) {
  if (p >= 0.45) return "TINGGI";
  if (p >= 0.3) return "SEDANG";
  if (p >= 0.18) return "RENDAH-SEDANG";
  return "RENDAH";
}

function buildQuick(
  input: MatchInput,
  c: {
    mk: Markets;
    pick: "1" | "X" | "2";
    scenario: Scenario;
    topFT: { h: number; a: number; p: number }[];
    topHT: { h: number; a: number; p: number }[];
    top2H: { h: number; a: number; p: number }[];
    htM: number[][];
    corners: CountPred;
    cards: CountPred;
    pen: EventPred;
    red: EventPred;
    allScorers: ScorerPred[];
    htftTop: [string, number];
    conf: number;
    lh: number;
    la: number;
  },
): QuickItem[] {
  const H = input.home.name, A = input.away.name;
  const { mk, scenario } = c;
  const q: QuickItem[] = [];
  const htP = (s: [number, number]) => c.htM[s[0]]?.[s[1]] ?? 0;
  const winnerName = c.pick === "1" ? H : c.pick === "2" ? A : "Seri";
  const pW = c.pick === "1" ? mk.x12.home : c.pick === "2" ? mk.x12.away : mk.x12.draw;
  q.push({
    key: "winner",
    label: "Pemenang pertandingan",
    answer: winnerName,
    p: pW,
    conf: marketConf(pW, c.conf),
    note: `${H} ${pct(mk.x12.home)} · Seri ${pct(mk.x12.draw)} · ${A} ${pct(mk.x12.away)}${pW < 0.5 ? ` — lebih aman: ${mk.dc.hx >= mk.dc.xa ? `${H} atau seri (${pct(mk.dc.hx)})` : `${A} atau seri (${pct(mk.dc.xa)})`}` : ""}`,
  });
  q.push({ key: "ht", label: "Skor babak 1", answer: `${scenario.ht[0]}-${scenario.ht[1]}`, p: htP(scenario.ht), conf: exactConf(htP(scenario.ht), 0.3), note: `Paling mungkin: ${c.topHT.slice(0, 3).map((s) => `${s.h}-${s.a} (${pct(s.p)})`).join(", ")}` });
  q.push({ key: "h2", label: "Skor babak 2 (gol di babak 2 saja)", answer: `${scenario.h2[0]}-${scenario.h2[1]}`, p: null, conf: null, note: `Paling mungkin: ${c.top2H.slice(0, 3).map((s) => `${s.h}-${s.a} (${pct(s.p)})`).join(", ")}` });
  const overall = c.topFT[0];
  q.push({
    key: "ft",
    label: "Skor akhir (FT)",
    answer: `${scenario.ft[0]}-${scenario.ft[1]}`,
    p: scenario.p,
    conf: exactConf(scenario.p, 0.15),
    note: overall.h === scenario.ft[0] && overall.a === scenario.ft[1] ? `Juga skor tunggal paling mungkin (${pct(overall.p)})` : `Skor tunggal paling mungkin secara matematis: ${overall.h}-${overall.a} (${pct(overall.p)})`,
  });
  const top3 = c.allScorers.filter((s) => s.anytime > 0.05).slice(0, 3);
  q.push({
    key: "scorer",
    label: "Pencetak gol",
    answer: top3.length ? top3.slice(0, 2).map((s) => s.name).join(", ") : "Belum ada data pemain",
    p: top3[0]?.anytime ?? null,
    conf: null,
    note: top3.length ? top3.map((s) => `${s.name} (${s.team === "H" ? H : A}) ${pct(s.anytime)} · ${likelihoodLabel(s.anytime)}`).join(" · ") : "Tambahkan pemain di formulir untuk prediksi pencetak gol",
  });
  q.push({ key: "pen", label: "Potensi penalti", answer: `${pct(c.pen.p)} — ${levelWord(c.pen.p)}`, p: c.pen.p, conf: null, note: c.pen.reasons.length ? c.pen.reasons.join(" · ") : `Lebih mungkin untuk ${c.pen.homeShare >= 0.5 ? H : A} (${pct(Math.max(c.pen.homeShare, 1 - c.pen.homeShare))})` });
  q.push({ key: "red", label: "Potensi kartu merah / pemain dikeluarkan", answer: `${pct(c.red.p)} — ${levelWord(c.red.p)}`, p: c.red.p, conf: null, note: c.red.reasons.length ? c.red.reasons.join(" · ") : "Berdasar rata-rata liga & disiplin tim" });

  // Handicap: garis paling agresif untuk tim unggulan yang masih ≥ 60% efektif
  const favHome = c.lh >= c.la;
  let ahBest: { line: number; p: number } | null = null;
  for (const r of mk.ah) {
    const line = favHome ? r.line : -r.line;
    const s = favHome ? r.home : r.away;
    const p = effectiveP(s);
    if (p >= 0.6 && (ahBest === null || line < ahBest.line)) ahBest = { line, p };
  }
  const favName = favHome ? H : A;
  if (ahBest)
    q.push({ key: "ah", label: "Rekomendasi handicap", answer: `${favName} ${fmtLine(ahBest.line)} (${ahIndo(ahBest.line)})`, p: ahBest.p, conf: marketConf(ahBest.p, c.conf), note: `Garis adil model: ${H} ${fmtLine(mk.fairAhLine)} (${ahIndo(mk.fairAhLine)})` });

  // O/U: garis yang sisi terbaiknya 60-78%, paling dekat ke 2.5
  let ouBest: { line: number; side: "Over" | "Under"; p: number } | null = null;
  for (const t of mk.totals.filter((t) => t.line >= 1.5 && t.line <= 3.5)) {
    const side = t.over >= t.under ? "Over" : "Under";
    const p = Math.max(t.over, t.under);
    if (p < 0.6 || p > 0.82) continue;
    if (!ouBest || Math.abs(t.line - 2.5) < Math.abs(ouBest.line - 2.5)) ouBest = { line: t.line, side, p };
  }
  const o25 = mk.totals.find((t) => t.line === 2.5)!;
  if (!ouBest) ouBest = { line: 2.5, side: o25.over >= 0.5 ? "Over" : "Under", p: Math.max(o25.over, o25.under) };
  q.push({ key: "ou", label: "Total gol Over/Under", answer: `${ouBest.side} ${ouBest.line}`, p: ouBest.p, conf: marketConf(ouBest.p, c.conf), note: `Ekspektasi total ${(c.lh + c.la).toFixed(2)} gol · Over 2.5 ${pct(o25.over)} · garis adil ${fmtTotalLine(mk.fairTotalLine)}` });

  const h2p = 1 - mk.homeDist[0] - mk.homeDist[1];
  const a2p = 1 - mk.awayDist[0] - mk.awayDist[1];
  q.push({
    key: "o15",
    label: "Ada tim cetak Over 1.5 (2+ gol)?",
    answer: mk.anyTeam2plus >= 0.5 ? `YA — paling mungkin ${h2p >= a2p ? H : A}` : "TIDAK",
    p: mk.anyTeam2plus >= 0.5 ? mk.anyTeam2plus : 1 - mk.anyTeam2plus,
    conf: marketConf(Math.max(mk.anyTeam2plus, 1 - mk.anyTeam2plus), c.conf),
    note: `${H} 2+ gol ${pct(h2p)} · ${A} 2+ gol ${pct(a2p)}`,
  });
  const ttPick = (dist: number[], lam: number) => {
    let best: { txt: string; p: number } | null = null;
    for (const line of [0.5, 1.5, 2.5]) {
      let o = 0;
      dist.forEach((v, k) => {
        if (k > line) o += v;
      });
      const side = o >= 0.5 ? "Over" : "Under";
      const p = Math.max(o, 1 - o);
      if (p < 0.6) continue;
      if (!best || Math.abs(p - 0.68) < Math.abs(best.p - 0.68)) best = { txt: `${side} ${line}`, p };
    }
    return best ?? { txt: lam >= 1 ? "Over 0.5" : "Under 1.5", p: 0 };
  };
  const t1 = ttPick(mk.homeDist, c.lh), t2 = ttPick(mk.awayDist, c.la);
  const lines = (dist: number[]) => [0.5, 1.5, 2.5].map((l) => `O${l} ${pct(dist.reduce((s, v, k) => (k > l ? s + v : s), 0))}`).join(" · ");
  q.push({ key: "tt", label: "Total gol Tim 1 / Tim 2", answer: `${H}: ${t1.txt} · ${A}: ${t2.txt}`, p: null, conf: null, note: `${H} (ekspektasi ${c.lh.toFixed(2)}): ${lines(mk.homeDist)} — ${A} (ekspektasi ${c.la.toFixed(2)}): ${lines(mk.awayDist)}` });
  const btts = mk.btts.yes >= 0.5;
  q.push({ key: "btts", label: "Kedua tim cetak gol (BTTS)", answer: btts ? "YA" : "TIDAK", p: btts ? mk.btts.yes : mk.btts.no, conf: marketConf(Math.max(mk.btts.yes, mk.btts.no), c.conf) });
  q.push({ key: "fhgoal", label: "Gol di babak 1?", answer: mk.ht.over05 >= 0.5 ? "YA" : "TIDAK", p: Math.max(mk.ht.over05, 1 - mk.ht.over05), conf: marketConf(Math.max(mk.ht.over05, 1 - mk.ht.over05), c.conf), note: `Gol sebelum menit 30: ${pct(mk.goalBefore.m30)}` });
  const cl = c.corners.lines.find((l) => l.line === 9.5)!;
  q.push({ key: "corner", label: "Corner", answer: `${cl.over >= 0.5 ? "Over" : "Under"} 9.5 (±${c.corners.lambda.toFixed(1)})`, p: Math.max(cl.over, 1 - cl.over), conf: null, note: `Data corner: ${c.corners.dataQuality}` });
  const kl = c.cards.lines.find((l) => l.line === 4.5)!;
  q.push({ key: "cards", label: "Kartu", answer: `${kl.over >= 0.5 ? "Over" : "Under"} 4.5 (±${c.cards.lambda.toFixed(1)})`, p: Math.max(kl.over, 1 - kl.over), conf: null, note: `Data kartu: ${c.cards.dataQuality}` });
  const [code, p] = c.htftTop;
  const n = (x: string) => (x === "1" ? H : x === "2" ? A : "Seri");
  q.push({ key: "htft", label: "HT/FT", answer: `${n(code[0])} / ${n(code[2])}`, p, conf: exactConf(p, 0.35) });
  return q;
}
