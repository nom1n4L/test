import { useEffect, useRef, useState } from "react";
import { useApp } from "../state";
import { aiErrorText, aiStatus, type AIStatus, type Change, diffExtraction, extractFromImages } from "../lib/ai";
import { ocrImages, parseOcr } from "../lib/ocr";
import { inArtifact } from "../lib/storage";
import { ICheck, ISpark, IUpload, IX } from "./icons";

/** Unggah screenshot → AI (Claude) atau OCR membaca data → pengguna meninjau → diterapkan ke formulir. */
export function UploadPanel() {
  const { attachments, addFiles, removeAttachment, draft, setDraft, meta, toast, go } = useApp();
  const [over, setOver] = useState(false);
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [changes, setChanges] = useState<Change[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [unclear, setUnclear] = useState<string[]>([]);
  const [ocrText, setOcrText] = useState<string | null>(null);
  const [ocrHits, setOcrHits] = useState<string[]>([]);
  const ctl = useRef<AbortController | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let alive = true;
    aiStatus(meta.settings.apiKey).then((s) => alive && setStatus(s));
    return () => {
      alive = false;
    };
  }, [meta.settings.apiKey]);

  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const files = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith("image/"));
      if (files.length) {
        addFiles(files);
        toast(`${files.length} gambar ditempel`);
      }
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }, [addFiles, toast]);

  const aiReady = !!status && (status.sample ? status.images : status.api);

  async function runAI() {
    if (!attachments.length) return;
    ctl.current = new AbortController();
    setBusy("Claude sedang membaca screenshot…");
    setChanges(null);
    setOcrText(null);
    try {
      const ext = await extractFromImages(
        attachments.map((a) => a.file),
        draft,
        { apiKey: meta.settings.apiKey, model: meta.settings.aiModel, maxImages: status?.maxImages ?? 5, signal: ctl.current.signal, onProgress: setBusy },
      );
      const ch = diffExtraction(draft, ext);
      setChanges(ch);
      setPicked(new Set(ch.map((c) => c.id)));
      setUnclear(ext.unclear ?? []);
      if (!ch.length) toast("Tidak ada data baru yang terbaca dari gambar.");
    } catch (e) {
      toast(aiErrorText(e), true);
    } finally {
      setBusy(null);
    }
  }

  async function runOCR() {
    if (!attachments.length) return;
    setBusy("Memuat mesin OCR…");
    setChanges(null);
    try {
      const texts = await ocrImages(attachments.map((a) => a.file), setBusy);
      const { ext, hits } = parseOcr(texts);
      setOcrText(texts.join("\n\n———\n\n"));
      setOcrHits(hits);
      const ch = diffExtraction(draft, ext);
      setChanges(ch);
      setPicked(new Set());
      setUnclear(ext.unclear ?? []);
    } catch (e) {
      toast((e as Error).message || "OCR gagal", true);
    } finally {
      setBusy(null);
    }
  }

  function apply() {
    if (!changes) return;
    const next = JSON.parse(JSON.stringify(draft));
    for (const c of changes) if (picked.has(c.id)) c.apply(next);
    setDraft(next);
    toast(`${picked.size} data diterapkan ke formulir`);
    setChanges(null);
  }

  return (
    <section className="panel">
      <div className="panel-head">
        <div className="panel-title">
          <h3>Unggah screenshot data</h3>
          <span className="muted small">klasemen, statistik tim, form, H2H, pemain, cedera, odds, wasit</span>
        </div>
        {status && (
          <span className={`chip ${aiReady ? "good" : "warn"}`}>{aiReady ? (status.sample ? "AI Claude aktif" : "AI via API key") : "AI belum aktif"}</span>
        )}
      </div>
      <div
        className={`drop${over ? " over" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setOver(false);
          addFiles([...e.dataTransfer.files]);
        }}
      >
        <IUpload width={30} height={30} style={{ color: "var(--flood)" }} />
        <p style={{ marginTop: 6, fontWeight: 600 }}>Tarik & lepas gambar di sini, tempel (Ctrl/Cmd+V), atau</p>
        <div className="row" style={{ justifyContent: "center", marginTop: 10 }}>
          <button className="btn btn-primary" type="button" onClick={() => fileRef.current?.click()}>
            <IUpload /> Pilih gambar
          </button>
        </div>
        <input ref={fileRef} type="file" accept="image/*" multiple hidden onChange={(e) => {
          addFiles([...(e.target.files ?? [])]);
          e.target.value = "";
        }} />
        <p className="muted small" style={{ marginTop: 10 }}>Semua gambar dianggap satu pertandingan. Makin lengkap datanya, makin tinggi akurasi & keyakinan.</p>
      </div>

      {attachments.length > 0 && (
        <>
          <div className="thumbs">
            {attachments.map((a) => (
              <div className="thumb" key={a.id}>
                <img src={a.url} alt={a.file.name} />
                <button type="button" aria-label={`Hapus ${a.file.name}`} onClick={() => removeAttachment(a.id)}><IX width={14} height={14} /></button>
              </div>
            ))}
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <button className="btn btn-primary" type="button" disabled={!!busy || !aiReady} onClick={runAI}>
              <ISpark /> Baca dengan AI Claude
            </button>
            {!inArtifact() && (
              <button className="btn" type="button" disabled={!!busy} onClick={runOCR}>
                Baca dengan OCR (dasar)
              </button>
            )}
            {busy && (
              <span className="row small dim">
                <span className="spin" /> {busy}
                <button className="btn btn-sm btn-ghost" type="button" onClick={() => ctl.current?.abort()}>Batal</button>
              </span>
            )}
          </div>
          {!aiReady && status && (
            <p className="notice small" style={{ marginTop: 10 }}>
              {inArtifact() ? "AI Claude tidak tersedia di tampilan ini. Isi data secara manual dari screenshot." : (
                <>AI membaca screenshot jauh lebih akurat daripada OCR. Aktifkan dengan API key Anthropic di <button className="btn btn-sm btn-ghost" type="button" onClick={() => go({ page: "settings" })}>Pengaturan</button>, atau buka aplikasi sebagai Artifact di claude.ai.</>
              )}
            </p>
          )}
        </>
      )}

      {changes && (
        <div className="stack" style={{ marginTop: 16 }}>
          <div className="row-between">
            <h3>Tinjau data yang terbaca ({changes.length})</h3>
            <div className="row">
              <button className="btn btn-sm btn-ghost" type="button" onClick={() => setPicked(new Set(changes.map((c) => c.id)))}>Pilih semua</button>
              <button className="btn btn-sm btn-ghost" type="button" onClick={() => setPicked(new Set())}>Kosongkan</button>
            </div>
          </div>
          {ocrText !== null && <p className="notice small">OCR hanya menebak pola "label angka angka" (kolom kiri = tuan rumah). Angka bisa berupa total atau rata-rata — centang hanya yang sudah Anda cek.</p>}
          {changes.length > 0 ? (
            <div className="table-wrap">
              <table className="t">
                <thead>
                  <tr><th></th><th>Data</th><th>Sekarang</th><th>Dari gambar</th></tr>
                </thead>
                <tbody>
                  {changes.map((c) => (
                    <tr key={c.id}>
                      <td><input type="checkbox" aria-label={`Terapkan ${c.label}`} checked={picked.has(c.id)} onChange={(e) => {
                        const n = new Set(picked);
                        if (e.target.checked) n.add(c.id);
                        else n.delete(c.id);
                        setPicked(n);
                      }} /></td>
                      <td>{c.label}</td>
                      <td className="num muted">{c.from}</td>
                      <td className="num">{c.to}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="muted">Tidak ada data yang dikenali.</p>
          )}
          {unclear.length > 0 && (
            <div className="notice bad small"><b>Tidak terbaca / tidak jelas:</b> {unclear.join(" · ")}</div>
          )}
          <div className="row">
            <button className="btn btn-primary" type="button" disabled={!picked.size} onClick={apply}><ICheck /> Terapkan {picked.size} data</button>
            <button className="btn btn-ghost" type="button" onClick={() => setChanges(null)}>Tutup</button>
          </div>
          {ocrText !== null && (
            <details className="fold">
              <summary><b>Teks mentah OCR</b><span className="muted small">{ocrHits.length} pola dikenali</span></summary>
              <div className="fold-body">
                <pre className="small" style={{ whiteSpace: "pre-wrap", maxHeight: 280, overflow: "auto", background: "var(--night)", padding: 12, borderRadius: 10 }}>{ocrText}</pre>
              </div>
            </details>
          )}
        </div>
      )}
    </section>
  );
}
