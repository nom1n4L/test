// Integrasi AI: membaca screenshot menjadi data terstruktur, dan analisis naratif.
// Jalur yang dipakai:
//  1) Di dalam Artifact claude.ai → kapabilitas `sample` (memakai akun Claude penonton).
//  2) Aplikasi mandiri / APK → penyedia pilihan pengguna dengan API key sendiri:
//     Google Gemini (ada kuota gratis), layanan kompatibel OpenAI (OpenRouter, Groq, …), atau Claude.

import type Anthropic from "@anthropic-ai/sdk";
import { ALL_TEAM_FIELDS, fieldLabel } from "./fields";
import { uid } from "./sample";
import type { AIOpinion, H2HMatch, MatchInput, OddsInput, Player, Settings, TeamInput } from "./types";
import type { Prediction } from "./predict";
import { fmtLine } from "./odds";
import { AIError, geminiGenerate, oaiGenerate, OAI_PRESETS } from "./providers";
import { normalizeExtractions, repairJson, toArray } from "./normalize";

export { AIError };

/* eslint-disable @typescript-eslint/no-explicit-any */

export interface AIStatus {
  sample: boolean; // runtime Artifact
  images: boolean;
  maxImages: number;
  api: boolean; // penyedia dengan API key siap
  label: string; // nama penyedia untuk ditampilkan
}

let sampleFn: any = null;
let sampleChecked = false;

export async function getSample(): Promise<any> {
  if (sampleChecked) return sampleFn;
  const c = (window as any).claude;
  if (!c || typeof c.use !== "function") {
    sampleChecked = true;
    return null;
  }
  try {
    sampleFn = await Promise.race([c.use("sample"), new Promise((r) => setTimeout(() => r(null), 12000))]);
  } catch {
    sampleFn = null;
  }
  sampleChecked = true;
  return sampleFn;
}

export function providerLabel(cfg: Settings): string {
  if (cfg.aiProvider === "gemini") return `Gemini (${cfg.geminiModel || "gemini-flash-latest"})`;
  if (cfg.aiProvider === "openai") return `${OAI_PRESETS.find((p) => p.id === cfg.oaiPreset)?.label ?? "OpenAI-kompatibel"} (${cfg.oaiModel || "model belum dipilih"})`;
  return `Claude (${cfg.aiModel})`;
}

/** Apakah penyedia yang dipilih sudah lengkap pengaturannya. */
export function providerReady(cfg: Settings): boolean {
  if (cfg.aiProvider === "gemini") return !!cfg.geminiKey.trim();
  if (cfg.aiProvider === "openai") return !!cfg.oaiBase.trim() && !!cfg.oaiModel.trim() && (!!cfg.oaiKey.trim() || cfg.oaiPreset === "custom");
  return !!cfg.apiKey.trim();
}

export async function aiStatus(cfg: Settings): Promise<AIStatus> {
  const s = await getSample();
  if (s) {
    let images = false, maxImages = 0;
    try {
      const lim = await s.limits();
      if (lim?.images) {
        images = true;
        maxImages = lim.images.maxCount;
      }
    } catch {
      /* tanpa gambar */
    }
    return { sample: true, images, maxImages, api: false, label: "Claude (akun claude.ai)" };
  }
  const api = providerReady(cfg);
  const maxImages = cfg.aiProvider === "claude" ? 20 : cfg.aiProvider === "gemini" ? 10 : cfg.oaiPreset === "deepseek" ? 0 : 5;
  return { sample: false, images: api && maxImages > 0, maxImages, api, label: providerLabel(cfg) };
}

/** Detail teknis untuk dilaporkan bila terjadi error. */
export function aiErrorDetail(e: unknown): string {
  const code = (e as any)?.code ?? (e as any)?.name ?? "error";
  const msg = (e as any)?.message ?? String(e);
  return `${code}: ${msg}`.slice(0, 600);
}

export function aiErrorText(e: unknown): string {
  const code = (e as any)?.code as string | undefined;
  switch (code) {
    case "not_granted":
    case "sampling_disabled":
    case "not_declared":
    case "capability_disabled":
    case "capability_removed":
      return "Akses Claude untuk halaman ini tidak diizinkan. Fitur AI dimatikan untuk sesi ini.";
    case "images_unavailable":
      return "Tampilan ini tidak bisa mengirim gambar ke Claude. Isi data manual atau gunakan OCR.";
    case "image_rejected":
      return "Gambar ditolak (format/ukuran). Coba screenshot PNG/JPG yang lebih kecil.";
    case "rate_limited":
      return "Batas pemakaian tercapai (kuota gratis biasanya reset per menit/hari). Coba lagi nanti atau ganti model.";
    case "session_expired":
      return "Sesi claude.ai berakhir. Masuk lagi lalu coba ulang.";
    case "refused":
      return "Claude menolak permintaan ini. Ubah isi permintaan lalu coba lagi.";
    case "invalid_json":
      return "Balasan AI tidak berbentuk data yang bisa dibaca. Coba lagi atau kurangi jumlah gambar.";
    case "prompt_too_large":
      return "Data terlalu besar untuk sekali kirim. Kurangi catatan atau jumlah pemain.";
    case "cancelled":
      return "Dibatalkan.";
    case "auth":
      return "API key ditolak (salah atau tidak aktif). Periksa di menu Pengaturan.";
    case "no_ai":
      return "AI belum aktif. Isi API key di Pengaturan (Gemini punya kuota gratis), atau buka aplikasi sebagai Artifact di claude.ai.";
    case "model_not_found":
      return "Model tidak ditemukan. Buka Pengaturan → Muat daftar model, lalu pilih model yang tersedia.";
    case "no_credit":
      return "Saldo/kredit akun penyedia AI habis. Pilih model gratis atau penyedia lain.";
    case "no_vision":
      return "Model ini tidak bisa membaca gambar. Pilih model yang mendukung gambar, atau pakai Gemini.";
    case "network":
      return "Tidak bisa terhubung ke penyedia AI. Periksa koneksi internet.";
    case "empty":
      return "AI tidak memberi jawaban. Coba lagi atau ganti model.";
    case "bad_image":
      return "AI gagal memproses gambar. Coba screenshot ulang (PNG/JPG), potong bagian yang penting, atau unggah satu per satu.";
    default:
      return (e as any)?.message ? `Gagal menghubungi AI: ${(e as any).message}` : "Gagal menghubungi AI. Coba lagi.";
  }
}

// ---------- utilitas gambar ----------
async function fileToBase64Jpeg(file: Blob, maxSide = 1568): Promise<{ data: string; media: "image/jpeg" }> {
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((res, rej) => {
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = rej;
      i.src = url;
    });
    const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight));
    const c = document.createElement("canvas");
    c.width = Math.round(img.naturalWidth * scale);
    c.height = Math.round(img.naturalHeight * scale);
    const ctx = c.getContext("2d")!;
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(img, 0, 0, c.width, c.height);
    const dataUrl = c.toDataURL("image/jpeg", 0.88);
    return { data: dataUrl.split(",")[1], media: "image/jpeg" };
  } finally {
    URL.revokeObjectURL(url);
  }
}

// ---------- pemanggilan model ----------
interface CallOpts {
  prompt: string;
  images?: Blob[];
  json?: boolean;
  tier?: "default" | "complex" | "quick";
  onText?: (text: string) => void;
  signal?: AbortSignal;
  cfg: Settings;
}

function parseJsonLoose(text: string): unknown {
  const t = text.trim();
  try {
    return JSON.parse(t);
  } catch {
    /* lanjut */
  }
  const fence = [...t.matchAll(/```(?:json)?\s*([\s\S]*?)```/g)];
  for (let i = fence.length - 1; i >= 0; i--) {
    try {
      return JSON.parse(fence[i][1]);
    } catch {
      /* lanjut */
    }
  }
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a >= 0 && b > a) {
    try {
      return JSON.parse(t.slice(a, b + 1));
    } catch {
      /* lanjut */
    }
  }
  // Balasan terpotong: selamatkan bagian yang utuh
  const repaired = repairJson(t);
  if (repaired !== undefined) return repaired;
  throw new AIError("invalid_json", `Balasan bukan JSON: ${t.slice(0, 160)}`, text);
}

async function callAI(o: CallOpts): Promise<{ text: string; json?: unknown }> {
  const sample = await getSample();
  if (sample) {
    const opts: any = { modelTier: o.tier ?? "default", signal: o.signal };
    if (o.images?.length) opts.images = o.images;
    if (o.onText) opts.onText = ({ text }: { text: string }) => o.onText!(text);
    if (o.json) {
      const json = await sample.json(o.prompt, opts);
      return { text: "", json };
    }
    opts.cache = false;
    const r = await sample(o.prompt, opts);
    return { text: r.text };
  }
  const cfg = o.cfg;
  if (!providerReady(cfg)) throw new AIError("no_ai", "AI tidak tersedia");
  const prompt = o.json ? `${o.prompt}\n\nBalas hanya dengan JSON yang valid, tanpa teks lain.` : o.prompt;

  if (cfg.aiProvider === "gemini" || cfg.aiProvider === "openai") {
    const images: string[] = [];
    for (const img of o.images ?? []) images.push((await fileToBase64Jpeg(img)).data);
    const g = { prompt, images, json: o.json, onText: o.onText, signal: o.signal };
    const text = cfg.aiProvider === "gemini"
      ? await geminiGenerate(cfg.geminiKey.trim(), cfg.geminiModel.trim(), g)
      : await oaiGenerate(cfg.oaiBase.trim(), cfg.oaiKey.trim(), cfg.oaiModel.trim(), cfg.oaiPreset, g);
    return o.json ? { text, json: parseJsonLoose(text) } : { text };
  }

  // Claude (Anthropic SDK)
  const { default: AnthropicSDK } = await import("@anthropic-ai/sdk");
  const client = new AnthropicSDK({ apiKey: cfg.apiKey.trim(), dangerouslyAllowBrowser: true });
  const content: Anthropic.Beta.BetaContentBlockParam[] = [];
  for (const img of o.images ?? []) {
    const { data, media } = await fileToBase64Jpeg(img);
    content.push({ type: "image", source: { type: "base64", media_type: media, data } });
  }
  content.push({ type: "text", text: prompt });
  try {
    const params: any = {
      model: cfg.aiModel || "claude-opus-5-5",
      max_tokens: 32000,
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      output_config: { effort: o.tier === "complex" ? "high" : "medium" },
      messages: [{ role: "user", content }],
    };
    const stream = client.beta.messages.stream(params, { signal: o.signal });
    if (o.onText) stream.on("text", (_delta: string, snapshot: string) => o.onText!(snapshot));
    const msg = await stream.finalMessage();
    if (msg.stop_reason === "refusal") throw new AIError("refused", "Permintaan ditolak");
    const text = msg.content.map((b) => (b.type === "text" ? b.text : "")).join("");
    if (o.json) return { text, json: parseJsonLoose(text) };
    return { text };
  } catch (e) {
    if (e instanceof AIError) throw e;
    if (e instanceof AnthropicSDK.AuthenticationError) throw new AIError("auth", "API key tidak valid");
    if (e instanceof AnthropicSDK.RateLimitError) throw new AIError("rate_limited", "Rate limit");
    if (e instanceof AnthropicSDK.APIUserAbortError) throw new AIError("cancelled", "Dibatalkan");
    if (e instanceof AnthropicSDK.APIError) throw new AIError("upstream_error", `${e.status ?? ""} ${e.message}`);
    throw e;
  }
}

/** Tes singkat koneksi ke penyedia yang dipilih. */
export async function testAI(cfg: Settings, signal?: AbortSignal): Promise<string> {
  const r = await callAI({ prompt: "Balas persis dengan satu kata: SIAP", cfg, tier: "quick", signal });
  return r.text.trim().slice(0, 80);
}

// ---------- ekstraksi screenshot ----------
const TEAM_KEYS = ALL_TEAM_FIELDS.map((f) => f.key);

function extractionPrompt(input: MatchInput, n: number): string {
  const teamShape = `{${TEAM_KEYS.map((k) => `"${k}": number|null`).join(", ")}, "form": string|null, "players": [{"name": string, "pos": "GK"|"DEF"|"MID"|"FWD", "goals": number|null, "assists": number|null, "apps": number|null, "minutes": number|null, "xg": number|null, "penTaker": boolean, "status": "fit"|"doubt"|"out"}]}`;
  return `Kamu adalah ekstraktor data statistik sepak bola yang sangat teliti. Terlampir ${n} screenshot (bisa berisi klasemen, statistik tim, form, head-to-head, statistik pemain, susunan pemain, cedera/skorsing, odds, atau wasit). Semua screenshot milik SATU pertandingan.

Pertandingan: ${input.home.name || "(nama tuan rumah belum diisi — ambil dari gambar)"} (TUAN RUMAH) vs ${input.away.name || "(nama tim tamu belum diisi — ambil dari gambar)"} (TIM TAMU). Kompetisi: ${input.leagueName}.

ATURAN WAJIB:
1. Isi HANYA angka yang benar-benar terlihat. Jangan menebak. Angka yang tidak ada, terpotong, atau tidak terbaca = null, dan sebutkan di "unclear".
2. Bedakan statistik KESELURUHAN, KANDANG, TANDANG, dan N LAGA TERAKHIR. Untuk tuan rumah, kolom venue* = statistik KANDANG. Untuk tim tamu, kolom venue* = statistik TANDANG. Jangan isi venue* dengan statistik keseluruhan.
3. Jika ada beberapa musim, pakai musim BERJALAN. Jika ada beberapa kompetisi, pakai kompetisi pertandingan ini.
4. Boleh menghitung turunan sederhana dari angka yang terlihat (mis. total gol dari daftar skor 5 laga terakhir, atau rata-rata = total / jumlah laga). Jelaskan setiap perhitungan di "notes".
5. Satuan: gf, ga, venueGF, venueGA, formGF, formGA, cleanSheets, failedToScore = TOTAL. xgFor, xgAgainst, shotsFor, sotFor, shotsAgainst, sotAgainst, cornersFor, cornersAgainst, yellowPg, redPg, pensForPg, pensAgainstPg = RATA-RATA PER LAGA. possession, fhGFPct, fhGAPct = persen 0-100.
6. form: huruf W/D/L (Win/Draw/Lose), laga TERBARU paling kiri. formGF/formGA = total gol di laga-laga form tersebut.
7. h2h: tiap laga {"hg": gol tim yang SEKARANG tuan rumah, "ag": gol tim yang SEKARANG tamu, "homeAtHome": true jika tuan rumah sekarang bermain di kandang pada laga itu, "date": string|null}.
8. players: pemain penting saja (pencetak gol/assist teratas, pemain cedera/skorsing). status "out" = pasti absen, "doubt" = diragukan, selain itu "fit". penTaker = eksekutor penalti jika terlihat.
9. odds: format desimal. ahLine = garis handicap Asia untuk TUAN RUMAH (negatif jika tuan rumah memberi voor), ahHome/ahAway = odds-nya.
10. Jika screenshot memperlihatkan tuan rumah/tamu terbalik dibanding nama di atas, sesuaikan dengan nama di atas.

Balas HANYA dengan satu objek JSON:
{"homeName": string|null, "awayName": string|null, "league": string|null, "kickoff": string|null,
 "home": ${teamShape},
 "away": (bentuk sama seperti home),
 "h2h": [{"hg": number, "ag": number, "homeAtHome": boolean, "date": string|null}],
 "referee": {"name": string|null, "yellowPg": number|null, "redPg": number|null, "pensPg": number|null},
 "odds": {"home": number|null, "draw": number|null, "away": number|null, "over25": number|null, "under25": number|null, "bttsYes": number|null, "bttsNo": number|null, "ahLine": number|null, "ahHome": number|null, "ahAway": number|null},
 "notes": "ringkasan konteks penting yang tidak masuk kolom (cedera, rotasi, motivasi, perhitungan yang kamu lakukan)",
 "unclear": ["angka/bagian yang tidak terbaca"]}`;
}

export interface Extraction {
  homeName?: string | null;
  awayName?: string | null;
  league?: string | null;
  kickoff?: string | null;
  home?: Omit<Partial<TeamInput>, "players"> & { players?: Partial<Player>[] };
  away?: Omit<Partial<TeamInput>, "players"> & { players?: Partial<Player>[] };
  h2h?: Partial<H2HMatch>[];
  referee?: { name?: string | null; yellowPg?: number | null; redPg?: number | null; pensPg?: number | null };
  odds?: Partial<OddsInput>;
  notes?: string;
  unclear?: string[];
}

function mergeExtractions(list: Extraction[]): Extraction {
  const out: Extraction = { home: { players: [] }, away: { players: [] }, h2h: [], referee: {}, odds: {}, notes: "", unclear: [] };
  for (const e of list) {
    out.homeName = out.homeName || e.homeName;
    out.awayName = out.awayName || e.awayName;
    out.league = out.league || e.league;
    out.kickoff = out.kickoff || e.kickoff;
    for (const side of ["home", "away"] as const) {
      const src = e[side];
      if (!src) continue;
      const dst = out[side]!;
      for (const [k, v] of Object.entries(src)) {
        if (k === "players") continue;
        if (v !== null && v !== undefined && v !== "" && (dst as any)[k] == null) (dst as any)[k] = v;
      }
      for (const p of toArray(src.players)) {
        if (!p?.name) continue;
        const nm = String(p.name).toLowerCase();
        if (!dst.players!.some((x) => String(x.name ?? "").toLowerCase() === nm)) dst.players!.push({ ...p, name: String(p.name) });
      }
    }
    for (const m of toArray(e.h2h)) out.h2h!.push(m);
    for (const [k, v] of Object.entries(e.referee ?? {})) if (v != null && (out.referee as any)[k] == null) (out.referee as any)[k] = v;
    for (const [k, v] of Object.entries(e.odds ?? {})) if (v != null && (out.odds as any)[k] == null) (out.odds as any)[k] = v;
    if (e.notes) out.notes = [out.notes, String(e.notes)].filter(Boolean).join("\n");
    out.unclear!.push(...toArray(e.unclear).map(String));
  }
  return out;
}

const FATAL = new Set(["auth", "rate_limited", "no_ai", "cancelled", "not_granted", "sampling_disabled", "not_declared", "capability_disabled", "no_credit", "session_expired", "model_not_found", "network"]);
const isFatal = (e: unknown) => FATAL.has((e as any)?.code);

async function readImages(imgs: Blob[], input: MatchInput, o: { cfg: Settings; signal?: AbortSignal }): Promise<Extraction[]> {
  const prompt = extractionPrompt(input, imgs.length);
  let raw: unknown;
  try {
    raw = (await callAI({ prompt, images: imgs, json: true, tier: "default", signal: o.signal, cfg: o.cfg })).json;
  } catch (e) {
    if (isFatal(e) || !["invalid_json", "empty"].includes((e as any)?.code)) throw e;
    // Coba sekali lagi tanpa mode JSON khusus
    const r = await callAI({ prompt: `${prompt}\n\nPENTING: jawab dengan SATU objek JSON saja, tanpa penjelasan.`, images: imgs, tier: "default", signal: o.signal, cfg: o.cfg });
    raw = parseJsonLoose(r.text);
  }
  const list = normalizeExtractions(raw);
  if (!list.length) throw new AIError("invalid_json", "Bentuk balasan tidak dikenali");
  return list;
}

export async function extractFromImages(
  files: Blob[],
  input: MatchInput,
  opts: { cfg: Settings; maxImages: number; signal?: AbortSignal; onProgress?: (msg: string) => void },
): Promise<Extraction> {
  const per = Math.max(1, Math.min(opts.maxImages || 5, 20));
  const batches: number[][] = [];
  for (let i = 0; i < files.length; i += per) batches.push(files.slice(i, i + per).map((_, j) => i + j));
  const results: Extraction[] = [];
  const failed: string[] = [];
  let lastErr: unknown = null;
  for (let b = 0; b < batches.length; b++) {
    const idx = batches[b];
    opts.onProgress?.(batches.length > 1 ? `Membaca kelompok gambar ${b + 1} dari ${batches.length}…` : files.length > 1 ? `AI sedang membaca ${files.length} screenshot…` : "AI sedang membaca screenshot…");
    try {
      results.push(...(await readImages(idx.map((i) => files[i]), input, opts)));
      continue;
    } catch (e) {
      if (isFatal(e)) throw e;
      lastErr = e;
      if (idx.length === 1) {
        failed.push(`Gambar ${idx[0] + 1}: ${aiErrorText(e)}`);
        continue;
      }
    }
    // Kelompok gagal → baca satu per satu agar gambar yang bermasalah tidak menggagalkan semuanya
    for (const i of idx) {
      opts.onProgress?.(`Membaca gambar ${i + 1} dari ${files.length} satu per satu…`);
      try {
        results.push(...(await readImages([files[i]], input, opts)));
      } catch (e) {
        if (isFatal(e)) throw e;
        lastErr = e;
        failed.push(`Gambar ${i + 1}: ${aiErrorText(e)}`);
      }
    }
  }
  if (!results.length) throw lastErr ?? new AIError("empty", "Tidak ada gambar yang terbaca");
  const merged = mergeExtractions(results);
  merged.unclear!.push(...failed);
  return merged;
}

// ---------- menerapkan ekstraksi ----------
export interface Change {
  id: string;
  label: string;
  from: string;
  to: string;
  apply: (m: MatchInput) => void;
}

const num = (v: unknown): number | null => {
  if (v === null || v === undefined || v === "") return null;
  const x = typeof v === "number" ? v : parseFloat(String(v).replace(",", ".").replace("%", ""));
  return Number.isFinite(x) ? x : null;
};

const show = (v: unknown) => (v === null || v === undefined || v === "" ? "—" : String(v));

export function diffExtraction(input: MatchInput, e: Extraction): Change[] {
  const ch: Change[] = [];
  if (e.homeName && e.homeName !== input.home.name)
    ch.push({ id: "homeName", label: "Nama tuan rumah", from: show(input.home.name), to: e.homeName, apply: (m) => (m.home.name = e.homeName!) });
  if (e.awayName && e.awayName !== input.away.name)
    ch.push({ id: "awayName", label: "Nama tim tamu", from: show(input.away.name), to: e.awayName, apply: (m) => (m.away.name = e.awayName!) });
  for (const side of ["home", "away"] as const) {
    const src = e[side];
    if (!src) continue;
    const teamName = side === "home" ? e.homeName || input.home.name || "Tuan rumah" : e.awayName || input.away.name || "Tim tamu";
    for (const k of TEAM_KEYS) {
      const v = num((src as any)[k]);
      if (v === null) continue;
      const cur = input[side][k] as number | null;
      if (cur === v) continue;
      ch.push({ id: `${side}.${k}`, label: `${teamName}: ${fieldLabel(k, side)}`, from: show(cur), to: String(v), apply: (m) => ((m[side] as any)[k] = v) });
    }
    if (typeof src.form === "string" && src.form.trim()) {
      const f = src.form.toUpperCase().replace(/[^WDLMSK]/g, "");
      if (f && f !== input[side].form)
        ch.push({ id: `${side}.form`, label: `${teamName}: Form terakhir`, from: show(input[side].form), to: f, apply: (m) => (m[side].form = f) });
    }
    for (const p of toArray(src.players)) {
      if (!p?.name) continue;
      p.name = String(p.name);
      const existing = input[side].players.find((x) => x.name.toLowerCase() === p.name!.toLowerCase());
      const pos = (["GK", "DEF", "MID", "FWD"] as const).includes(p.pos as any) ? (p.pos as Player["pos"]) : "MID";
      const status = (["fit", "doubt", "out"] as const).includes(p.status as any) ? (p.status as Player["status"]) : "fit";
      const desc = [p.goals != null ? `${p.goals} gol` : null, p.apps != null ? `${p.apps} laga` : null, status !== "fit" ? (status === "out" ? "ABSEN" : "diragukan") : null].filter(Boolean).join(", ");
      ch.push({
        id: `${side}.player.${p.name}`,
        label: `${teamName}: pemain ${p.name}`,
        from: existing ? "ada" : "—",
        to: existing ? `perbarui (${desc || "data"})` : `tambah (${pos}${desc ? ", " + desc : ""})`,
        apply: (m) => {
          const list = m[side].players;
          const ex = list.find((x) => x.name.toLowerCase() === p.name!.toLowerCase());
          const patch: Partial<Player> = { pos, status };
          for (const k of ["goals", "assists", "apps", "minutes", "xg"] as const) {
            const v = num((p as any)[k]);
            if (v !== null) (patch as any)[k] = v;
          }
          if (p.penTaker) patch.penTaker = true;
          if (ex) Object.assign(ex, patch);
          else list.push({ id: uid(), name: p.name!, pos, goals: null, assists: null, apps: null, minutes: null, xg: null, penTaker: false, status, ...patch });
        },
      });
    }
  }
  const h2h = toArray(e.h2h).filter((m) => m && num(m.hg) !== null && num(m.ag) !== null);
  if (h2h.length)
    ch.push({
      id: "h2h",
      label: `Head-to-head (${h2h.length} laga)`,
      from: `${input.h2h.length} laga`,
      to: h2h.map((m) => `${m.hg}-${m.ag}${m.homeAtHome === false ? " (tandang)" : ""}`).join(", "),
      apply: (m) => {
        m.h2h = h2h.map((x) => ({ hg: num(x.hg)!, ag: num(x.ag)!, homeAtHome: x.homeAtHome !== false, date: x.date ?? undefined }));
      },
    });
  const ref = e.referee ?? {};
  if (ref.name && ref.name !== input.referee.name) ch.push({ id: "ref.name", label: "Wasit", from: show(input.referee.name), to: ref.name, apply: (m) => (m.referee.name = ref.name!) });
  for (const k of ["yellowPg", "redPg", "pensPg"] as const) {
    const v = num(ref[k]);
    if (v !== null && v !== input.referee[k]) {
      const lbl = { yellowPg: "kartu kuning/laga", redPg: "kartu merah/laga", pensPg: "penalti/laga" }[k];
      ch.push({ id: `ref.${k}`, label: `Wasit: ${lbl}`, from: show(input.referee[k]), to: String(v), apply: (m) => (m.referee[k] = v) });
    }
  }
  for (const [k, raw] of Object.entries(e.odds ?? {})) {
    const v = num(raw);
    const key = k as keyof OddsInput;
    if (v === null || !(key in input.odds) || input.odds[key] === v) continue;
    ch.push({ id: `odds.${k}`, label: `Odds: ${k === "ahLine" ? "garis handicap tuan rumah" : k}`, from: show(input.odds[key]), to: k === "ahLine" ? fmtLine(v) : String(v), apply: (m) => (m.odds[key] = v) });
  }
  if (typeof e.notes === "string" && e.notes.trim())
    ch.push({ id: "notes", label: "Catatan konteks dari AI", from: input.notes ? "ada" : "—", to: e.notes.slice(0, 140) + (e.notes.length > 140 ? "…" : ""), apply: (m) => (m.notes = [m.notes, e.notes].filter(Boolean).join("\n")) });
  return ch;
}

// ---------- analisis naratif ----------
const pc = (p: number) => `${(p * 100).toFixed(1)}%`;

function teamBlock(t: TeamInput, venue: string) {
  const v = (x: number | null, d = 2) => (x === null ? "—" : Number.isInteger(x) ? String(x) : x.toFixed(d));
  const lines = [
    `Klasemen #${v(t.position)} · Main ${v(t.played)} · M-S-K ${v(t.wins)}-${v(t.draws)}-${v(t.losses)} · Gol ${v(t.gf)}-${v(t.ga)} · CS ${v(t.cleanSheets)} · gagal cetak ${v(t.failedToScore)}`,
    `${venue}: main ${v(t.venuePlayed)} · M-S-K ${v(t.venueWins)}-${v(t.venueDraws)}-${v(t.venueLosses)} · gol ${v(t.venueGF)}-${v(t.venueGA)}`,
    `Form (terbaru kiri): ${t.form || "—"} · gol form ${v(t.formGF)}-${v(t.formGA)}`,
    `Per laga: xG ${v(t.xgFor)} · xGA ${v(t.xgAgainst)} · tembakan ${v(t.shotsFor)} (SoT ${v(t.sotFor)}) · tembakan lawan ${v(t.shotsAgainst)} (SoT ${v(t.sotAgainst)}) · penguasaan ${v(t.possession)}%`,
    `Babak 1: ${v(t.fhGFPct)}% gol dicetak, ${v(t.fhGAPct)}% kebobolan · corner ${v(t.cornersFor)}/${v(t.cornersAgainst)} · kuning ${v(t.yellowPg)} · merah ${v(t.redPg)} · penalti ${v(t.pensForPg)}/${v(t.pensAgainstPg)}`,
    `Absen tambahan: serang ${t.absAttack}/3, bertahan ${t.absDefense}/3 · motivasi ${t.motivation} · istirahat ${v(t.restDays)} hari`,
    `Pemain: ${t.players.length ? t.players.map((p) => `${p.name} (${p.pos}, ${p.goals ?? "?"} gol/${p.apps ?? "?"} laga${p.xg !== null ? `, xG ${p.xg}` : ""}${p.penTaker ? ", penalti" : ""}${p.status !== "fit" ? `, ${p.status === "out" ? "ABSEN" : "diragukan"}` : ""})`).join("; ") : "—"}`,
  ];
  return lines.join("\n");
}

export function analysisPrompt(input: MatchInput, pred: Prediction, lessons: string[]): string {
  const H = input.home.name, A = input.away.name;
  const mk = pred.mk;
  const o25 = mk.totals.find((t) => t.line === 2.5)!;
  const model = [
    `Ekspektasi gol (λ): ${H} ${pred.lam.lh.toFixed(2)} · ${A} ${pred.lam.la.toFixed(2)} · porsi babak 1 ${(pred.lam.fh * 100).toFixed(0)}%/${(pred.lam.fa * 100).toFixed(0)}%`,
    `Sinyal: ${pred.lam.signals.map((s) => `${s.label} ${s.lh.toFixed(2)}-${s.la.toFixed(2)} (bobot ${s.weight.toFixed(2)})`).join("; ")}`,
    `Penyesuaian: ${pred.lam.adjustments.map((a) => a.label).join("; ") || "—"}`,
    `1X2: ${pc(mk.x12.home)} / ${pc(mk.x12.draw)} / ${pc(mk.x12.away)} · Over 2.5 ${pc(o25.over)} · BTTS ${pc(mk.btts.yes)} · HT gol ${pc(mk.ht.over05)}`,
    `Skor teratas: ${pred.topFT.slice(0, 6).map((s) => `${s.h}-${s.a} ${pc(s.p)}`).join(", ")}`,
    `HT teratas: ${pred.topHT.slice(0, 4).map((s) => `${s.h}-${s.a} ${pc(s.p)}`).join(", ")}`,
    `Garis handicap adil: ${H} ${fmtLine(mk.fairAhLine)} · garis total adil ${mk.fairTotalLine}`,
    `Corner λ ${pred.corners.lambda.toFixed(1)} · kartu λ ${pred.cards.lambda.toFixed(1)} · penalti ${pc(pred.pen.p)} · kartu merah ${pc(pred.red.p)}`,
    `Pencetak gol: ${[...pred.scorers.home, ...pred.scorers.away].sort((a, b) => b.anytime - a.anytime).slice(0, 5).map((s) => `${s.name} ${pc(s.anytime)}`).join(", ") || "—"}`,
    `Keyakinan model ${pred.confidence.score}/10 · konflik: ${pred.conflicts.map((c) => c.title).join("; ") || "—"}`,
  ].join("\n");
  const o = input.odds;
  const odds = [o.home, o.draw, o.away].every((x) => x) ? `Odds 1X2: ${o.home} / ${o.draw} / ${o.away}; O2.5 ${o.over25 ?? "—"}; BTTS ya ${o.bttsYes ?? "—"}; AH ${o.ahLine ?? "—"} (${o.ahHome ?? "—"}/${o.ahAway ?? "—"})` : "Odds: tidak diberikan";
  return `Kamu adalah analis data sepak bola tingkat tinggi dan peneliti taktik. Tugasmu: prediksi pra-pertandingan yang paling bisa dipertanggungjawabkan secara statistik, bukan sekadar menebak pemenang. Jangan ikut asumsi yang lemah; tantang kesimpulan dan cari bukti yang berlawanan.

PERTANDINGAN: ${H} (tuan rumah) vs ${A} (tamu) — ${input.leagueName}, ${input.kickoff.replace("T", " ")}${input.neutral ? " (venue netral)" : ""}${input.derby ? " · DERBY" : ""} · kepentingan: ${input.importance}
Rata-rata liga: tuan rumah ${input.homeAvg} gol, tamu ${input.awayAvg} gol per laga.

DATA ${H.toUpperCase()}:
${teamBlock(input.home, "Kandang")}

DATA ${A.toUpperCase()}:
${teamBlock(input.away, "Tandang")}

H2H (gol ${H} - gol ${A}): ${input.h2h.map((m) => `${m.hg}-${m.ag}${m.homeAtHome ? "" : " (di kandang lawan)"}`).join(", ") || "—"}
Wasit: ${input.referee.name || "—"} · kuning ${input.referee.yellowPg ?? "—"} · merah ${input.referee.redPg ?? "—"} · penalti ${input.referee.pensPg ?? "—"} per laga
${odds}
Catatan: ${input.notes || "—"}

OUTPUT MODEL STATISTIK BOLAMETRIK (Poisson/Dixon-Coles + bobot sumber yang dipelajari):
${model}

PELAJARAN DARI KESALAHAN PREDIKSI SEBELUMNYA (gunakan untuk mengoreksi bias):
${lessons.length ? lessons.slice(0, 12).map((l) => `- ${l}`).join("\n") : "- Belum ada riwayat."}

Jika ada screenshot terlampir, periksa setiap angka yang terlihat dan gunakan sebagai data utama; sebutkan angka yang tidak terbaca, jangan mengarang.
Data di atas bisa saja MINIM (banyak "—"). Jika begitu: katakan dengan jelas data apa yang kurang dan turunkan keyakinan. Kamu boleh memakai pengetahuan umummu tentang kekuatan, gaya main, dan kondisi kedua tim, tetapi tandai sebagai [PENGETAHUAN UMUM — bisa usang], jangan mengarang angka statistik spesifik, dan utamakan data yang diberikan bila bertentangan.

TULIS ANALISIS DALAM BAHASA INDONESIA (Markdown, pakai tabel bila membantu), dengan urutan:
1. **Form terkini** (≥5 laga: M/S/K, gol, clean sheet, BTTS, O/U) — jelaskan artinya, jangan hanya mengulang angka.
2. **Kandang vs tandang** — prioritaskan data kandang tuan rumah & tandang tim tamu.
3. **Profil serangan & pertahanan** (gol, xG/xGA, tembakan, kelemahan berulang).
4. **Matchup taktik** — setiap kesimpulan diberi label HIGH/MEDIUM/LOW CONFIDENCE; jangan mengarang info taktik yang tidak didukung data.
5. **Model gol** — rentang total gol, kecenderungan O1.5/O2.5/U2.5, BTTS, gol babak 1.
6. **Model skor** — 4-6 skor diurutkan dengan label HIGH/MEDIUM/LOW.
7. **1X2** — bukti untuk tiap sisi, HASIL PALING MUNGKIN dan ALTERNATIF UTAMA.
8. **Pemain** — calon pencetak gol dengan kategori kemungkinan (jangan pernah bilang "pasti mencetak gol").
9. **Corner, kartu, penalti & kartu merah** — hanya jika datanya ada; bila tidak, katakan datanya kurang.
10. **Babak 1 vs babak 2**.
11. **SINYAL YANG BERTENTANGAN** — bukti terkuat yang berlawanan arah, dan mana yang layak diberi bobot lebih.
12. **Setuju/tidak dengan model** — di mana kamu berbeda dengan model statistik di atas dan mengapa.
13. **PROYEKSI AKHIR**: skor paling mungkin, kecenderungan hasil, lingkungan gol, BTTS, skor HT, corner, kartu, pencetak gol, keyakinan X/10, 5 BUKTI TERKUAT, RISIKO TERBESAR.
14. **Rekomendasi pasaran** (aman / utama / berisiko), termasuk handicap dan O/U — "aman" bukan berarti pasti.

Pisahkan FAKTA → INTERPRETASI → INFERENSI MODEL → PREDIKSI. Jangan beri kepastian; jangan pakai 90%+ tanpa dasar kuat. Langsung ke analisis, tanpa disclaimer generik.

Di AKHIR jawaban, tulis blok kode JSON persis seperti ini (angka = estimasimu sendiri, 0-1, total 1X2 = 1):
\`\`\`json
{"home": 0.00, "draw": 0.00, "away": 0.00, "over25": 0.00, "btts": 0.00, "xgHome": 0.00, "xgAway": 0.00}
\`\`\``;
}

export async function analyzeMatch(
  input: MatchInput,
  pred: Prediction,
  lessons: string[],
  opts: { cfg: Settings; images?: Blob[]; signal?: AbortSignal; onText?: (t: string) => void },
): Promise<AIOpinion> {
  const r = await callAI({ prompt: analysisPrompt(input, pred, lessons), images: opts.images, tier: "complex", onText: opts.onText, signal: opts.signal, cfg: opts.cfg });
  return parseOpinion(r.text);
}

export function parseOpinion(text: string): AIOpinion {
  let probs: AIOpinion["probs"] = null;
  let xg: AIOpinion["xg"] = null;
  try {
    const j = parseJsonLoose(text.slice(Math.max(0, text.lastIndexOf("```json")))) as any;
    const h = num(j.home), d = num(j.draw), a = num(j.away);
    if (h !== null && d !== null && a !== null && h + d + a > 0.5) {
      probs = { home: h, draw: d, away: a, over25: num(j.over25), btts: num(j.btts) };
      const xh = num(j.xgHome), xa = num(j.xgAway);
      if (xh !== null && xa !== null) xg = { home: xh, away: xa };
    }
  } catch {
    /* tanpa angka */
  }
  const clean = text.replace(/```json[\s\S]*?```\s*$/, "").trim();
  return { createdAt: new Date().toISOString(), text: clean, probs, xg };
}
