import { describe, expect, it } from "vitest";
import { diffExtraction, parseOpinion } from "../src/lib/ai";
import { parseOcr } from "../src/lib/ocr";
import { emptyMatch } from "../src/lib/sample";

describe("ekstraksi AI", () => {
  it("mengubah JSON ekstraksi menjadi daftar perubahan yang bisa diterapkan", () => {
    const m = emptyMatch("epl");
    const ch = diffExtraction(m, {
      homeName: "Arsenal",
      awayName: "Chelsea",
      home: { played: 10, gf: 22, ga: 8, venueGF: 13, form: "wwdlw", players: [{ name: "Saka", pos: "FWD", goals: 6, apps: 10, status: "fit" }] },
      away: { played: 10, gf: "15" as unknown as number, xgFor: null, players: [{ name: "Palmer", pos: "MID", goals: 7, status: "out" }] },
      h2h: [{ hg: 2, ag: 2, homeAtHome: false }],
      odds: { home: 2.1, ahLine: -0.25 },
      unclear: ["xG tim tamu terpotong"],
    });
    const next = JSON.parse(JSON.stringify(m));
    for (const c of ch) c.apply(next);
    expect(next.home.name).toBe("Arsenal");
    expect(next.home.gf).toBe(22);
    expect(next.home.form).toBe("WWDLW");
    expect(next.away.gf).toBe(15);
    expect(next.away.xgFor).toBeNull();
    expect(next.away.players[0].status).toBe("out");
    expect(next.home.players[0].goals).toBe(6);
    expect(next.h2h[0]).toMatchObject({ hg: 2, ag: 2, homeAtHome: false });
    expect(next.odds.ahLine).toBe(-0.25);
  });

  it("membaca estimasi peluang di akhir analisis AI", () => {
    const op = parseOpinion('## Analisis\nTeks...\n\n```json\n{"home": 0.5, "draw": 0.27, "away": 0.23, "over25": 0.55, "btts": 0.5, "xgHome": 1.6, "xgAway": 1.0}\n```');
    expect(op.probs?.home).toBe(0.5);
    expect(op.xg?.away).toBe(1);
    expect(op.text).not.toContain("```json");
  });
});

describe("OCR heuristik", () => {
  it("mengenali baris perbandingan statistik dan form", () => {
    const { ext } = parseOcr(["Ball possession 58% 42%\nExpected goals (xG) 1.85 0.92\nShots on target 6 3\nCorner kicks 7 2\nW W D L W\nL D W W L"]);
    expect(ext.home?.possession).toBe(58);
    expect(ext.away?.xgFor).toBe(0.92);
    expect(ext.home?.sotFor).toBe(6);
    expect(ext.away?.cornersFor).toBe(2);
    expect(ext.home?.form).toBe("WWDLW");
    expect(ext.away?.form).toBe("LDWWL");
  });
});
