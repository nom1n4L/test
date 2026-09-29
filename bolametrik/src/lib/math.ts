// Fungsi matematika murni: distribusi Poisson, Negative Binomial, matriks skor Dixon-Coles.

export const MAXG = 10; // skor 0..10 per tim

export const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x));

export function poissonPmf(lambda: number, max = MAXG): number[] {
  const out = new Array(max + 1).fill(0);
  let p = Math.exp(-lambda);
  out[0] = p;
  for (let k = 1; k <= max; k++) {
    p = (p * lambda) / k;
    out[k] = p;
  }
  return out;
}

export function logPoisson(k: number, lambda: number): number {
  let lf = 0;
  for (let i = 2; i <= k; i++) lf += Math.log(i);
  return k * Math.log(Math.max(lambda, 1e-9)) - lambda - lf;
}

/** Negative binomial dengan mean mu dan dispersi r (var = mu + mu^2/r). */
export function negBinPmf(mu: number, r: number, max: number): number[] {
  const out = new Array(max + 1).fill(0);
  const p = r / (r + mu);
  let v = Math.pow(p, r);
  out[0] = v;
  for (let k = 1; k <= max; k++) {
    v = (v * (k - 1 + r) * (1 - p)) / k;
    out[k] = v;
  }
  return out;
}

export function convolve(a: number[], b: number[]): number[] {
  const out = new Array(a.length + b.length - 1).fill(0);
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++) out[i + j] += a[i] * b[j];
  return out;
}

export type Matrix = number[][];

/**
 * Matriks skor dengan koreksi Dixon-Coles (rho) untuk skor rendah dan
 * faktor draw (drawBoost) yang dipelajari dari kesalahan prediksi seri.
 */
export function scoreMatrix(lh: number, la: number, rho = 0, drawBoost = 1): Matrix {
  const ph = poissonPmf(lh);
  const pa = poissonPmf(la);
  const m: Matrix = [];
  let sum = 0;
  for (let i = 0; i <= MAXG; i++) {
    m.push([]);
    for (let j = 0; j <= MAXG; j++) {
      let tau = 1;
      if (i === 0 && j === 0) tau = 1 - lh * la * rho;
      else if (i === 0 && j === 1) tau = 1 + lh * rho;
      else if (i === 1 && j === 0) tau = 1 + la * rho;
      else if (i === 1 && j === 1) tau = 1 - rho;
      let v = ph[i] * pa[j] * Math.max(tau, 0);
      if (i === j) v *= drawBoost;
      m[i].push(v);
      sum += v;
    }
  }
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) m[i][j] /= sum;
  return m;
}

export function matrixOutcome(m: Matrix) {
  let h = 0, d = 0, a = 0;
  for (let i = 0; i < m.length; i++)
    for (let j = 0; j < m[i].length; j++) {
      if (i > j) h += m[i][j];
      else if (i === j) d += m[i][j];
      else a += m[i][j];
    }
  return { home: h, draw: d, away: a };
}

export function topScores(m: Matrix, n: number) {
  const list: { h: number; a: number; p: number }[] = [];
  for (let i = 0; i < m.length; i++) for (let j = 0; j < m[i].length; j++) list.push({ h: i, a: j, p: m[i][j] });
  list.sort((x, y) => y.p - x.p);
  return list.slice(0, n);
}

/** Distribusi selisih gol (home - away) sebagai Map. */
export function diffDist(m: Matrix): Map<number, number> {
  const d = new Map<number, number>();
  for (let i = 0; i < m.length; i++) for (let j = 0; j < m[i].length; j++) d.set(i - j, (d.get(i - j) ?? 0) + m[i][j]);
  return d;
}

export function totalDist(m: Matrix): number[] {
  const t = new Array(m.length * 2).fill(0);
  for (let i = 0; i < m.length; i++) for (let j = 0; j < m[i].length; j++) t[i + j] += m[i][j];
  return t;
}

export interface Settle {
  win: number;
  halfWin: number;
  push: number;
  halfLoss: number;
  loss: number;
}

/** Pisahkan garis Asia (mis. -0.75) menjadi dua setengah taruhan. */
export function splitLine(line: number): [number, number] {
  const q = Math.round(line * 4);
  if (q % 2 === 0) return [line, line]; // 0, 0.5, 1, 1.5 ...
  return [(q - 1) / 4, (q + 1) / 4];
}

function settleHalf(margin: number): "w" | "p" | "l" {
  if (Math.abs(margin) < 1e-9) return "p";
  return margin > 0 ? "w" : "l";
}

/** Hasil taruhan Asia dari satu nilai "margin" untuk kedua setengah garis. */
export function settleAsian(value: number, line: number, dir: 1 | -1): Settle {
  // dir=1 : menang jika value + line > 0 (handicap), untuk total: value - line > 0 (over)
  const [l1, l2] = splitLine(line);
  const r1 = settleHalf(dir * value + l1);
  const r2 = settleHalf(dir * value + l2);
  const s: Settle = { win: 0, halfWin: 0, push: 0, halfLoss: 0, loss: 0 };
  const key = [r1, r2].sort().join("");
  // kombinasi: ww, pw, pp, lp, ll, lw (tidak mungkin untuk garis berurutan)
  if (key === "ww") s.win = 1;
  else if (key === "pw") s.halfWin = 1;
  else if (key === "pp") s.push = 1;
  else if (key === "lp") s.halfLoss = 1;
  else if (key === "ll") s.loss = 1;
  else if (key === "lw") {
    s.halfWin = 0.5;
    s.halfLoss = 0.5;
  }
  return s;
}

function addSettle(acc: Settle, s: Settle, p: number) {
  acc.win += s.win * p;
  acc.halfWin += s.halfWin * p;
  acc.push += s.push * p;
  acc.halfLoss += s.halfLoss * p;
  acc.loss += s.loss * p;
}

/** Distribusi hasil Asian Handicap untuk tuan rumah dengan garis `line` (mis. -0.5). */
export function asianHandicap(m: Matrix, line: number): Settle {
  const acc: Settle = { win: 0, halfWin: 0, push: 0, halfLoss: 0, loss: 0 };
  for (const [d, p] of diffDist(m)) addSettle(acc, settleAsian(d, line, 1), p);
  return acc;
}

/** Distribusi hasil Over untuk garis total (Asia atau biasa). */
export function asianTotal(dist: number[], line: number): Settle {
  const acc: Settle = { win: 0, halfWin: 0, push: 0, halfLoss: 0, loss: 0 };
  dist.forEach((p, t) => addSettle(acc, settleAsian(t, -line, 1), p));
  return acc;
}

export function flipSettle(s: Settle): Settle {
  return { win: s.loss, halfWin: s.halfLoss, push: s.push, halfLoss: s.halfWin, loss: s.win };
}

/** Probabilitas "efektif": menang=1, menang½=0.75, push=0.5, kalah½=0.25. */
export function effectiveP(s: Settle): number {
  return s.win + 0.75 * s.halfWin + 0.5 * s.push + 0.25 * s.halfLoss;
}

/** Odds desimal adil (EV = 0) untuk distribusi hasil Asia. */
export function fairOddsAsian(s: Settle): number {
  const up = s.win + 0.5 * s.halfWin;
  const down = s.loss + 0.5 * s.halfLoss;
  if (up <= 1e-9) return Infinity;
  return 1 + down / up;
}

export function evAsian(s: Settle, odds: number): number {
  return s.win * (odds - 1) + s.halfWin * (odds - 1) * 0.5 - s.halfLoss * 0.5 - s.loss;
}

export function over(dist: number[], line: number): number {
  let p = 0;
  dist.forEach((v, k) => {
    if (k > line) p += v;
  });
  return p;
}

export function sumArr(a: number[]) {
  return a.reduce((s, v) => s + v, 0);
}

/** Penggabungan dua tim menjadi matriks independen (tanpa koreksi) untuk babak. */
export function indepMatrix(lh: number, la: number): Matrix {
  const ph = poissonPmf(lh);
  const pa = poissonPmf(la);
  return ph.map((x) => pa.map((y) => x * y));
}

export function round(x: number, d = 2) {
  const f = Math.pow(10, d);
  return Math.round(x * f) / f;
}

/**
 * Balik peluang pasar (1X2 tanpa margin, opsional Over 2.5) menjadi ekspektasi gol
 * kedua tim lewat pencarian grid pada model Poisson/Dixon-Coles.
 */
export function lambdasFromOdds(pH: number, pD: number, pA: number, pOver: number | null, priorTotal: number, rho = -0.07) {
  const err = (lh: number, la: number) => {
    const m = scoreMatrix(lh, la, rho, 1);
    const o = matrixOutcome(m);
    let e = (o.home - pH) ** 2 + (o.away - pA) ** 2 + 0.5 * (o.draw - pD) ** 2;
    if (pOver !== null) e += (over(totalDist(m), 2.5) - pOver) ** 2;
    else e += 0.01 * ((lh + la - priorTotal) / priorTotal) ** 2;
    return e;
  };
  let best = { lh: priorTotal / 2, la: priorTotal / 2, e: Infinity };
  for (let lh = 0.15; lh <= 4.5; lh += 0.1)
    for (let la = 0.15; la <= 4.5; la += 0.1) {
      const e = err(lh, la);
      if (e < best.e) best = { lh, la, e };
    }
  const c = { ...best };
  for (let lh = c.lh - 0.1; lh <= c.lh + 0.1 + 1e-9; lh += 0.02)
    for (let la = c.la - 0.1; la <= c.la + 0.1 + 1e-9; la += 0.02) {
      if (lh <= 0.05 || la <= 0.05) continue;
      const e = err(lh, la);
      if (e < best.e) best = { lh, la, e };
    }
  return { lh: best.lh, la: best.la, err: best.e };
}
