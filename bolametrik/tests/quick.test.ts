import { afterEach, describe, expect, it, vi } from "vitest";
import { lambdasFromOdds, matrixOutcome, over, scoreMatrix, totalDist } from "../src/lib/math";
import { defaultParams } from "../src/lib/model";
import { predict } from "../src/lib/predict";
import { emptyMatch } from "../src/lib/sample";
import { geminiGenerate, oaiGenerate, AIError } from "../src/lib/providers";

describe("mode cepat / data minim", () => {
  it("membalik peluang 1X2 + O/U menjadi ekspektasi gol", () => {
    const m = scoreMatrix(1.8, 0.9, -0.07, 1);
    const o = matrixOutcome(m);
    const inv = lambdasFromOdds(o.home, o.draw, o.away, over(totalDist(m), 2.5), 2.7);
    expect(inv.lh).toBeCloseTo(1.8, 1);
    expect(inv.la).toBeCloseTo(0.9, 1);
  });

  it("hanya nama tim → tetap ada prediksi dari rata-rata liga", () => {
    const m = emptyMatch("idn");
    m.home.name = "A";
    m.away.name = "B";
    const p = predict(m, defaultParams());
    expect(p.lam.signals.map((s) => s.key)).toEqual(["league"]);
    expect(p.quick.length).toBeGreaterThan(10);
    expect(p.confidence.score).toBeLessThan(4);
  });

  it("odds saja → sinyal pasar dipakai dan mengunggulkan favorit bandar", () => {
    const m = emptyMatch("epl");
    m.home.name = "A";
    m.away.name = "B";
    m.odds = { ...m.odds, home: 4.5, draw: 3.8, away: 1.75, over25: 1.7, under25: 2.15 };
    const p = predict(m, defaultParams());
    expect(p.lam.signals.some((s) => s.key === "odds")).toBe(true);
    expect(p.mk.x12.away).toBeGreaterThan(p.mk.x12.home);
    expect(p.pick).toBe("2");
  });

  it("posisi klasemen, form satu tim, dan penilaian kekuatan menggeser prediksi", () => {
    const m = emptyMatch("idn");
    m.home.name = "A";
    m.away.name = "B";
    m.home.position = 2;
    m.away.position = 17;
    m.home.form = "WWWDW";
    m.home.rating = 5;
    m.away.rating = 2;
    const p = predict(m, defaultParams());
    const keys = p.lam.signals.map((s) => s.key);
    expect(keys).toContain("position");
    expect(keys).toContain("formPts");
    expect(keys).toContain("rating");
    expect(p.mk.x12.home).toBeGreaterThan(0.6);
  });
});

function sse(lines: string[]) {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      for (const l of lines) c.enqueue(enc.encode(l));
      c.close();
    },
  });
}

describe("penyedia AI (respons tiruan)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("Gemini: menggabungkan potongan SSE dan melewati bagian 'thought'", async () => {
    const fetchMock = vi.fn(async () => new Response(sse([
      'data: {"candidates":[{"content":{"parts":[{"text":"berpikir","thought":true}]}}]}\n\n',
      'data: {"candidates":[{"content":{"parts":[{"text":"Halo "}]}}]}\n\n',
      'data: {"candidates":[{"content":{"parts":[{"text":"dunia"}]},"finishReason":"STOP"}]}\n\n',
    ]), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const seen: string[] = [];
    const text = await geminiGenerate("KEY", "gemini-flash-latest", { prompt: "hai", images: ["AAAA"], onText: (t) => seen.push(t) });
    expect(text).toBe("Halo dunia");
    expect(seen.at(-1)).toBe("Halo dunia");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("models/gemini-flash-latest:streamGenerateContent?alt=sse&key=KEY");
    const body = JSON.parse(String(init.body));
    expect(body.contents[0].parts[0].inlineData.mimeType).toBe("image/jpeg");
  });

  it("Gemini: key salah → kode auth", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { code: 400, message: "API key not valid. Please pass a valid API key.", status: "INVALID_ARGUMENT" } }), { status: 400 })));
    await expect(geminiGenerate("x", "m", { prompt: "hai", images: [] })).rejects.toMatchObject({ code: "auth" });
  });

  it("OpenAI-kompatibel: delta SSE, [DONE], dan header OpenRouter", async () => {
    const fetchMock = vi.fn(async () => new Response(sse([
      'data: {"choices":[{"delta":{"content":"Skor "}}]}\n\n',
      'data: {"choices":[{"delta":{"content":"2-1"}}]}\n\n',
      "data: [DONE]\n\n",
    ]), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const text = await oaiGenerate("https://openrouter.ai/api/v1/", "k", "model:free", "openrouter", { prompt: "hai", images: ["BBBB"] });
    expect(text).toBe("Skor 2-1");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://openrouter.ai/api/v1/chat/completions");
    const h = init.headers as Record<string, string>;
    expect(h.Authorization).toBe("Bearer k");
    expect(h["X-Title"]).toBe("BolaMetrik");
    const body = JSON.parse(String(init.body));
    expect(body.messages[0].content[1].image_url.url).toMatch(/^data:image\/jpeg;base64,BBBB/);
  });

  it("OpenAI-kompatibel: 429 → rate_limited", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { message: "Rate limit exceeded: free-models-per-day" } }), { status: 429 })));
    const err = await oaiGenerate("https://api.groq.com/openai/v1", "k", "m", "groq", { prompt: "x", images: [] }).catch((e) => e);
    expect(err).toBeInstanceOf(AIError);
    expect(err.code).toBe("rate_limited");
  });
});
