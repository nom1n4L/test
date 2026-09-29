// Penyedia AI selain Claude: Google Gemini (ada kuota gratis) dan layanan
// kompatibel OpenAI (OpenRouter, Groq, OpenAI, DeepSeek, atau URL sendiri).
// Semua dipanggil langsung dari perangkat dengan API key milik pengguna.

/* eslint-disable @typescript-eslint/no-explicit-any */

export class AIError extends Error {
  code: string;
  partial?: string;
  constructor(code: string, message: string, partial?: string) {
    super(message);
    this.code = code;
    this.partial = partial;
  }
}

export interface OaiPreset {
  id: string;
  label: string;
  base: string;
  keyUrl: string;
  note: string;
}

export const OAI_PRESETS: OaiPreset[] = [
  { id: "openrouter", label: "OpenRouter", base: "https://openrouter.ai/api/v1", keyUrl: "https://openrouter.ai/keys", note: "Ada model gratis (berakhiran :free). Untuk membaca screenshot, pilih model bertanda 'bisa gambar'." },
  { id: "groq", label: "Groq", base: "https://api.groq.com/openai/v1", keyUrl: "https://console.groq.com/keys", note: "Punya kuota gratis dan sangat cepat. Hanya sebagian model yang bisa membaca gambar." },
  { id: "openai", label: "OpenAI", base: "https://api.openai.com/v1", keyUrl: "https://platform.openai.com/api-keys", note: "Berbayar." },
  { id: "deepseek", label: "DeepSeek", base: "https://api.deepseek.com/v1", keyUrl: "https://platform.deepseek.com/api_keys", note: "Murah, tetapi tidak bisa membaca gambar (hanya analisis teks)." },
  { id: "custom", label: "URL sendiri", base: "", keyUrl: "", note: "Server apa pun yang kompatibel dengan API OpenAI (/chat/completions)." },
];

export const GEMINI_KEY_URL = "https://aistudio.google.com/apikey";
const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

export interface ModelInfo {
  id: string;
  label: string;
  vision: boolean | null; // null = tidak diketahui
  free: boolean;
}

async function httpError(res: Response): Promise<AIError> {
  let msg = `HTTP ${res.status}`;
  try {
    const j = await res.json();
    msg = j?.error?.message ?? j?.message ?? msg;
  } catch {
    /* bukan JSON */
  }
  if (res.status === 401 || res.status === 403 || /API_KEY_INVALID|API key not valid|invalid.{0,20}key|incorrect api key/i.test(msg)) return new AIError("auth", msg);
  if (res.status === 404) return new AIError("model_not_found", msg);
  if (res.status === 429) return new AIError("rate_limited", msg);
  if (res.status === 402) return new AIError("no_credit", msg);
  if (res.status === 400 && /image|vision|multimodal|modalit/i.test(msg)) return new AIError("no_vision", msg);
  return new AIError("upstream_error", msg);
}

function netError(e: unknown): AIError {
  if (e instanceof AIError) return e;
  if ((e as any)?.name === "AbortError") return new AIError("cancelled", "Dibatalkan");
  return new AIError("network", (e as any)?.message ?? "Tidak bisa terhubung");
}

/** Baca aliran Server-Sent Events baris per baris. */
async function readSSE(res: Response, onData: (j: any) => void) {
  const reader = res.body!.getReader();
  const dec = new TextDecoder();
  let buf = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line.startsWith("data:")) continue;
      const payload = line.slice(5).trim();
      if (payload === "[DONE]") return;
      try {
        onData(JSON.parse(payload));
      } catch {
        /* potongan tidak lengkap */
      }
    }
  }
}

export interface GenOpts {
  prompt: string;
  images: string[]; // base64 JPEG
  json?: boolean;
  onText?: (text: string) => void;
  signal?: AbortSignal;
}

// ---------- Google Gemini ----------
export async function geminiGenerate(key: string, model: string, o: GenOpts): Promise<string> {
  const m = (model || "gemini-flash-latest").replace(/^models\//, "");
  const body = {
    contents: [{ role: "user", parts: [...o.images.map((data) => ({ inlineData: { mimeType: "image/jpeg", data } })), { text: o.prompt }] }],
    generationConfig: { temperature: o.json ? 0.1 : 0.5, maxOutputTokens: 32768, ...(o.json ? { responseMimeType: "application/json" } : {}) },
  };
  let text = "";
  let blocked = "";
  try {
    const res = await fetch(`${GEMINI_BASE}/models/${encodeURIComponent(m)}:streamGenerateContent?alt=sse&key=${encodeURIComponent(key)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: o.signal,
    });
    if (!res.ok) throw await httpError(res);
    await readSSE(res, (j) => {
      if (j?.promptFeedback?.blockReason) blocked = j.promptFeedback.blockReason;
      const parts = j?.candidates?.[0]?.content?.parts ?? [];
      const add = parts.filter((p: any) => !p.thought && typeof p.text === "string").map((p: any) => p.text).join("");
      if (add) {
        text += add;
        o.onText?.(text);
      }
    });
  } catch (e) {
    throw netError(e);
  }
  if (!text && blocked) throw new AIError("refused", `Diblokir: ${blocked}`);
  if (!text) throw new AIError("empty", "Balasan kosong");
  return text;
}

export async function listGeminiModels(key: string): Promise<ModelInfo[]> {
  try {
    const res = await fetch(`${GEMINI_BASE}/models?pageSize=200&key=${encodeURIComponent(key)}`);
    if (!res.ok) throw await httpError(res);
    const j = await res.json();
    return (j.models ?? [])
      .filter((x: any) => (x.supportedGenerationMethods ?? []).includes("generateContent") && /gemini/i.test(x.name))
      .map((x: any) => ({ id: String(x.name).replace(/^models\//, ""), label: x.displayName ?? x.name, vision: true, free: true }));
  } catch (e) {
    throw netError(e);
  }
}

// ---------- Kompatibel OpenAI ----------
function oaiHeaders(key: string, preset: string): Record<string, string> {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  if (key) h.Authorization = `Bearer ${key}`;
  if (preset === "openrouter") h["X-Title"] = "BolaMetrik";
  return h;
}

export async function oaiGenerate(base: string, key: string, model: string, preset: string, o: GenOpts): Promise<string> {
  const content = o.images.length
    ? [{ type: "text", text: o.prompt }, ...o.images.map((d) => ({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${d}` } }))]
    : o.prompt;
  let text = "";
  let streamErr = "";
  try {
    const res = await fetch(`${base.replace(/\/+$/, "")}/chat/completions`, {
      method: "POST",
      headers: oaiHeaders(key, preset),
      body: JSON.stringify({ model, stream: true, temperature: o.json ? 0.1 : 0.5, max_tokens: 8000, messages: [{ role: "user", content }] }),
      signal: o.signal,
    });
    if (!res.ok) throw await httpError(res);
    await readSSE(res, (j) => {
      if (j?.error) streamErr = j.error.message ?? String(j.error);
      const add = j?.choices?.[0]?.delta?.content;
      if (typeof add === "string" && add) {
        text += add;
        o.onText?.(text);
      }
    });
  } catch (e) {
    throw netError(e);
  }
  if (!text && streamErr) throw new AIError(/image|vision/i.test(streamErr) ? "no_vision" : "upstream_error", streamErr);
  if (!text) throw new AIError("empty", "Balasan kosong");
  return text;
}

export async function listOaiModels(base: string, key: string, preset: string): Promise<ModelInfo[]> {
  try {
    const res = await fetch(`${base.replace(/\/+$/, "")}/models`, { headers: oaiHeaders(key, preset) });
    if (!res.ok) throw await httpError(res);
    const j = await res.json();
    const list: ModelInfo[] = (j.data ?? [])
      // hanya model yang menghasilkan teks (bukan musik/gambar/embedding)
      .filter((x: any) => !x.architecture?.output_modalities || x.architecture.output_modalities.includes("text"))
      .filter((x: any) => !/embed|whisper|tts|dall-e|moderation|guard|safety/i.test(String(x.id)))
      .map((x: any) => {
        const mods: string[] | undefined = x.architecture?.input_modalities;
        return { id: String(x.id), label: x.name ?? x.id, vision: mods ? mods.includes("image") : null, free: String(x.id).endsWith(":free") };
      });
    // Model gratis & bisa gambar di atas
    return list.sort((a, b) => Number(b.free) - Number(a.free) || Number(b.vision === true) - Number(a.vision === true) || a.id.localeCompare(b.id));
  } catch (e) {
    throw netError(e);
  }
}
