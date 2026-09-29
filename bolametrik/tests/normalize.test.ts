import { describe, expect, it } from "vitest";
import { normalizeExtractions, repairJson } from "../src/lib/normalize";
import { diffExtraction } from "../src/lib/ai";
import { emptyMatch } from "../src/lib/sample";

const apply = (raw: unknown) => {
  const m = emptyMatch("idn");
  for (const e of normalizeExtractions(raw)) for (const c of diffExtraction(m, e)) c.apply(m);
  return m;
};

describe("normalisasi balasan AI", () => {
  it("memperbaiki JSON yang terpotong", () => {
    const full = JSON.stringify({ homeName: "Persib", home: { gf: 20, ga: 9, players: [{ name: "Ciro", goals: 7 }, { name: "Beckham", goals: 4 }] }, away: { gf: 15 } });
    const cut = full.slice(0, full.indexOf("Beckham") + 12);
    const r = repairJson(cut) as { homeName: string; home: { gf: number; players: unknown[] } };
    expect(r.homeName).toBe("Persib");
    expect(r.home.gf).toBe(20);
    expect(r.home.players.length).toBeGreaterThanOrEqual(1);
  });

  it("menerima akar berupa array, pembungkus, dan statistik bersarang", () => {
    const m = apply([{ data: { homeTeam: { name: "Persib", stats: { gf: "20", ga: 9, possession: "55%" } }, awayTeam: { name: "Persija", statistics: { gf: 15 } } } }]);
    expect(m.home.name).toBe("Persib");
    expect(m.home.gf).toBe(20);
    expect(m.home.possession).toBe(55);
    expect(m.away.name).toBe("Persija");
    expect(m.away.gf).toBe(15);
  });

  it("tidak crash pada bentuk aneh (pemain objek, notes array, h2h string)", () => {
    const m = apply({
      homeName: "A",
      awayName: "B",
      home: { form: ["W", "W", "D"], players: { x: { name: "Ciro", goals: 5 }, y: "Beckham" } },
      away: { players: "tidak ada" },
      h2h: ["2-1", { score: "1 - 1" }, { hg: 0, ag: 2, homeAtHome: false }],
      notes: ["catatan 1", "catatan 2"],
      unclear: "xG terpotong",
      referee: "Thoriq Alkatiri",
    });
    expect(m.home.form).toBe("WWD");
    expect(m.home.players.map((p) => p.name)).toEqual(["Ciro", "Beckham"]);
    expect(m.h2h).toHaveLength(3);
    expect(m.h2h[1]).toMatchObject({ hg: 1, ag: 1 });
    expect(m.referee.name).toBe("Thoriq Alkatiri");
    expect(m.notes).toContain("catatan 1");
  });

  it("mengembalikan daftar kosong untuk sampah", () => {
    expect(normalizeExtractions("halo")).toEqual([]);
    expect(normalizeExtractions(null)).toEqual([]);
  });
});
