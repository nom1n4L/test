import { useEffect, useState } from "react";
import { useApp } from "../state";
import { aiErrorText, aiStatus, type AIStatus, providerLabel, testAI } from "../lib/ai";
import { GEMINI_KEY_URL, listGeminiModels, listOaiModels, type ModelInfo, OAI_PRESETS } from "../lib/providers";
import { inArtifact } from "../lib/storage";
import type { AIProvider, Settings } from "../lib/types";
import { Panel, Seg, copyText } from "./ui";
import { ICheck, ICopy, ISpark } from "./icons";

/* eslint-disable @typescript-eslint/no-explicit-any */

const isNative = () => !!(window as any).Capacitor?.isNativePlatform?.();

function ExtLink({ href, children }: { href: string; children: string }) {
  const { toast } = useApp();
  // Di APK, link tanpa target dibuka Capacitor di browser sistem
  return (
    <span className="row" style={{ gap: 6, display: "inline-flex" }}>
      <a href={href} target={isNative() ? undefined : "_blank"} rel="noreferrer">{children}</a>
      <button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label={`Salin ${href}`} onClick={async () => toast((await copyText(href)) ? "Link disalin" : href)}><ICopy /></button>
    </span>
  );
}

type Draft = Pick<Settings, "aiProvider" | "geminiKey" | "geminiModel" | "oaiPreset" | "oaiBase" | "oaiKey" | "oaiModel" | "apiKey" | "aiModel">;

export function AIProviderPanel() {
  const { meta, updateSettings, toast } = useApp();
  const cfg = meta.settings;
  const artifact = inArtifact();
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [d, setD] = useState<Draft>({
    aiProvider: cfg.aiProvider,
    geminiKey: cfg.geminiKey,
    geminiModel: cfg.geminiModel,
    oaiPreset: cfg.oaiPreset,
    oaiBase: cfg.oaiBase,
    oaiKey: cfg.oaiKey,
    oaiModel: cfg.oaiModel,
    apiKey: cfg.apiKey,
    aiModel: cfg.aiModel,
  });
  const [show, setShow] = useState(false);
  const [models, setModels] = useState<ModelInfo[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [test, setTest] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    aiStatus(cfg).then(setStatus);
  }, [cfg]);

  const set = (patch: Partial<Draft>) => {
    setD((x) => ({ ...x, ...patch }));
    setTest(null);
  };
  const merged: Settings = { ...cfg, ...d };
  const dirty = (Object.keys(d) as (keyof Draft)[]).some((k) => d[k] !== cfg[k]);
  const preset = OAI_PRESETS.find((p) => p.id === d.oaiPreset) ?? OAI_PRESETS[0];

  if (artifact) {
    return (
      <Panel title="AI">
        <div className="stack-sm small">
          {!status ? <span className="row dim"><span className="spin" /> Memeriksa…</span> : status.sample ? (
            <p className="notice good">Aktif lewat claude.ai (memakai akun Claude Anda; izin diminta saat pertama dipakai). {status.images ? `Bisa membaca hingga ${status.maxImages} gambar per permintaan.` : "Tampilan ini tidak mendukung gambar."}</p>
          ) : (
            <p className="notice">AI tidak tersedia di tampilan ini.</p>
          )}
          <p className="dim">Pilihan Gemini, OpenRouter, Groq, dan penyedia lain tersedia di APK dan versi web mandiri.</p>
        </div>
      </Panel>
    );
  }

  function save() {
    updateSettings({
      ...d,
      geminiKey: d.geminiKey.trim(),
      geminiModel: d.geminiModel.trim() || "gemini-flash-latest",
      oaiBase: d.oaiBase.trim(),
      oaiKey: d.oaiKey.trim(),
      oaiModel: d.oaiModel.trim(),
      apiKey: d.apiKey.trim(),
    });
    toast("Pengaturan AI disimpan");
  }

  async function loadModels() {
    setBusy("Memuat daftar model…");
    try {
      const list = d.aiProvider === "gemini" ? await listGeminiModels(d.geminiKey.trim()) : await listOaiModels(d.oaiBase.trim(), d.oaiKey.trim(), d.oaiPreset);
      setModels(list);
      if (!list.length) toast("Tidak ada model yang ditemukan.", true);
    } catch (e) {
      toast(aiErrorText(e), true);
    } finally {
      setBusy(null);
    }
  }

  async function runTest() {
    setBusy("Menguji koneksi…");
    setTest(null);
    try {
      const t = await testAI(merged);
      setTest({ ok: true, text: `Terhubung — balasan: "${t}"` });
    } catch (e) {
      setTest({ ok: false, text: aiErrorText(e) });
    } finally {
      setBusy(null);
    }
  }

  const modelPicker = (value: string, onPick: (id: string) => void) =>
    models && models.length > 0 ? (
      <select className="input" aria-label="Pilih model dari daftar" value={models.some((m) => m.id === value) ? value : ""} onChange={(e) => e.target.value && onPick(e.target.value)}>
        <option value="">Pilih dari {models.length} model…</option>
        {models.map((m) => (
          <option key={m.id} value={m.id}>
            {m.id}{m.free && d.aiProvider !== "gemini" ? " · gratis" : ""}{m.vision === true ? " · bisa gambar" : m.vision === false ? " · teks saja" : ""}
          </option>
        ))}
      </select>
    ) : null;

  return (
    <Panel title="Penyedia AI" sub="untuk membaca screenshot & analisis naratif — prediksi statistik tetap jalan tanpa AI">
      <div className="stack">
        <Seg<AIProvider>
          label="Penyedia AI"
          value={d.aiProvider}
          onChange={(v) => {
            set({ aiProvider: v });
            setModels(null);
          }}
          options={[
            { v: "gemini", label: "Google Gemini · gratis" },
            { v: "openai", label: "OpenRouter / Groq / lainnya" },
            { v: "claude", label: "Claude (berbayar)" },
          ]}
        />

        {d.aiProvider === "gemini" && (
          <div className="stack-sm small">
            <ol className="dim" style={{ margin: 0, paddingLeft: 18 }}>
              <li>Buka <ExtLink href={GEMINI_KEY_URL}>aistudio.google.com/apikey</ExtLink> dan masuk dengan akun Google.</li>
              <li>Tekan <b>Create API key</b>, lalu salin key-nya.</li>
              <li>Tempel di bawah, tekan <b>Simpan</b>, lalu <b>Tes koneksi</b>.</li>
            </ol>
            <div className="field">
              <label htmlFor="gem-key">API key Gemini</label>
              <input id="gem-key" className="input num" type={show ? "text" : "password"} autoComplete="off" placeholder="AIza..." value={d.geminiKey} onChange={(e) => set({ geminiKey: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="gem-model">Model</label>
              <div className="row">
                <input id="gem-model" className="input num grow" value={d.geminiModel} placeholder="gemini-flash-latest" onChange={(e) => set({ geminiModel: e.target.value })} />
                <button className="btn btn-sm" type="button" disabled={!d.geminiKey.trim() || !!busy} onClick={loadModels}>Muat daftar model</button>
              </div>
              <div className="row" style={{ gap: 6 }}>
                {["gemini-flash-latest", "gemini-flash-lite-latest"].map((mm) => (
                  <button key={mm} type="button" className={`btn btn-sm${d.geminiModel === mm ? " btn-primary" : ""}`} onClick={() => set({ geminiModel: mm })}>{mm}</button>
                ))}
              </div>
              {modelPicker(d.geminiModel, (id) => set({ geminiModel: id }))}
              <span className="hint">"gemini-flash-latest" selalu mengarah ke model Flash terbaru (cepat, bisa membaca gambar). Sering kena batas kuota? Pakai "gemini-flash-lite-latest" — kuota gratisnya lebih longgar. Model "pro" lebih pintar tetapi kuota gratisnya paling kecil.</span>
            </div>
            <p className="muted tiny">Kuota gratis Gemini dibatasi per menit dan per hari. Pada tingkat gratis, Google dapat memakai isi permintaan untuk meningkatkan layanannya — jangan kirim data pribadi.</p>
          </div>
        )}

        {d.aiProvider === "openai" && (
          <div className="stack-sm small">
            <div className="field">
              <label htmlFor="oai-preset">Layanan</label>
              <select id="oai-preset" className="input" value={d.oaiPreset} onChange={(e) => {
                const p = OAI_PRESETS.find((x) => x.id === e.target.value)!;
                set({ oaiPreset: p.id, oaiBase: p.base || d.oaiBase, oaiModel: "" });
                setModels(null);
              }}>
                {OAI_PRESETS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
              </select>
              <span className="hint">{preset.note}</span>
            </div>
            {preset.keyUrl && <p className="dim">Buat API key di <ExtLink href={preset.keyUrl}>{preset.keyUrl.replace("https://", "")}</ExtLink></p>}
            <div className="field">
              <label htmlFor="oai-base">URL API</label>
              <input id="oai-base" className="input num" value={d.oaiBase} disabled={d.oaiPreset !== "custom"} placeholder="https://…/v1" onChange={(e) => set({ oaiBase: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="oai-key">API key</label>
              <input id="oai-key" className="input num" type={show ? "text" : "password"} autoComplete="off" placeholder={d.oaiPreset === "custom" ? "opsional" : "sk-…"} value={d.oaiKey} onChange={(e) => set({ oaiKey: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="oai-model">Model</label>
              <div className="row">
                <input id="oai-model" className="input num grow" value={d.oaiModel} placeholder="nama model" onChange={(e) => set({ oaiModel: e.target.value })} />
                <button className="btn btn-sm" type="button" disabled={!d.oaiBase.trim() || !!busy} onClick={loadModels}>Muat daftar model</button>
              </div>
              {modelPicker(d.oaiModel, (id) => set({ oaiModel: id }))}
              {d.oaiPreset === "openrouter" && <span className="hint">Model gratis ada di urutan atas daftar. Untuk membaca screenshot pilih yang bertanda "bisa gambar".</span>}
            </div>
          </div>
        )}

        {d.aiProvider === "claude" && (
          <div className="stack-sm small">
            <p className="dim">API key Anthropic dari <ExtLink href="https://console.anthropic.com/settings/keys">console.anthropic.com</ExtLink>. Pemakaian ditagih ke akun Anthropic Anda.</p>
            <div className="field">
              <label htmlFor="cl-key">API key Anthropic</label>
              <input id="cl-key" className="input num" type={show ? "text" : "password"} autoComplete="off" placeholder="sk-ant-..." value={d.apiKey} onChange={(e) => set({ apiKey: e.target.value })} />
            </div>
            <div className="field">
              <span className="lbl">Model</span>
              <Seg<string> label="Model Claude" value={d.aiModel} onChange={(v) => set({ aiModel: v })} options={[{ v: "claude-opus-5-5", label: "Claude Opus 5.5 (terbaik)" }, { v: "claude-sonnet-5-5", label: "Claude Sonnet 5.5 (lebih murah)" }]} />
            </div>
          </div>
        )}

        <label className="check small"><input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> Tampilkan API key</label>
        <div className="row">
          <button className="btn btn-primary" type="button" disabled={!dirty} onClick={save}><ICheck /> Simpan</button>
          <button className="btn" type="button" disabled={!!busy} onClick={runTest}><ISpark /> Tes koneksi</button>
          {busy && <span className="row small dim"><span className="spin" /> {busy}</span>}
        </div>
        {test && <p className={`notice small ${test.ok ? "good" : "bad"}`}>{test.text}</p>}
        <p className="muted tiny">
          Aktif sekarang: <b>{status ? (status.api ? status.label : "belum ada") : "…"}</b>{dirty ? " · ada perubahan yang belum disimpan" : ""}. API key hanya disimpan di perangkat ini, tidak ikut cadangan, dan dikirim langsung ke penyedia yang dipilih ({providerLabel(merged).split(" (")[0]}).
        </p>
      </div>
    </Panel>
  );
}
