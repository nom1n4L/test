// Turunan semua pasaran gol dari matriks skor.

import {
  asianHandicap,
  asianTotal,
  flipSettle,
  type Matrix,
  matrixOutcome,
  over,
  type Settle,
  totalDist,
} from "./math";
import type { Matrices } from "./model";

export interface AHRow {
  line: number; // garis untuk tuan rumah
  home: Settle;
  away: Settle;
}

export interface TotalRow {
  line: number;
  over: Settle;
  under: Settle;
}

export interface Markets {
  x12: { home: number; draw: number; away: number };
  dc: { hx: number; xa: number; ha: number };
  dnb: { home: number; away: number };
  totals: { line: number; over: number; under: number }[];
  asianTotals: TotalRow[];
  ah: AHRow[];
  fairAhLine: number;
  fairTotalLine: number;
  btts: { yes: number; no: number };
  bttsOver25: number;
  teamTotals: { home: { line: number; over: number }[]; away: { line: number; over: number }[] };
  homeDist: number[];
  awayDist: number[];
  anyTeam2plus: number;
  bothTeams2plus: number;
  cleanSheet: { home: number; away: number };
  winToNil: { home: number; away: number };
  exactGoals: number[]; // 0..5, index 6 = 6+
  oddEven: { odd: number; even: number };
  margin: { label: string; p: number }[];
  resultBtts: Record<"1Y" | "1N" | "XY" | "XN" | "2Y" | "2N", number>;
  resultOU: Record<"1O" | "1U" | "XO" | "XU" | "2O" | "2U", number>;
  ht: { home: number; draw: number; away: number; over05: number; over15: number; btts: number; dist: number[] };
  h2: { home: number; draw: number; away: number; over05: number; over15: number; dist: number[] };
  htft: Record<string, number>;
  highestHalf: { first: number; second: number; equal: number };
  goalBothHalves: number;
  firstToScore: { home: number; away: number; none: number };
  goalBefore: { m15: number; m30: number; m45: number };
  totalDist: number[];
}

const sign = (x: number) => (x > 0 ? "1" : x < 0 ? "2" : "X");

function marg(m: Matrix) {
  const home = new Array(m.length).fill(0);
  const away = new Array(m.length).fill(0);
  for (let i = 0; i < m.length; i++)
    for (let j = 0; j < m.length; j++) {
      home[i] += m[i][j];
      away[j] += m[i][j];
    }
  return { home, away };
}

export function computeMarkets(M: Matrices, lh: number, la: number, fh: number, fa: number): Markets {
  const m = M.ft;
  const x12 = matrixOutcome(m);
  const td = totalDist(m);
  const { home: hd, away: ad } = marg(m);

  const totals = [0.5, 1.5, 2.5, 3.5, 4.5, 5.5].map((line) => {
    const o = over(td, line);
    return { line, over: o, under: 1 - o };
  });
  const asianTotals: TotalRow[] = [];
  for (let line = 1.5; line <= 4.5 + 1e-9; line += 0.25) {
    const o = asianTotal(td, line);
    asianTotals.push({ line, over: o, under: flipSettle(o) });
  }
  const ah: AHRow[] = [];
  for (let q = -12; q <= 12; q++) {
    const line = q / 4;
    const s = asianHandicap(m, line);
    ah.push({ line, home: s, away: flipSettle(s) });
  }
  // garis "adil": peluang menang (ekuivalen) tuan rumah paling dekat 50%
  let fairAhLine = 0, best = 9;
  for (const r of ah) {
    const up = r.home.win + 0.5 * r.home.halfWin;
    const down = r.home.loss + 0.5 * r.home.halfLoss;
    const d = Math.abs(up - down);
    if (d < best) {
      best = d;
      fairAhLine = r.line;
    }
  }
  let fairTotalLine = 2.5;
  best = 9;
  for (const r of asianTotals) {
    const d = Math.abs(r.over.win + 0.5 * r.over.halfWin - (r.over.loss + 0.5 * r.over.halfLoss));
    if (d < best) {
      best = d;
      fairTotalLine = r.line;
    }
  }

  let bttsYes = 0, bttsOver25 = 0, any2 = 0, both2 = 0, odd = 0;
  const resultBtts = { "1Y": 0, "1N": 0, XY: 0, XN: 0, "2Y": 0, "2N": 0 };
  const resultOU = { "1O": 0, "1U": 0, XO: 0, XU: 0, "2O": 0, "2U": 0 };
  const marginMap = new Map<string, number>();
  for (let i = 0; i < m.length; i++)
    for (let j = 0; j < m.length; j++) {
      const p = m[i][j];
      const btts = i > 0 && j > 0;
      if (btts) bttsYes += p;
      if (btts && i + j >= 3) bttsOver25 += p;
      if (i >= 2 || j >= 2) any2 += p;
      if (i >= 2 && j >= 2) both2 += p;
      if ((i + j) % 2 === 1) odd += p;
      const r = sign(i - j) as "1" | "X" | "2";
      resultBtts[`${r}${btts ? "Y" : "N"}` as keyof typeof resultBtts] += p;
      resultOU[`${r}${i + j > 2.5 ? "O" : "U"}` as keyof typeof resultOU] += p;
      const d = i - j;
      const label = d === 0 ? "Seri" : d > 0 ? (d >= 3 ? "Tuan rumah menang 3+" : `Tuan rumah menang ${d}`) : -d >= 3 ? "Tim tamu menang 3+" : `Tim tamu menang ${-d}`;
      marginMap.set(label, (marginMap.get(label) ?? 0) + p);
    }
  const marginOrder = ["Tuan rumah menang 3+", "Tuan rumah menang 2", "Tuan rumah menang 1", "Seri", "Tim tamu menang 1", "Tim tamu menang 2", "Tim tamu menang 3+"];
  const margin = marginOrder.map((label) => ({ label, p: marginMap.get(label) ?? 0 }));

  const exactGoals = [0, 1, 2, 3, 4, 5].map((k) => td[k] ?? 0);
  exactGoals.push(1 - exactGoals.reduce((s, v) => s + v, 0));

  const teamTotals = {
    home: [0.5, 1.5, 2.5, 3.5].map((line) => ({ line, over: over(hd, line) })),
    away: [0.5, 1.5, 2.5, 3.5].map((line) => ({ line, over: over(ad, line) })),
  };

  // Babak
  const htO = matrixOutcome(M.ht);
  const htTd = totalDist(M.ht);
  const h2O = matrixOutcome(M.h2);
  const h2Td = totalDist(M.h2);
  let htBtts = 0;
  for (let i = 1; i < M.ht.length; i++) for (let j = 1; j < M.ht.length; j++) htBtts += M.ht[i][j];

  // HT/FT: gabungan babak 1 x babak 2, lalu diskalakan agar marjinal FT = matriks utama
  const joint: Record<string, number> = {};
  const ftConv: Record<string, number> = { "1": 0, X: 0, "2": 0 };
  let first = 0, second = 0, equal = 0, bothHalves = 0;
  const N = M.ht.length;
  for (let i = 0; i < N; i++)
    for (let j = 0; j < N; j++) {
      const p1 = M.ht[i][j];
      if (p1 < 1e-7) continue;
      for (let k = 0; k < N; k++)
        for (let l = 0; l < N; l++) {
          const p = p1 * M.h2[k][l];
          if (p < 1e-9) continue;
          const key = `${sign(i - j)}/${sign(i + k - j - l)}`;
          joint[key] = (joint[key] ?? 0) + p;
          ftConv[sign(i + k - j - l)] += p;
          const g1 = i + j, g2 = k + l;
          if (g1 > g2) first += p;
          else if (g2 > g1) second += p;
          else equal += p;
          if (g1 > 0 && g2 > 0) bothHalves += p;
        }
    }
  const target: Record<string, number> = { "1": x12.home, X: x12.draw, "2": x12.away };
  const htft: Record<string, number> = {};
  for (const a of ["1", "X", "2"])
    for (const b of ["1", "X", "2"]) {
      const key = `${a}/${b}`;
      htft[key] = ((joint[key] ?? 0) * target[b]) / Math.max(ftConv[b], 1e-9);
    }
  const hs = first + second + equal;

  const p00 = m[0][0];
  const lt = lh + la;
  const fT = (lh * fh + la * fa) / Math.max(lt, 1e-9);
  const rate1 = lt * fT; // ekspektasi gol babak 1
  const goalBefore = {
    m15: 1 - Math.exp(-rate1 * (15 / 45)),
    m30: 1 - Math.exp(-rate1 * (30 / 45)),
    m45: 1 - Math.exp(-rate1),
  };

  return {
    x12,
    dc: { hx: x12.home + x12.draw, xa: x12.away + x12.draw, ha: x12.home + x12.away },
    dnb: { home: x12.home / (x12.home + x12.away), away: x12.away / (x12.home + x12.away) },
    totals,
    asianTotals,
    ah,
    fairAhLine,
    fairTotalLine,
    btts: { yes: bttsYes, no: 1 - bttsYes },
    bttsOver25,
    teamTotals,
    homeDist: hd,
    awayDist: ad,
    anyTeam2plus: any2,
    bothTeams2plus: both2,
    cleanSheet: { home: ad[0], away: hd[0] },
    winToNil: { home: resultBtts["1N"], away: resultBtts["2N"] },
    exactGoals,
    oddEven: { odd, even: 1 - odd },
    margin,
    resultBtts,
    resultOU,
    ht: { ...htO, over05: over(htTd, 0.5), over15: over(htTd, 1.5), btts: htBtts, dist: htTd },
    h2: { ...h2O, over05: over(h2Td, 0.5), over15: over(h2Td, 1.5), dist: h2Td },
    htft,
    highestHalf: { first: first / hs, second: second / hs, equal: equal / hs },
    goalBothHalves: bothHalves / hs,
    firstToScore: { home: (lh / lt) * (1 - p00), away: (la / lt) * (1 - p00), none: p00 },
    goalBefore,
    totalDist: td,
  };
}
