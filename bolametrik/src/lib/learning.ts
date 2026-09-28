// Belajar dari kesalahan: evaluasi prediksi vs hasil nyata, lalu replay semua
// laga yang sudah selesai untuk memperbarui parameter model secara deterministik.

import { clamp, logPoisson, matrixOutcome, scoreMatrix } from "./math";
import { applyLearned, BASE_WEIGHTS, computeLambdas, defaultParams, halfShares, SIGNAL_KEYS, SIGNAL_LABEL } from "./model";
import { predict, type Prediction } from "./predict";
import { rawCards, rawCorners, rawPenalty, rawRed } from "./extras";
import { CAT_LABEL, type Rec, settlePick, type SettleOutcome } from "./recommend";
import type { MarketCat, MatchRecord, MatchResult, ModelParams, SignalKey } from "./types";

export interface Evaluation {
  outcomeHit: boolean;
  brier: number; // 1X2 (0 = sempurna, 2 = terburuk)
  logLoss: number;
  baselineBrier: number; // pembanding: rata-rata liga tanpa informasi tim
  ftExact: boolean;
  htExact: boolean | null;
  scenarioExact: boolean;
  ouHit: boolean;
  bttsHit: boolean;
  goalError: number; // aktual - ekspektasi total gol
  homeGoalError: number;
  awayGoalError: number;
  recs: { rec: Rec; out: SettleOutcome | null }[];
  scorerHits: { name: string; p: number; scored: boolean }[];
  penOk: boolean | null;
  redOk: boolean | null;
  cornersErr: number | null;
  cardsErr: number | null;
  lessons: string[];
}

export function normName(s: string) {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9 ]/g, "")
    .trim();
}

const pct = (p: number) => `${Math.round(p * 100)}%`;

export function evaluate(pred: Prediction, rec: MatchRecord, r: MatchResult): Evaluation {
  const x = pred.mk.x12;
  const res = r.ftH > r.ftA ? "1" : r.ftH < r.ftA ? "2" : "X";
  const o = { "1": [1, 0, 0], X: [0, 1, 0], "2": [0, 0, 1] }[res];
  const p = [x.home, x.draw, x.away];
  const brier = p.reduce((s, v, i) => s + (v - o[i]) ** 2, 0);
  const logLoss = -Math.log(Math.max(p[o.indexOf(1)], 1e-6));
  const base = matrixOutcome(scoreMatrix(rec.input.homeAvg, rec.input.awayAvg, -0.07, 1.04));
  const bp = [base.home, base.draw, base.away];
  const baselineBrier = bp.reduce((s, v, i) => s + (v - o[i]) ** 2, 0);
  const total = r.ftH + r.ftA;
  const lt = pred.lam.lh + pred.lam.la;
  const o25 = pred.mk.totals.find((t) => t.line === 2.5)!.over;
  const ouHit = (o25 >= 0.5) === total > 2.5;
  const bttsHit = (pred.mk.btts.yes >= 0.5) === (r.ftH > 0 && r.ftA > 0);
  const ftExact = pred.scenario.ft[0] === r.ftH && pred.scenario.ft[1] === r.ftA;
  const htExact = r.htH === null || r.htA === null ? null : pred.scenario.ht[0] === r.htH && pred.scenario.ht[1] === r.htA;
  const recs = pred.recs.map((rc) => ({ rec: rc, out: settlePick(rc.pick, r, normName) }));
  const scored = new Set(r.scorers.map(normName));
  const allSc = [...pred.scorers.home, ...pred.scorers.away].sort((a, b) => b.anytime - a.anytime).slice(0, 5);
  const scorerHits = allSc.map((s) => {
    const n = normName(s.name);
    return { name: s.name, p: s.anytime, scored: [...scored].some((x) => x === n || x.includes(n) || n.includes(x)) };
  });
  const penOk = r.pen === null ? null : (pred.pen.p >= 0.5) === r.pen;
  const redOk = r.red === null ? null : (pred.red.p >= 0.5) === r.red;
  const cornersErr = r.corners === null ? null : r.corners - pred.corners.lambda;
  const cardsErr = r.cards === null ? null : r.cards - pred.cards.lambda;

  // ----- Pelajaran (analisis kesalahan) -----
  const L: string[] = [];
  const Hn = rec.input.home.name, An = rec.input.away.name;
  const pickName = pred.pick === "1" ? Hn : pred.pick === "2" ? An : "seri";
  if (pred.pick === res) L.push(`✔ Arah hasil benar: model memilih ${pickName} (${pct(Math.max(...p))}).`);
  else {
    const pr = p[o.indexOf(1)];
    L.push(`✘ Arah hasil salah: model memilih ${pickName}, hasil ${res === "X" ? "seri" : res === "1" ? `${Hn} menang` : `${An} menang`} (model memberi ${pct(pr)} untuk hasil ini${pr < 0.2 ? " — kejutan besar" : ""}).`);
  }
  const eh = r.ftH - pred.lam.lh, ea = r.ftA - pred.lam.la;
  if (Math.abs(total - lt) >= 1.5)
    L.push(`${total > lt ? "▲" : "▼"} Total gol ${total} vs ekspektasi ${lt.toFixed(2)}: model ${total > lt ? "meremehkan" : "melebih-lebihkan"} tempo laga. Skala gol global & faktor liga disesuaikan.`);
  if (Math.abs(eh) >= 1.5) L.push(`${Hn} mencetak ${r.ftH} gol (ekspektasi ${pred.lam.lh.toFixed(2)}): ${eh > 0 ? "serangan tuan rumah diremehkan" : "serangan tuan rumah terlalu dipercaya"}.`);
  if (Math.abs(ea) >= 1.5) L.push(`${An} mencetak ${r.ftA} gol (ekspektasi ${pred.lam.la.toFixed(2)}): ${ea > 0 ? "serangan tim tamu diremehkan" : "serangan tim tamu terlalu dipercaya"}.`);
  // Sinyal mana yang paling akurat di laga ini
  const sigLL = pred.lam.signals.map((s) => ({ s, ll: logPoisson(r.ftH, s.lh) + logPoisson(r.ftA, s.la) }));
  if (sigLL.length >= 3) {
    sigLL.sort((a, b) => b.ll - a.ll);
    L.push(`Sumber data paling akurat di laga ini: ${sigLL[0].s.label} (${sigLL[0].s.lh.toFixed(2)}-${sigLL[0].s.la.toFixed(2)}); paling meleset: ${sigLL[sigLL.length - 1].s.label}. Bobot sumber diperbarui.`);
  }
  if (res === "X" && x.draw < 0.26) L.push("Hasil seri kurang diantisipasi; faktor seri (draw boost) dinaikkan sedikit.");
  if (r.htH !== null && r.htA !== null && total > 0) {
    const share = (r.htH + r.htA) / total;
    if (share >= 0.75 && total >= 2) L.push("Sebagian besar gol terjadi di babak 1 — porsi gol babak 1 dinaikkan sedikit.");
    if (share <= 0.2 && total >= 2) L.push("Gol menumpuk di babak 2 — porsi gol babak 2 dinaikkan sedikit.");
  }
  const bad = recs.filter((x) => x.out && x.out.score === 0 && (x.rec.tier === "aman" || x.rec.tier === "utama"));
  if (bad.length) L.push(`Rekomendasi yang kalah: ${bad.map((b) => b.rec.label).join(", ")}. Keandalan kategori ${[...new Set(bad.map((b) => CAT_LABEL[b.rec.pick.cat]))].join(", ")} dikoreksi.`);
  const good = recs.filter((x) => x.out && x.out.score >= 0.75 && (x.rec.tier === "aman" || x.rec.tier === "utama"));
  if (good.length) L.push(`Rekomendasi yang menang: ${good.map((g) => g.rec.label).join(", ")}.`);
  if (cornersErr !== null && Math.abs(cornersErr) >= 3.5) L.push(`Corner ${r.corners} vs ekspektasi ${pred.corners.lambda.toFixed(1)} — skala corner disesuaikan.`);
  if (cardsErr !== null && Math.abs(cardsErr) >= 2.5) L.push(`Kartu ${r.cards} vs ekspektasi ${pred.cards.lambda.toFixed(1)} — skala kartu disesuaikan.`);
  if (r.pen && pred.pen.p < 0.25) L.push(`Ada penalti padahal peluang model hanya ${pct(pred.pen.p)}.`);
  if (r.red && pred.red.p < 0.2) L.push(`Ada kartu merah padahal peluang model hanya ${pct(pred.red.p)}.`);
  if (brier < baselineBrier) L.push(`Skor Brier ${brier.toFixed(3)} lebih baik dari tebakan dasar liga (${baselineBrier.toFixed(3)}).`);
  else L.push(`Skor Brier ${brier.toFixed(3)} lebih buruk dari tebakan dasar liga (${baselineBrier.toFixed(3)}) — laga ini sulit diprediksi dari data yang ada.`);

  return {
    outcomeHit: pred.pick === res,
    brier,
    logLoss,
    baselineBrier,
    ftExact,
    htExact,
    scenarioExact: ftExact && htExact === true,
    ouHit,
    bttsHit,
    goalError: total - lt,
    homeGoalError: eh,
    awayGoalError: ea,
    recs,
    scorerHits,
    penOk,
    redOk,
    cornersErr,
    cardsErr,
    lessons: L,
  };
}

// ---------- Replay pembelajaran ----------

export interface ParamChange {
  recordId: string;
  label: string;
  date: string;
  brier: number;
  changes: string[];
}

export interface LearnState {
  params: ModelParams;
  log: ParamChange[];
  history: { id: string; date: string; brier: number; baseline: number; hit: boolean; logLoss: number }[];
}

const sortKey = (r: MatchRecord) => r.input.kickoff || r.createdAt;

/**
 * Replay semua laga selesai (urut kronologis) dari parameter default.
 * Karena prediksi adalah fungsi murni (input + snapshot), hasil replay
 * selalu konsisten walau hasil laga diedit atau dihapus.
 */
export function relearn(records: MatchRecord[]): LearnState {
  const params = defaultParams();
  const log: ParamChange[] = [];
  const history: LearnState["history"] = [];
  const settled = records.filter((r) => r.result && !r.demo).sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
  const hedge: Record<SignalKey, number> = {} as Record<SignalKey, number>;
  for (const k of SIGNAL_KEYS) hedge[k] = 0;
  let aiLossModel = 0, aiLossAI = 0;

  for (const rec of settled) {
    const r = rec.result!;
    const before = { ...params, weights: { ...params.weights }, leagueFactor: { ...params.leagueFactor } };
    // Prediksi seperti yang dibuat saat itu (untuk evaluasi rekomendasi & statistik)
    const predThen = predict(rec.input, rec.snapshot, rec.ai);
    const ev = evaluate(predThen, rec, r);
    history.push({ id: rec.id, date: sortKey(rec), brier: ev.brier, baseline: ev.baselineBrier, hit: ev.outcomeHit, logLoss: ev.logLoss });

    // λ mentah dihitung ulang dengan bobot sinyal SAAT INI, lalu parameter global saat ini
    const lamNow = computeLambdas(rec.input, params);
    const fin = applyLearned(lamNow.raw, rec.input.neutral, rec.input.leagueKey, params);
    const yH = r.ftH, yA = r.ftA;

    // 1) Skala gol global & faktor liga (gradien log-likelihood Poisson)
    const gTot = (yH - fin.lh + (yA - fin.la)) / Math.max(fin.lh + fin.la, 0.5);
    params.goalScale = clamp(params.goalScale * Math.exp(0.035 * gTot), 0.75, 1.3);
    const lk = rec.input.leagueKey;
    params.leagueFactor[lk] = clamp((params.leagueFactor[lk] ?? 1) * Math.exp(0.06 * gTot), 0.75, 1.3);
    // 2) Keunggulan kandang
    if (!rec.input.neutral) {
      const gHa = (yH - fin.lh) / Math.max(fin.lh, 0.4) - (yA - fin.la) / Math.max(fin.la, 0.4);
      params.homeAdv = clamp(params.homeAdv * Math.exp(0.02 * clamp(gHa, -2, 2)), 0.85, 1.2);
    }
    // 3) Bobot sumber data (algoritma Hedge / multiplicative weights)
    const lls = lamNow.signals.map((s) => ({ k: s.key, ll: logPoisson(yH, s.lh) + logPoisson(yA, s.la), rel: s.rel }));
    if (lls.length >= 2) {
      const mean = lls.reduce((s, x) => s + x.ll, 0) / lls.length;
      for (const x of lls) hedge[x.k] += clamp(x.ll - mean, -3, 3) * x.rel;
      for (const k of SIGNAL_KEYS) params.weights[k] = clamp(Math.exp(0.12 * hedge[k]), 0.3, 3);
    }
    // 4) Faktor seri
    const mNow = scoreMatrix(fin.lh, fin.la, params.rho, params.drawBoost);
    const pD = matrixOutcome(mNow).draw;
    const isDraw = yH === yA ? 1 : 0;
    params.drawBoost = clamp(params.drawBoost * Math.exp(0.04 * (isDraw - pD)), 0.85, 1.35);
    // 5) Porsi gol babak 1
    if (r.htH !== null && r.htA !== null && yH + yA > 0) {
      const { fh, fa } = halfShares(rec.input, params);
      const exp1 = fin.lh * fh + fin.la * fa;
      const share = exp1 / Math.max(fin.lh + fin.la, 0.1);
      const obs = (r.htH + r.htA + share * 2) / (yH + yA + 2);
      params.fhShare = clamp(params.fhShare + 0.06 * (obs - share), 0.36, 0.54);
    }
    // 6) Corner, kartu, penalti, kartu merah
    if (r.corners !== null) {
      const c = rawCorners(rec.input);
      const lam = (c.home + c.away) * params.cornerScale;
      params.cornerScale = clamp(params.cornerScale * Math.exp(0.05 * clamp((r.corners - lam) / Math.max(lam, 1), -1, 1)), 0.7, 1.4);
    }
    const xo = matrixOutcome(mNow);
    if (r.cards !== null) {
      const c = rawCards(rec.input, xo.home, xo.away);
      const lam = (c.home + c.away) * params.cardScale;
      params.cardScale = clamp(params.cardScale * Math.exp(0.05 * clamp((r.cards - lam) / Math.max(lam, 1), -1, 1)), 0.7, 1.4);
    }
    if (r.pen !== null) {
      const c = rawPenalty(rec.input, fin.lh, fin.la);
      const pp = 1 - Math.exp(-(c.home + c.away) * params.penScale);
      params.penScale = clamp(params.penScale * Math.exp(0.1 * ((r.pen ? 1 : 0) - pp)), 0.6, 1.6);
    }
    if (r.red !== null) {
      const c = rawRed(rec.input, xo.home, xo.away);
      const pr = 1 - Math.exp(-(c.home + c.away) * params.redScale);
      params.redScale = clamp(params.redScale * Math.exp(0.1 * ((r.red ? 1 : 0) - pr)), 0.6, 1.6);
    }
    // 7) Keandalan tiap kategori pasaran (dari rekomendasi yang dibuat saat itu)
    const markCat = (cat: MarketCat, p: number, score: number) => {
      const s = params.market[cat] ?? { n: 0, hits: 0, sumP: 0 };
      params.market[cat] = { n: s.n + 1, hits: s.hits + score, sumP: s.sumP + p };
    };
    markCat("1X2", Math.max(predThen.mk.x12.home, predThen.mk.x12.draw, predThen.mk.x12.away), ev.outcomeHit ? 1 : 0);
    for (const { rec: rc, out } of ev.recs) {
      if (!out || rc.pick.cat === "1X2") continue;
      markCat(rc.pick.cat, rc.p, out.score);
    }
    // 8) AI vs model (Hedge dua pakar)
    if (rec.ai?.probs) {
      const res = yH > yA ? 0 : yH === yA ? 1 : 2;
      const pa = [rec.ai.probs.home, rec.ai.probs.draw, rec.ai.probs.away];
      const sa = pa.reduce((s, v) => s + v, 0) || 1;
      const pm = [predThen.mk.x12.home, predThen.mk.x12.draw, predThen.mk.x12.away];
      const b = (pp: number[], s = 1) => pp.reduce((acc, v, i) => acc + (v / s - (i === res ? 1 : 0)) ** 2, 0);
      aiLossModel += b(pm);
      aiLossAI += b(pa, sa);
      const wM = Math.exp(-1.2 * aiLossModel), wA = Math.exp(-1.2 * aiLossAI);
      params.aiWeight = clamp((0.3 * wA) / (0.3 * wA + 0.7 * wM), 0.1, 0.7);
    }
    params.learned += 1;

    // Catat perubahan parameter yang berarti
    const ch: string[] = [];
    const d = (label: string, a: number, b: number, digits = 3) => {
      if (Math.abs(a - b) >= 0.004) ch.push(`${label}: ${a.toFixed(digits)} → ${b.toFixed(digits)}`);
    };
    d("Skala gol", before.goalScale, params.goalScale);
    d(`Faktor liga ${rec.input.leagueName}`, before.leagueFactor[lk] ?? 1, params.leagueFactor[lk]);
    d("Keunggulan kandang", before.homeAdv, params.homeAdv);
    d("Faktor seri", before.drawBoost, params.drawBoost);
    d("Porsi gol babak 1", before.fhShare, params.fhShare);
    d("Skala corner", before.cornerScale, params.cornerScale);
    d("Skala kartu", before.cardScale, params.cardScale);
    d("Skala penalti", before.penScale, params.penScale);
    d("Skala kartu merah", before.redScale, params.redScale);
    d("Bobot AI", before.aiWeight, params.aiWeight);
    for (const k of SIGNAL_KEYS) if (Math.abs(before.weights[k] - params.weights[k]) >= 0.02) ch.push(`Bobot "${SIGNAL_LABEL[k]}": ${before.weights[k].toFixed(2)} → ${params.weights[k].toFixed(2)}`);
    log.push({ recordId: rec.id, label: `${rec.input.home.name} ${r.ftH}-${r.ftA} ${rec.input.away.name}`, date: sortKey(rec), brier: ev.brier, changes: ch });
  }
  return { params, log, history };
}

/** Bobot efektif tiap sumber (dasar x dipelajari), untuk ditampilkan. */
export function effectiveWeights(params: ModelParams) {
  return SIGNAL_KEYS.map((k) => ({ key: k, label: SIGNAL_LABEL[k], base: BASE_WEIGHTS[k], learned: params.weights[k], eff: BASE_WEIGHTS[k] * params.weights[k] }));
}

export interface Stats {
  total: number;
  settled: number;
  pending: number;
  hit1x2: number;
  hitOU: number;
  hitBTTS: number;
  exactFT: number;
  exactHT: number;
  brier: number | null;
  baseline: number | null;
  recWin: number | null; // rata-rata skor rekomendasi aman+utama
  recN: number;
  roi: number | null;
  valueN: number;
  calibration: { bin: string; pred: number; actual: number; n: number }[];
}

/** Statistik akurasi dari semua laga selesai (memakai prediksi yang dibuat saat itu). */
export function computeStats(records: MatchRecord[]): Stats {
  const real = records.filter((r) => !r.demo);
  const settled = real.filter((r) => r.result);
  let h = 0, ou = 0, bt = 0, ex = 0, exh = 0, exhN = 0, brier = 0, base = 0, recSum = 0, recN = 0, profit = 0, stake = 0, valueN = 0;
  const bins = Array.from({ length: 5 }, () => ({ pred: 0, actual: 0, n: 0 }));
  for (const rec of settled) {
    const pred = predict(rec.input, rec.snapshot, rec.ai);
    const ev = evaluate(pred, rec, rec.result!);
    if (ev.outcomeHit) h++;
    if (ev.ouHit) ou++;
    if (ev.bttsHit) bt++;
    if (ev.ftExact) ex++;
    if (ev.htExact !== null) {
      exhN++;
      if (ev.htExact) exh++;
    }
    brier += ev.brier;
    base += ev.baselineBrier;
    for (const { rec: rc, out } of ev.recs) {
      if (!out) continue;
      if (rc.tier === "aman" || rc.tier === "utama") {
        recSum += out.score;
        recN++;
      }
      if (rc.tier === "value" && rc.odds) {
        valueN++;
        stake += 1;
        const gain = out.score === 1 ? rc.odds - 1 : out.score === 0.75 ? (rc.odds - 1) / 2 : out.score === 0.5 ? 0 : out.score === 0.25 ? -0.5 : -1;
        profit += gain;
      }
    }
    // Kalibrasi: semua peluang 1X2
    const res = rec.result!.ftH > rec.result!.ftA ? 0 : rec.result!.ftH === rec.result!.ftA ? 1 : 2;
    [pred.mk.x12.home, pred.mk.x12.draw, pred.mk.x12.away].forEach((p, i) => {
      const b = Math.min(4, Math.floor(p * 5));
      bins[b].pred += p;
      bins[b].actual += i === res ? 1 : 0;
      bins[b].n++;
    });
  }
  const n = settled.length;
  return {
    total: real.length,
    settled: n,
    pending: real.length - n,
    hit1x2: n ? h / n : 0,
    hitOU: n ? ou / n : 0,
    hitBTTS: n ? bt / n : 0,
    exactFT: n ? ex / n : 0,
    exactHT: exhN ? exh / exhN : 0,
    brier: n ? brier / n : null,
    baseline: n ? base / n : null,
    recWin: recN ? recSum / recN : null,
    recN,
    roi: stake ? profit / stake : null,
    valueN,
    calibration: bins.map((b, i) => ({ bin: `${i * 20}-${i * 20 + 20}%`, pred: b.n ? b.pred / b.n : 0, actual: b.n ? b.actual / b.n : 0, n: b.n })),
  };
}
