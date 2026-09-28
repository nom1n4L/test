import { useEffect, useRef, useState } from "react";
import { useApp } from "../state";
import { aiStatus, type AIStatus } from "../lib/ai";
import { inArtifact } from "../lib/storage";
import type { AppMeta, MatchRecord } from "../lib/types";
import { Panel, Seg, copyText } from "../components/ui";
import { ICloud, ICopy, IDownload, IUpload } from "../components/icons";

/* eslint-disable @typescript-eslint/no-explicit-any */

async function offerDownload(filename: string, data: string): Promise<boolean> {
  const c = (window as any).claude;
  if (c?.use) {
    try {
      const dl = await c.use("downloads");
      if (dl) {
        await dl.save({ filename, data });
        return true;
      }
    } catch {
      return false;
    }
  }
  try {
    const url = URL.createObjectURL(new Blob([data], { type: "application/json" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
    return true;
  } catch {
    return false;
  }
}

export function Settings() {
  const { meta, updateSettings, storeKind, records, importData, wipeAll, toast, stats } = useApp();
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [key, setKey] = useState(meta.settings.apiKey);
  const [paste, setPaste] = useState("");
  const [confirmWipe, setConfirmWipe] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const artifact = inArtifact();
  const nativeApp = !!(window as any).Capacitor?.isNativePlatform?.();

  useEffect(() => {
    aiStatus(meta.settings.apiKey).then(setStatus);
  }, [meta.settings.apiKey]);

  const real = records.filter((r) => !r.demo);
  const backup = () => JSON.stringify({ app: "bolametrik", version: 1, exportedAt: new Date().toISOString(), records: real, meta: { ...meta, settings: { ...meta.settings, apiKey: "" } } }, null, 1);

  async function doImport(text: string) {
    try {
      const j = JSON.parse(text) as { records?: MatchRecord[]; meta?: AppMeta };
      if (!Array.isArray(j.records)) throw new Error("format");
      const n = await importData(j.records, j.meta);
      toast(`${n} prediksi diimpor — pembelajaran dihitung ulang`);
      setPaste("");
    } catch {
      toast("File cadangan tidak dikenali. Pastikan berasal dari BolaMetrik.", true);
    }
  }

  return (
    <div className="page stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">Pengaturan</div>
          <h1>Data, AI & cadangan</h1>
        </div>
      </div>

      <div className="grid-2">
        <Panel title="Penyimpanan">
          <div className="stack-sm small">
            <div className="row"><ICloud width={20} height={20} /><b>{storeKind === "cloud" ? "Cloud claude.ai — tersinkron antar perangkat" : storeKind === "local" ? "Perangkat ini (IndexedDB browser)" : "Sementara (hilang saat ditutup)"}</b></div>
            <p className="dim">{real.length} prediksi · {stats.settled} sudah ada hasilnya.</p>
            {storeKind !== "cloud" && <p className="dim">Data tersimpan di browser ini saja. Buat cadangan berkala agar riwayat pembelajaran tidak hilang saat data browser dibersihkan.</p>}
          </div>
        </Panel>
        <Panel title="AI Claude">
          <div className="stack-sm small">
            {!status ? <span className="row dim"><span className="spin" /> Memeriksa…</span> : status.sample ? (
              <p className="notice good">Aktif lewat claude.ai (memakai akun Claude Anda; izin diminta saat pertama dipakai). {status.images ? `Bisa membaca hingga ${status.maxImages} gambar per permintaan.` : "Tampilan ini tidak mendukung gambar."}</p>
            ) : artifact ? (
              <p className="notice">AI tidak tersedia di tampilan ini.</p>
            ) : (
              <>
                <p className="dim">Untuk aplikasi mandiri / APK: masukkan API key Anthropic Anda (console.anthropic.com). Key hanya disimpan di perangkat ini dan dikirim langsung ke api.anthropic.com.</p>
                <div className="row">
                  <input className="input num grow" type="password" autoComplete="off" aria-label="API key Anthropic" placeholder="sk-ant-..." value={key} onChange={(e) => setKey(e.target.value)} />
                  <button className="btn btn-primary" type="button" onClick={() => { updateSettings({ apiKey: key.trim() }); toast(key.trim() ? "API key disimpan" : "API key dihapus"); }}>Simpan</button>
                </div>
                <div className="field">
                  <span className="lbl">Model</span>
                  <Seg<string> label="Model AI" value={meta.settings.aiModel} onChange={(v) => updateSettings({ aiModel: v })} options={[{ v: "claude-opus-5-5", label: "Claude Opus 5.5 (terbaik)" }, { v: "claude-sonnet-5-5", label: "Claude Sonnet 5.5 (lebih murah)" }]} />
                </div>
                <p className="muted tiny">Biaya pemakaian API ditagih ke akun Anthropic Anda.</p>
              </>
            )}
          </div>
        </Panel>
      </div>

      <Panel title="Cadangan data" sub="ekspor / impor riwayat & pembelajaran">
        <div className="row">
          {!nativeApp && <button className="btn" type="button" disabled={!real.length} onClick={async () => { const ok = await offerDownload(`bolametrik-cadangan-${new Date().toISOString().slice(0, 10)}.json`, backup()); if (!ok) toast("Unduhan tidak didukung di sini — pakai Salin JSON.", true); }}><IDownload /> Unduh cadangan</button>}
          <button className="btn" type="button" disabled={!real.length} onClick={async () => toast((await copyText(backup())) ? "JSON cadangan disalin" : "Tidak bisa menyalin di tampilan ini", false)}><ICopy /> Salin JSON</button>
          <button className="btn" type="button" onClick={() => fileRef.current?.click()}><IUpload /> Impor file</button>
          <input ref={fileRef} type="file" accept="application/json,.json" hidden onChange={async (e) => {
            const f = e.target.files?.[0];
            if (f) await doImport(await f.text());
            e.target.value = "";
          }} />
        </div>
        {nativeApp && <p className="muted small" style={{ marginTop: 8 }}>Di aplikasi Android: tekan Salin JSON lalu simpan teksnya (mis. ke catatan atau WhatsApp ke diri sendiri).</p>}
        <div className="stack-sm" style={{ marginTop: 12 }}>
          <textarea className="input" aria-label="Tempel JSON cadangan" placeholder="…atau tempel JSON cadangan di sini" value={paste} onChange={(e) => setPaste(e.target.value)} />
          <div><button className="btn btn-sm" type="button" disabled={!paste.trim()} onClick={() => doImport(paste)}>Impor dari teks</button></div>
        </div>
      </Panel>

      <Panel title="Hapus semua data">
        <p className="small dim">Menghapus semua prediksi, hasil, tim tersimpan, dan seluruh pembelajaran. Tidak bisa dibatalkan — unduh cadangan dulu.</p>
        <div className="row" style={{ marginTop: 10 }}>
          {confirmWipe ? (
            <>
              <button className="btn btn-danger" type="button" onClick={async () => { await wipeAll(); setConfirmWipe(false); toast("Semua data dihapus"); }}>Ya, hapus semuanya</button>
              <button className="btn btn-ghost" type="button" onClick={() => setConfirmWipe(false)}>Batal</button>
            </>
          ) : (
            <button className="btn btn-danger" type="button" disabled={!real.length} onClick={() => setConfirmWipe(true)}>Hapus semua data…</button>
          )}
        </div>
      </Panel>

      <Panel title="Tentang BolaMetrik">
        <div className="stack-sm small dim">
          <p>Model: Poisson bivariat dengan koreksi Dixon-Coles, penggabungan berbobot dari 9 sumber data, Negative Binomial untuk corner & kartu, dan pembelajaran online (Hedge + gradien log-likelihood) dari hasil nyata.</p>
          <p>Tidak ada model yang bisa menjamin hasil pertandingan. Aplikasi ini membantu menilai peluang secara disiplin, memperlihatkan bukti yang berlawanan, dan mengukur akurasinya sendiri secara jujur.</p>
          <p>Taruhan berisiko dan dibatasi hukum di banyak wilayah, termasuk Indonesia. Patuhi hukum setempat dan jangan bertaruh dengan uang yang tidak siap hilang.</p>
        </div>
      </Panel>
    </div>
  );
}
