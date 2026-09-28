import { describe, expect, it } from "vitest";
import { asianHandicap, effectiveP, matrixOutcome, scoreMatrix, settleAsian, splitLine } from "../src/lib/math";
import { defaultParams } from "../src/lib/model";
import { predict } from "../src/lib/predict";
import { demoMatch, emptyMatch } from "../src/lib/sample";
import { evaluate, relearn, computeStats } from "../src/lib/learning";
import { settlePick } from "../src/lib/recommend";
import { ahIndo, fromDecimal, removeMargin, toDecimal } from "../src/lib/odds";
import type { MatchRecord } from "../src/lib/types";

const sum = (m: number[][]) => m.flat().reduce((s, v) => s + v, 0);

describe("math", () => {
  it("matriks skor ternormalisasi", () => {
    const m = scoreMatrix(1.6, 1.1, -0.07, 1.05);
    expect(sum(m)).toBeCloseTo(1, 9);
    const o = matrixOutcome(m);
    expect(o.home + o.draw + o.away).toBeCloseTo(1, 9);
    expect(o.home).toBeGreaterThan(o.away);
  });

  it("garis Asia dipecah benar", () => {
    expect(splitLine(-0.25)).toEqual([-0.5, 0]);
    expect(splitLine(-0.75)).toEqual([-1, -0.5]);
    expect(splitLine(0.5)).toEqual([0.5, 0.5]);
  });

  it("settle handicap seperempat", () => {
    // tuan rumah -0.25, hasil seri → kalah setengah
    expect(settleAsian(0, -0.25, 1).halfLoss).toBe(1);
    // tuan rumah -0.75, menang 1 gol → menang setengah
    expect(settleAsian(1, -0.75, 1).halfWin).toBe(1);
    // tuan rumah -1, menang 1 gol → push
    expect(settleAsian(1, -1, 1).push).toBe(1);
  });

  it("AH 0 sama dengan DNB", () => {
    const m = scoreMatrix(1.5, 1.2);
    const s = asianHandicap(m, 0);
    const o = matrixOutcome(m);
    expect(s.win).toBeCloseTo(o.home, 9);
    expect(s.push).toBeCloseTo(o.draw, 9);
    expect(effectiveP(s)).toBeGreaterThan(0.5);
  });
});

describe("odds", () => {
  it("konversi Indo/Malay", () => {
    expect(toDecimal("-2", "indo")).toBeCloseTo(1.5);
    expect(toDecimal("1.5", "indo")).toBeCloseTo(2.5);
    expect(toDecimal("0.8", "malay")).toBeCloseTo(1.8);
    expect(toDecimal("-0.5", "malay")).toBeCloseTo(3);
    expect(fromDecimal(1.5, "indo")).toBe("-2.00");
    expect(ahIndo(-0.75)).toBe("½-1");
    expect(ahIndo(0.25)).toBe("0-½");
    expect(ahIndo(1.5)).toBe("1½");
    const f = removeMargin([1.9, 3.4, 4.2]);
    expect(f.reduce((a, b) => a + b, 0)).toBeCloseTo(1);
  });
});

describe("predict", () => {
  it("contoh menghasilkan angka masuk akal", () => {
    const p = predict(demoMatch(), defaultParams());
    expect(p.lam.lh).toBeGreaterThan(1);
    expect(p.lam.lh).toBeLessThan(3);
    expect(p.lam.la).toBeGreaterThan(0.5);
    expect(p.mk.x12.home).toBeGreaterThan(p.mk.x12.away);
    const htftSum = Object.values(p.mk.htft).reduce((a, b) => a + b, 0);
    expect(htftSum).toBeCloseTo(1, 3);
    expect(p.scenario.ht[0] + p.scenario.h2[0]).toBe(p.scenario.ft[0]);
    expect(p.recs.length).toBeGreaterThan(3);
    expect(p.quick.length).toBeGreaterThan(10);
    expect(p.confidence.score).toBeGreaterThan(1);
    expect(p.scorers.home[0].name).toBe("R. Pratama");
    expect(p.corners.lambda).toBeGreaterThan(7);
    expect(p.pen.p).toBeGreaterThan(0.1);
  });

  it("laga tanpa data tetap berjalan (prior liga)", () => {
    const m = emptyMatch("epl");
    m.home.name = "A";
    m.away.name = "B";
    const p = predict(m, defaultParams());
    expect(p.lam.lh).toBeCloseTo(1.6, 1);
    expect(p.confidence.score).toBeLessThan(5);
  });
});

describe("learning", () => {
  it("replay memperbarui parameter & statistik", () => {
    const params = defaultParams();
    const recs: MatchRecord[] = [];
    for (let i = 0; i < 12; i++) {
      const input = demoMatch();
      input.kickoff = `2026-01-${String(i + 1).padStart(2, "0")}T19:00`;
      recs.push({
        id: `r${i}`,
        createdAt: input.kickoff,
        updatedAt: input.kickoff,
        input,
        snapshot: params,
        ai: null,
        result: { ftH: 3, ftA: 2, htH: 2, htA: 1, corners: 12, cards: 6, red: false, pen: true, scorers: ["R. Pratama"], settledAt: input.kickoff },
        sources: [],
      });
    }
    const st = relearn(recs);
    expect(st.params.learned).toBe(12);
    // Laga berulang dengan banyak gol → skala gol harus naik
    expect(st.params.goalScale).toBeGreaterThan(1);
    expect(st.params.leagueFactor["idn"]).toBeGreaterThan(1);
    expect(st.params.cornerScale).toBeGreaterThan(1);
    expect(st.log.length).toBe(12);
    const stats = computeStats(recs);
    expect(stats.settled).toBe(12);
    expect(stats.brier).not.toBeNull();
    const ev = evaluate(predict(recs[0].input, params), recs[0], recs[0].result!);
    expect(ev.lessons.length).toBeGreaterThan(1);
    expect(ev.scorerHits.find((s) => s.name === "R. Pratama")?.scored).toBe(true);
  });

  it("settle pilihan", () => {
    const r = { ftH: 2, ftA: 1, htH: 1, htA: 1, corners: 9, cards: 3, red: false, pen: false, scorers: [], settledAt: "" };
    expect(settlePick({ cat: "AH", code: "1", line: -0.75 }, r)?.label).toBe("Menang ½");
    expect(settlePick({ cat: "OU", code: "O", line: 2.5 }, r)?.score).toBe(1);
    expect(settlePick({ cat: "OU", code: "U", line: 3 }, r)?.label).toBe("Refund");
    expect(settlePick({ cat: "HTFT", code: "X/1" }, r)?.score).toBe(1);
    expect(settlePick({ cat: "BTTS", code: "Y" }, r)?.score).toBe(1);
    expect(settlePick({ cat: "CORN", code: "O", line: 9.5 }, r)?.score).toBe(0);
  });
});
