import { useMemo, useState } from "react";
import { useApp } from "../state";
import { predict, type Prediction } from "../lib/predict";
import { evaluate } from "../lib/learning";
import type { MatchRecord } from "../lib/types";
import { ResultForm } from "../components/ResultForm";
import { ConfChip, FormLetters, Tabs, copyText, fmtDate, pct } from "../components/ui";
import { IBack, ICopy, IEdit, IFlag, ITrash } from "../components/icons";
import { AITab, MarketsTab, ModelTab, PlayersTab, ScoresTab, SetPiecesTab, SimTab, SummaryTab } from "./ResultTabs";

type Tab = "ringkasan" | "skor" | "pasaran" | "pemain" | "setpiece" | "simulasi" | "ai" | "model";

function summaryText(rec: MatchRecord, p: Prediction): string {
  const H = rec.input.home.name, A = rec.input.away.name;
  const lines = [
    `⚽ ${H} vs ${A} — ${rec.input.leagueName}`,
    `Prediksi BolaMetrik (keyakinan ${p.confidence.score}/10)`,
    "",
    ...p.quick.map((q) => `• ${q.label}: ${q.answer}${q.p !== null ? ` (${pct(q.p)})` : ""}`),
    "",
    "Rekomendasi:",
    ...p.recs.filter((r) => r.tier === "aman" || r.tier === "utama" || r.tier === "value").map((r) => `• [${r.tier}] ${r.label} — ${pct(r.p)}`),
    "",
    "Peluang bukan kepastian. Main bertanggung jawab.",
  ];
  return lines.join("\n");
}

export function Result({ id }: { id: string }) {
  const { records, go, saveRecord, deleteRecord, toast, setDraft } = useApp();
  const rec = records.find((r) => r.id === id);
  const [tab, setTab] = useState<Tab>("ringkasan");
  const [showForm, setShowForm] = useState(false);
  const [confirmDel, setConfirmDel] = useState(false);
  const pred = useMemo(() => (rec ? predict(rec.input, rec.snapshot, rec.ai) : null), [rec]);
  const ev = useMemo(() => (rec?.result && pred ? evaluate(pred, rec, rec.result) : null), [rec, pred]);

  if (!rec || !pred) {
    return (
      <div className="page empty">
        <h3>Prediksi tidak ditemukan</h3>
        <p>Mungkin sudah dihapus.</p>
        <button className="btn" type="button" onClick={() => go({ page: "history" })}>Ke riwayat</button>
      </div>
    );
  }
  const H = rec.input.home.name, A = rec.input.away.name;
  const sc = pred.scenario;

  return (
    <div className="page">
      <div className="row-between" style={{ marginBottom: 12 }}>
        <button className="btn btn-ghost btn-sm" type="button" onClick={() => go({ page: "history" })}><IBack /> Riwayat</button>
        <div className="row">
          {rec.demo ? (
            <button className="btn btn-sm" type="button" onClick={() => { setDraft(JSON.parse(JSON.stringify(rec.input))); go({ page: "analyze" }); }}><IEdit /> Pakai sebagai templat</button>
          ) : (
            <>
              <button className="btn btn-primary btn-sm" type="button" onClick={() => setShowForm(true)}><IFlag /> {rec.result ? "Edit hasil" : "Input hasil"}</button>
              <button className="btn btn-sm" type="button" onClick={() => go({ page: "analyze", editId: rec.id })}><IEdit /> Edit data</button>
            </>
          )}
          <button className="btn btn-sm" type="button" onClick={async () => toast((await copyText(summaryText(rec, pred))) ? "Ringkasan disalin — siap ditempel ke WhatsApp/Telegram" : "Tidak bisa menyalin di tampilan ini", false)}><ICopy /> Salin</button>
          {!rec.demo && (confirmDel ? (
            <span className="row">
              <button className="btn btn-sm btn-danger" type="button" onClick={async () => { await deleteRecord(rec.id); toast("Prediksi dihapus"); go({ page: "history" }); }}>Ya, hapus</button>
              <button className="btn btn-sm btn-ghost" type="button" onClick={() => setConfirmDel(false)}>Batal</button>
            </span>
          ) : (
            <button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label="Hapus prediksi" onClick={() => setConfirmDel(true)}><ITrash /></button>
          ))}
        </div>
      </div>

      {rec.demo && <p className="notice small" style={{ marginBottom: 12 }}>Ini contoh dengan tim fiktif (Garuda FC vs Rajawali United) untuk memperlihatkan cara kerja aplikasi. Angkanya ilustrasi, bukan data asli.</p>}

      <section className="scoreboard" aria-label="Papan skor prediksi">
        <div className="sb-meta">
          <span>{rec.input.leagueName} · {fmtDate(rec.input.kickoff)}{rec.input.derby ? " · DERBY" : ""}{rec.input.neutral ? " · netral" : ""}</span>
          <span className="row" style={{ gap: 8 }}>
            {rec.result ? <span className={`chip ${ev?.outcomeHit ? "good" : "bad"}`}>Hasil {rec.result.ftH}-{rec.result.ftA} · {ev?.outcomeHit ? "arah benar" : "arah salah"}</span> : <span className="chip flood">Menunggu hasil</span>}
            <ConfChip c={pred.confidence.label} />
          </span>
        </div>
        <div className="sb-main">
          <div className="sb-team home">
            <span className="upper" style={{ color: "var(--home)" }}>Tuan rumah</span>
            <span className="sb-name">{H}</span>
            <FormLetters form={rec.input.home.form} />
          </div>
          <div className="sb-score" aria-label={`Prediksi skor ${sc.ft[0]}-${sc.ft[1]}`}>
            <span className="led">{sc.ft[0]}</span><span className="led-sep">:</span><span className="led">{sc.ft[1]}</span>
          </div>
          <div className="sb-team away">
            <span className="upper" style={{ color: "var(--away)" }}>Tim tamu</span>
            <span className="sb-name">{A}</span>
            <FormLetters form={rec.input.away.form} />
          </div>
        </div>
        <div className="sb-sub">
          <span>Babak 1 <b>{sc.ht[0]}-{sc.ht[1]}</b></span>
          <span>Babak 2 <b>{sc.h2[0]}-{sc.h2[1]}</b></span>
          <span>xG model <b>{pred.lam.lh.toFixed(2)} – {pred.lam.la.toFixed(2)}</b></span>
          <span>1X2 <b>{pct(pred.mk.x12.home)} · {pct(pred.mk.x12.draw)} · {pct(pred.mk.x12.away)}</b></span>
          <span>Keyakinan <b>{pred.confidence.score.toFixed(1)}/10</b></span>
        </div>
      </section>

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { v: "ringkasan", label: "Ringkasan" },
          { v: "skor", label: "Skor & Babak" },
          { v: "pasaran", label: "Pasaran & Handicap" },
          { v: "pemain", label: "Pencetak Gol" },
          { v: "setpiece", label: "Corner, Kartu, Penalti" },
          { v: "simulasi", label: "Simulasi" },
          { v: "ai", label: rec.ai ? "Analisis AI ✓" : "Analisis AI" },
          { v: "model", label: "Data & Model" },
        ]}
      />

      {tab === "ringkasan" && <SummaryTab rec={rec} pred={pred} ev={ev} />}
      {tab === "skor" && <ScoresTab rec={rec} pred={pred} />}
      {tab === "pasaran" && <MarketsTab rec={rec} pred={pred} />}
      {tab === "pemain" && <PlayersTab rec={rec} pred={pred} />}
      {tab === "setpiece" && <SetPiecesTab rec={rec} pred={pred} />}
      {tab === "simulasi" && <SimTab rec={rec} pred={pred} />}
      {tab === "ai" && <AITab rec={rec} pred={pred} />}
      {tab === "model" && <ModelTab rec={rec} pred={pred} />}

      {showForm && (
        <ResultForm
          rec={rec}
          pred={pred}
          onClose={() => setShowForm(false)}
          onClear={async () => {
            await saveRecord({ ...rec, result: null });
            setShowForm(false);
            toast("Hasil dihapus — pembelajaran dihitung ulang");
          }}
          onSave={async (r) => {
            await saveRecord({ ...rec, result: r });
            setShowForm(false);
            setTab("ringkasan");
            toast("Hasil tersimpan — model sudah belajar dari laga ini");
          }}
        />
      )}
    </div>
  );
}
