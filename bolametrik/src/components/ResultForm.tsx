import { useState } from "react";
import type { MatchRecord, MatchResult } from "../lib/types";
import type { Prediction } from "../lib/predict";
import { Modal, Seg } from "./ui";

type Tri = "ya" | "tidak" | "?";

/** Formulir hasil nyata — sumber pembelajaran model. */
export function ResultForm({ rec, pred, onSave, onClose, onClear }: { rec: MatchRecord; pred: Prediction; onSave: (r: MatchResult) => void; onClose: () => void; onClear?: () => void }) {
  const r = rec.result;
  const [ftH, setFtH] = useState(r ? String(r.ftH) : "");
  const [ftA, setFtA] = useState(r ? String(r.ftA) : "");
  const [htH, setHtH] = useState(r?.htH != null ? String(r.htH) : "");
  const [htA, setHtA] = useState(r?.htA != null ? String(r.htA) : "");
  const [corners, setCorners] = useState(r?.corners != null ? String(r.corners) : "");
  const [cards, setCards] = useState(r?.cards != null ? String(r.cards) : "");
  const [pen, setPen] = useState<Tri>(r?.pen == null ? "?" : r.pen ? "ya" : "tidak");
  const [red, setRed] = useState<Tri>(r?.red == null ? "?" : r.red ? "ya" : "tidak");
  const known = [...pred.scorers.home, ...pred.scorers.away].map((s) => s.name);
  const [picked, setPicked] = useState<Set<string>>(new Set((r?.scorers ?? []).filter((s) => known.includes(s))));
  const [extra, setExtra] = useState((r?.scorers ?? []).filter((s) => !known.includes(s)).join(", "));
  const n = (s: string) => (s.trim() === "" ? null : Math.max(0, Math.round(parseFloat(s))));
  const valid = n(ftH) !== null && n(ftA) !== null;
  const tri = (t: Tri) => (t === "?" ? null : t === "ya");
  const H = rec.input.home.name, A = rec.input.away.name;

  function save() {
    if (!valid) return;
    const scorers = [...picked, ...extra.split(/[,;\n]/).map((s) => s.trim()).filter(Boolean)];
    onSave({ ftH: n(ftH)!, ftA: n(ftA)!, htH: n(htH), htA: n(htA), corners: n(corners), cards: n(cards), pen: tri(pen), red: tri(red), scorers, settledAt: new Date().toISOString() });
  }

  const box = (id: string, label: string, v: string, set: (s: string) => void) => (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} className="input num" inputMode="numeric" value={v} onChange={(e) => set(e.target.value.replace(/[^0-9]/g, ""))} />
    </div>
  );

  return (
    <Modal onClose={onClose} label="Input hasil pertandingan">
      <div className="stack">
        <div>
          <div className="eyebrow">Hasil nyata</div>
          <h2>{H} vs {A}</h2>
          <p className="muted small">Setiap hasil yang Anda input membuat model mengevaluasi kesalahannya dan menyesuaikan parameter untuk prediksi berikutnya.</p>
        </div>
        <div className="grid-2">
          <div className="stack-sm">
            <span className="upper muted">Skor akhir (wajib)</span>
            <div className="row">{box("ftH", H, ftH, setFtH)}{box("ftA", A, ftA, setFtA)}</div>
          </div>
          <div className="stack-sm">
            <span className="upper muted">Skor babak 1</span>
            <div className="row">{box("htH", H, htH, setHtH)}{box("htA", A, htA, setHtA)}</div>
          </div>
        </div>
        <div className="grid-2">
          {box("corners", "Total corner", corners, setCorners)}
          {box("cards", "Total kartu (kuning + merah)", cards, setCards)}
        </div>
        <div className="row" style={{ gap: 18 }}>
          <div className="stack-sm"><span className="upper muted">Ada penalti?</span><Seg<Tri> label="Ada penalti" value={pen} onChange={setPen} options={[{ v: "ya", label: "Ya" }, { v: "tidak", label: "Tidak" }, { v: "?", label: "Tidak tahu" }]} /></div>
          <div className="stack-sm"><span className="upper muted">Ada kartu merah?</span><Seg<Tri> label="Ada kartu merah" value={red} onChange={setRed} options={[{ v: "ya", label: "Ya" }, { v: "tidak", label: "Tidak" }, { v: "?", label: "Tidak tahu" }]} /></div>
        </div>
        <div className="stack-sm">
          <span className="upper muted">Pencetak gol</span>
          {known.length > 0 && (
            <div className="row">
              {known.map((name) => (
                <label key={name} className="check small">
                  <input type="checkbox" checked={picked.has(name)} onChange={(e) => {
                    const s = new Set(picked);
                    if (e.target.checked) s.add(name);
                    else s.delete(name);
                    setPicked(s);
                  }} /> {name}
                </label>
              ))}
            </div>
          )}
          <input className="input" aria-label="Pencetak gol lain" placeholder="Pencetak gol lain, pisahkan dengan koma" value={extra} onChange={(e) => setExtra(e.target.value)} />
        </div>
        <div className="row-between">
          {onClear && r ? <button className="btn btn-ghost btn-danger" type="button" onClick={onClear}>Hapus hasil</button> : <span />}
          <div className="row">
            <button className="btn btn-ghost" type="button" onClick={onClose}>Batal</button>
            <button className="btn btn-primary" type="button" disabled={!valid} onClick={save}>Simpan & pelajari</button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
