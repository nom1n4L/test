// Kandidat pasaran, rekomendasi bertingkat, dan penyelesaian (settle) pilihan setelah laga.

import { effectiveP, evAsian, fairOddsAsian, type Settle, settleAsian } from "./math";
import type { Markets } from "./markets";
import type { CountPred, ScorerPred } from "./extras";
import { type ConfLabel, marketConf } from "./insights";
import { ahIndo, fmtLine, fmtTotalLine, kelly } from "./odds";
import type { MarketCat, MatchInput, MatchResult, ModelParams } from "./types";

export interface Pick {
  cat: MarketCat;
  code: string;
  line?: number;
  team?: "H" | "A";
  name?: string;
}

export type Tier = "aman" | "utama" | "value" | "spekulatif";

export interface Rec {
  pick: Pick;
  label: string;
  p: number; // probabilitas efektif
  settle?: Settle;
  fair: number;
  conf: ConfLabel;
  reason: string;
  tier?: Tier;
  odds?: number;
  edge?: number;
  kellyPct?: number;
}

export const CAT_LABEL: Record<MarketCat, string> = {
  "1X2": "1X2 (Pemenang)",
  DC: "Double Chance",
  DNB: "Draw No Bet",
  AH: "Handicap Asia",
  OU: "Over/Under Gol",
  BTTS: "Kedua Tim Cetak Gol",
  TT: "Total Gol Tim",
  HT: "Babak Pertama",
  CORN: "Corner",
  CARD: "Kartu",
  CS: "Skor Tepat",
  HTFT: "HT/FT",
  SCORER: "Pencetak Gol",
};

const CAT_WEIGHT: Partial<Record<MarketCat, number>> = {
  "1X2": 1.1,
  AH: 1.12,
  OU: 1.08,
  BTTS: 1.0,
  TT: 0.97,
  DNB: 0.95,
  DC: 0.9,
  HT: 0.9,
  CORN: 0.86,
  CARD: 0.8,
};

export function marketReliability(params: ModelParams, cat: MarketCat): number {
  const s = params.market[cat];
  if (!s || s.n < 3) return 1;
  const ratio = (s.hits + 3) / (s.sumP + 3);
  return Math.min(1.12, Math.max(0.82, ratio));
}

interface Ctx {
  input: MatchInput;
  mk: Markets;
  corners: CountPred;
  cards: CountPred;
  lh: number;
  la: number;
  conf: number;
}

const pct = (p: number) => `${Math.round(p * 100)}%`;

export function buildCandidates(c: Ctx): Rec[] {
  const { mk, input } = c;
  const Hn = input.home.name, An = input.away.name;
  const out: Rec[] = [];
  const add = (pick: Pick, label: string, p: number, reason: string, settle?: Settle) =>
    out.push({ pick, label, p, settle, fair: settle ? fairOddsAsian(settle) : 1 / Math.max(p, 1e-6), conf: marketConf(p, c.conf), reason });
  const lt = c.lh + c.la;

  add({ cat: "1X2", code: "1" }, `${Hn} menang`, mk.x12.home, `Ekspektasi gol ${c.lh.toFixed(2)} vs ${c.la.toFixed(2)}; peluang menang ${pct(mk.x12.home)}.`);
  add({ cat: "1X2", code: "X" }, "Seri", mk.x12.draw, `Peluang seri ${pct(mk.x12.draw)}; selisih kekuatan kecil.`);
  add({ cat: "1X2", code: "2" }, `${An} menang`, mk.x12.away, `Ekspektasi gol ${c.la.toFixed(2)} vs ${c.lh.toFixed(2)}; peluang menang ${pct(mk.x12.away)}.`);
  add({ cat: "DC", code: "1X" }, `${Hn} atau seri (1X)`, mk.dc.hx, `${Hn} tidak kalah di ${pct(mk.dc.hx)} simulasi skor.`);
  add({ cat: "DC", code: "X2" }, `${An} atau seri (X2)`, mk.dc.xa, `${An} tidak kalah di ${pct(mk.dc.xa)} simulasi skor.`);
  add({ cat: "DC", code: "12" }, "Tidak seri (12)", mk.dc.ha, `Peluang ada pemenang ${pct(mk.dc.ha)}.`);
  add({ cat: "DNB", code: "1" }, `${Hn} Draw No Bet`, mk.dnb.home, `Jika seri uang kembali; ${Hn} menang ${pct(mk.dnb.home)} dari laga yang ada pemenangnya.`);
  add({ cat: "DNB", code: "2" }, `${An} Draw No Bet`, mk.dnb.away, `Jika seri uang kembali; ${An} menang ${pct(mk.dnb.away)} dari laga yang ada pemenangnya.`);

  // Hanya garis yang realistis ditawarkan bandar (sekitar garis adil)
  for (const r of mk.ah) {
    if (Math.abs(r.line) > 2 || Math.abs(r.line - mk.fairAhLine) > 1) continue;
    add({ cat: "AH", code: "1", line: r.line }, `Handicap ${Hn} ${fmtLine(r.line)} (${ahIndo(r.line)})`, effectiveP(r.home), ahReason(Hn, r.line, r.home), r.home);
    add({ cat: "AH", code: "2", line: -r.line }, `Handicap ${An} ${fmtLine(-r.line)} (${ahIndo(r.line)})`, effectiveP(r.away), ahReason(An, -r.line, r.away), r.away);
  }
  for (const t of mk.totals) {
    if (t.line < 1.5 || t.line > 4.5 || Math.abs(t.line - mk.fairTotalLine) > 1.25) continue;
    add({ cat: "OU", code: "O", line: t.line }, `Over ${t.line} gol`, t.over, `Ekspektasi total gol ${lt.toFixed(2)}; P(≥${Math.ceil(t.line)} gol) ${pct(t.over)}.`);
    add({ cat: "OU", code: "U", line: t.line }, `Under ${t.line} gol`, t.under, `Ekspektasi total gol ${lt.toFixed(2)}; P(≤${Math.floor(t.line)} gol) ${pct(t.under)}.`);
  }
  for (const t of mk.asianTotals) {
    if (Math.abs(t.line * 2 - Math.round(t.line * 2)) < 1e-9 || Math.abs(t.line - mk.fairTotalLine) > 0.75) continue; // hanya garis seperempat di sekitar garis adil
    add({ cat: "OU", code: "O", line: t.line }, `Over ${fmtTotalLine(t.line)} (Asia)`, effectiveP(t.over), `Garis Asia: separuh taruhan bisa kembali. Total ekspektasi ${lt.toFixed(2)}.`, t.over);
    add({ cat: "OU", code: "U", line: t.line }, `Under ${fmtTotalLine(t.line)} (Asia)`, effectiveP(t.under), `Garis Asia: separuh taruhan bisa kembali. Total ekspektasi ${lt.toFixed(2)}.`, t.under);
  }
  const pHs = 1 - mk.homeDist[0], pAs = 1 - mk.awayDist[0];
  add({ cat: "BTTS", code: "Y" }, "Kedua tim cetak gol: YA", mk.btts.yes, `P(${Hn} cetak) ${pct(pHs)} × P(${An} cetak) ${pct(pAs)} (dengan koreksi skor rendah).`);
  add({ cat: "BTTS", code: "N" }, "Kedua tim cetak gol: TIDAK", mk.btts.no, `Clean sheet ${Hn} ${pct(mk.cleanSheet.home)}, clean sheet ${An} ${pct(mk.cleanSheet.away)}.`);

  const tt = (team: "H" | "A") => {
    const name = team === "H" ? Hn : An;
    const lam = team === "H" ? c.lh : c.la;
    for (const r of team === "H" ? mk.teamTotals.home : mk.teamTotals.away) {
      if (r.line > 2.5) continue;
      add({ cat: "TT", code: "O", line: r.line, team }, `${name} Over ${r.line} gol`, r.over, `Ekspektasi gol ${name} ${lam.toFixed(2)}.`);
      add({ cat: "TT", code: "U", line: r.line, team }, `${name} Under ${r.line} gol`, 1 - r.over, `Ekspektasi gol ${name} ${lam.toFixed(2)}.`);
    }
  };
  tt("H");
  tt("A");
  add({ cat: "TT", code: "ANY2Y" }, "Ada tim yang cetak 2+ gol", mk.anyTeam2plus, `P(${Hn} ≥2) ${pct(1 - mk.homeDist[0] - mk.homeDist[1])}, P(${An} ≥2) ${pct(1 - mk.awayDist[0] - mk.awayDist[1])}.`);
  add({ cat: "TT", code: "ANY2N" }, "Tidak ada tim yang cetak 2+ gol", 1 - mk.anyTeam2plus, "Kedua tim maksimal 1 gol.");

  add({ cat: "HT", code: "HO", line: 0.5 }, "Babak 1 Over 0.5 gol", mk.ht.over05, `Ekspektasi gol babak 1 ${(lt * 0.45).toFixed(2)}; P(gol sebelum menit 45) ${pct(mk.ht.over05)}.`);
  add({ cat: "HT", code: "HU", line: 0.5 }, "Babak 1 Under 0.5 (0-0 HT)", 1 - mk.ht.over05, "Peluang babak pertama tanpa gol.");
  add({ cat: "HT", code: "HO", line: 1.5 }, "Babak 1 Over 1.5 gol", mk.ht.over15, "Butuh 2+ gol sebelum turun minum.");
  add({ cat: "HT", code: "HU", line: 1.5 }, "Babak 1 Under 1.5 gol", 1 - mk.ht.over15, "Maksimal 1 gol di babak pertama.");
  add({ cat: "HT", code: "H1" }, `${Hn} unggul di babak 1`, mk.ht.home, "Hasil babak pertama.");
  add({ cat: "HT", code: "HX" }, "Seri di babak 1", mk.ht.draw, "Babak pertama lebih sering seri karena gol lebih sedikit.");
  add({ cat: "HT", code: "H2" }, `${An} unggul di babak 1`, mk.ht.away, "Hasil babak pertama.");

  const cq = c.corners.dataQuality;
  for (const l of c.corners.lines) {
    add({ cat: "CORN", code: "O", line: l.line }, `Corner Over ${l.line}`, l.over, `Ekspektasi corner ${c.corners.lambda.toFixed(1)} (data: ${cq}).`);
    add({ cat: "CORN", code: "U", line: l.line }, `Corner Under ${l.line}`, 1 - l.over, `Ekspektasi corner ${c.corners.lambda.toFixed(1)} (data: ${cq}).`);
  }
  for (const l of c.cards.lines) {
    add({ cat: "CARD", code: "O", line: l.line }, `Kartu Over ${l.line}`, l.over, `Ekspektasi kartu ${c.cards.lambda.toFixed(1)} (data: ${c.cards.dataQuality}).`);
    add({ cat: "CARD", code: "U", line: l.line }, `Kartu Under ${l.line}`, 1 - l.over, `Ekspektasi kartu ${c.cards.lambda.toFixed(1)} (data: ${c.cards.dataQuality}).`);
  }
  return out;
}

function ahReason(name: string, line: number, s: Settle) {
  const parts = [`menang penuh ${pct(s.win)}`];
  if (s.halfWin > 0.005) parts.push(`menang ½ ${pct(s.halfWin)}`);
  if (s.push > 0.005) parts.push(`refund ${pct(s.push)}`);
  if (s.halfLoss > 0.005) parts.push(`kalah ½ ${pct(s.halfLoss)}`);
  parts.push(`kalah ${pct(s.loss)}`);
  return `${name} ${fmtLine(line)}: ${parts.join(", ")}.`;
}

/** Pilih rekomendasi bertingkat dengan diversifikasi kategori. */
export function selectRecs(cands: Rec[], params: ModelParams, input: MatchInput, extras: { corners: CountPred; cards: CountPred; topScore: { h: number; a: number; p: number }; htft: [string, number]; scorer?: ScorerPred }): Rec[] {
  const scored = cands.map((r) => {
    let w = CAT_WEIGHT[r.pick.cat] ?? 0.8;
    if (r.pick.cat === "CORN" && extras.corners.dataQuality === "default liga") w *= 0.75;
    if (r.pick.cat === "CARD" && extras.cards.dataQuality === "default liga") w *= 0.7;
    return { r, s: r.p * marketReliability(params, r.pick.cat), w };
  });
  const taken = new Set<string>();
  const pickTier = (lo: number, hi: number, n: number, tier: Tier, used: Set<string>) => {
    const res: Rec[] = [];
    const pool = scored.filter((x) => x.r.p >= lo && x.r.p <= hi).sort((a, b) => b.s * b.w - a.s * a.w);
    for (const x of pool) {
      if (res.length >= n) break;
      if (used.has(x.r.pick.cat) || taken.has(x.r.label)) continue;
      taken.add(x.r.label);
      used.add(x.r.pick.cat);
      res.push({ ...x.r, tier });
    }
    return res;
  };
  const usedA = new Set<string>();
  const aman = pickTier(0.7, 0.9, 3, "aman", usedA);
  const usedB = new Set<string>(["CS"]);
  const utama = pickTier(0.55, 0.78, 4, "utama", usedB);

  // Value bet dari odds yang dimasukkan
  const value: Rec[] = [];
  const o = input.odds;
  const find = (cat: MarketCat, code: string, line?: number) => cands.find((r) => r.pick.cat === cat && r.pick.code === code && (line === undefined || Math.abs((r.pick.line ?? 0) - line) < 1e-9));
  const tryValue = (r: Rec | undefined, odds: number | null) => {
    if (!r || odds === null || !(odds > 1)) return;
    const edge = r.settle ? evAsian(r.settle, odds) : r.p * odds - 1;
    if (edge < 0.03) return;
    // Hindari duplikat taruhan yang identik (mis. AH -0.5 = menang 1X2)
    if (value.some((v) => Math.abs(v.p - r.p) < 1e-6 && Math.abs((v.edge ?? 0) - edge) < 0.02)) return;
    value.push({ ...r, tier: "value", odds, edge, kellyPct: kelly(r.p, odds) * 100 });
  };
  tryValue(find("1X2", "1"), o.home);
  tryValue(find("1X2", "X"), o.draw);
  tryValue(find("1X2", "2"), o.away);
  tryValue(find("OU", "O", 2.5), o.over25);
  tryValue(find("OU", "U", 2.5), o.under25);
  tryValue(find("BTTS", "Y"), o.bttsYes);
  tryValue(find("BTTS", "N"), o.bttsNo);
  if (o.ahLine !== null && Number.isFinite(o.ahLine)) {
    tryValue(find("AH", "1", o.ahLine), o.ahHome);
    tryValue(find("AH", "2", -o.ahLine), o.ahAway);
  }
  value.sort((a, b) => (b.edge ?? 0) - (a.edge ?? 0));

  const spek: Rec[] = [];
  const ts = extras.topScore;
  spek.push({ pick: { cat: "CS", code: `${ts.h}-${ts.a}` }, label: `Skor tepat ${ts.h}-${ts.a}`, p: ts.p, fair: 1 / ts.p, conf: "SANGAT RENDAH", reason: "Skor tunggal paling mungkin; tetap peluang kecil karena banyak kemungkinan skor.", tier: "spekulatif" });
  spek.push({ pick: { cat: "HTFT", code: extras.htft[0] }, label: `HT/FT ${htftLabel(extras.htft[0], input)}`, p: extras.htft[1], fair: 1 / extras.htft[1], conf: extras.htft[1] >= 0.35 ? "SEDANG" : extras.htft[1] >= 0.22 ? "RENDAH" : "SANGAT RENDAH", reason: "Kombinasi hasil babak 1 dan akhir yang paling mungkin.", tier: "spekulatif" });
  if (extras.scorer)
    spek.push({ pick: { cat: "SCORER", code: "ANY", name: extras.scorer.name, team: extras.scorer.team }, label: `${extras.scorer.name} mencetak gol`, p: extras.scorer.anytime, fair: 1 / extras.scorer.anytime, conf: extras.scorer.anytime >= 0.5 ? "TINGGI" : extras.scorer.anytime >= 0.35 ? "SEDANG" : extras.scorer.anytime >= 0.22 ? "RENDAH" : "SANGAT RENDAH", reason: `Ekspektasi ${extras.scorer.lambda.toFixed(2)} gol · ${extras.scorer.basis}.`, tier: "spekulatif" });

  return [...aman, ...utama, ...value.slice(0, 3), ...spek];
}

export function htftLabel(code: string, input: MatchInput) {
  const n = (c: string) => (c === "1" ? input.home.name : c === "2" ? input.away.name : "Seri");
  const [a, b] = code.split("/");
  return `${n(a)} / ${n(b)}`;
}

export interface SettleOutcome {
  score: number; // 1 menang, 0.75 menang½, 0.5 refund, 0.25 kalah½, 0 kalah
  label: "Menang" | "Menang ½" | "Refund" | "Kalah ½" | "Kalah";
}

function fromSettle(s: Settle): SettleOutcome {
  if (s.win) return { score: 1, label: "Menang" };
  if (s.halfWin && s.halfLoss) return { score: 0.5, label: "Refund" };
  if (s.halfWin) return { score: 0.75, label: "Menang ½" };
  if (s.push) return { score: 0.5, label: "Refund" };
  if (s.halfLoss) return { score: 0.25, label: "Kalah ½" };
  return { score: 0, label: "Kalah" };
}

const bin = (b: boolean): SettleOutcome => (b ? { score: 1, label: "Menang" } : { score: 0, label: "Kalah" });

/** Nilai pilihan setelah hasil diketahui; null jika data hasil tidak cukup. */
export function settlePick(p: Pick, r: MatchResult, norm: (s: string) => string = (s) => s.toLowerCase().trim()): SettleOutcome | null {
  const d = r.ftH - r.ftA;
  const t = r.ftH + r.ftA;
  const res = d > 0 ? "1" : d < 0 ? "2" : "X";
  switch (p.cat) {
    case "1X2":
      return bin(p.code === res);
    case "DC":
      return bin(p.code.includes(res));
    case "DNB":
      if (res === "X") return { score: 0.5, label: "Refund" };
      return bin(p.code === res);
    case "AH":
      return fromSettle(settleAsian(p.code === "1" ? d : -d, p.line ?? 0, 1));
    case "OU": {
      const s = settleAsian(t, -(p.line ?? 2.5), 1);
      return fromSettle(p.code === "O" ? s : { win: s.loss, halfWin: s.halfLoss, push: s.push, halfLoss: s.halfWin, loss: s.win });
    }
    case "BTTS":
      return bin((r.ftH > 0 && r.ftA > 0) === (p.code === "Y"));
    case "TT": {
      if (p.code === "ANY2Y") return bin(r.ftH >= 2 || r.ftA >= 2);
      if (p.code === "ANY2N") return bin(r.ftH < 2 && r.ftA < 2);
      const g = p.team === "H" ? r.ftH : r.ftA;
      return bin(p.code === "O" ? g > (p.line ?? 0.5) : g < (p.line ?? 0.5));
    }
    case "HT": {
      if (r.htH === null || r.htA === null) return null;
      const ht = r.htH + r.htA;
      const hres = r.htH > r.htA ? "H1" : r.htH < r.htA ? "H2" : "HX";
      if (p.code === "HO") return bin(ht > (p.line ?? 0.5));
      if (p.code === "HU") return bin(ht < (p.line ?? 0.5));
      return bin(p.code === hres);
    }
    case "CORN":
      if (r.corners === null) return null;
      return bin(p.code === "O" ? r.corners > (p.line ?? 9.5) : r.corners < (p.line ?? 9.5));
    case "CARD":
      if (r.cards === null) return null;
      return bin(p.code === "O" ? r.cards > (p.line ?? 4.5) : r.cards < (p.line ?? 4.5));
    case "CS":
      return bin(p.code === `${r.ftH}-${r.ftA}`);
    case "HTFT": {
      if (r.htH === null || r.htA === null) return null;
      const hr = r.htH > r.htA ? "1" : r.htH < r.htA ? "2" : "X";
      return bin(p.code === `${hr}/${res}`);
    }
    case "SCORER": {
      if (!p.name) return null;
      const target = norm(p.name);
      return bin(r.scorers.some((s) => norm(s) === target || norm(s).includes(target) || target.includes(norm(s))));
    }
  }
  return null;
}
