import { useMemo } from "react";
import { useApp } from "../state";
import { predict } from "../lib/predict";
import type { MatchRecord } from "../lib/types";
import { effectiveWeights } from "../lib/learning";
import { HBars } from "../components/charts";
import { fmtDate, pct } from "../components/ui";
import { IChevron, IPlus, ISpark } from "../components/icons";

export function MatchRow({ rec }: { rec: MatchRecord }) {
  const { go } = useApp();
  const p = useMemo(() => predict(rec.input, rec.snapshot, rec.ai), [rec]);
  const r = rec.result;
  const hit = r ? (p.pick === (r.ftH > r.ftA ? "1" : r.ftH < r.ftA ? "2" : "X")) : null;
  return (
    <button className="match-item" type="button" onClick={() => go({ page: "result", id: rec.id })}>
      <div style={{ minWidth: 0 }}>
        <div className="mi-teams">{rec.input.home.name} <span className="muted">vs</span> {rec.input.away.name}</div>
        <div className="mi-sub">{rec.input.leagueName} · {fmtDate(rec.input.kickoff)}</div>
        <div className="row" style={{ marginTop: 6, gap: 6 }}>
          {rec.demo && <span className="chip plain">Contoh</span>}
          {r ? <span className={`chip ${hit ? "good" : "bad"}`}>Hasil {r.ftH}-{r.ftA} · {hit ? "benar" : "salah"}</span> : !rec.demo && <span className="chip flood">Menunggu hasil</span>}
          {rec.ai && <span className="chip plain">AI</span>}
          <span className="muted small">keyakinan {p.confidence.score.toFixed(1)}</span>
        </div>
      </div>
      <div style={{ textAlign: "right" }}>
        <div className="mi-score">{p.scenario.ft[0]}-{p.scenario.ft[1]}</div>
        <div className="muted tiny">HT {p.scenario.ht[0]}-{p.scenario.ht[1]}</div>
      </div>
    </button>
  );
}

export function Home() {
  const { records, stats, learn, go, storeKind } = useApp();
  const real = records.filter((r) => !r.demo);
  const pending = real.filter((r) => !r.result).sort((a, b) => a.input.kickoff.localeCompare(b.input.kickoff));
  const recent = [...records].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5);
  const weights = effectiveWeights(learn.params).sort((a, b) => b.eff - a.eff);
  return (
    <div className="page stack">
      <section className="hero">
        <svg className="hero-lines" viewBox="0 0 300 300" aria-hidden="true">
          <g fill="none" stroke="rgba(237,243,236,.6)" strokeWidth="2">
            <circle cx="150" cy="150" r="70" />
            <circle cx="150" cy="150" r="4" fill="rgba(237,243,236,.6)" />
            <line x1="150" y1="0" x2="150" y2="300" />
            <rect x="60" y="-2" width="180" height="60" />
            <path d="M110 58a42 42 0 0 0 80 0" />
          </g>
        </svg>
        <div className="eyebrow">Asisten prediksi sepak bola</div>
        <h1 style={{ marginTop: 8 }}>Baca data. Hitung peluang. Belajar dari hasil.</h1>
        <p>Unggah screenshot statistik, BolaMetrik membacanya dengan AI, menghitung skor babak 1, babak 2, skor akhir, pencetak gol, penalti, kartu merah, handicap, over/under, dan rekomendasi pasaran. Setiap hasil yang Anda input membuat model mengoreksi kesalahannya sendiri.</p>
        <div className="row" style={{ marginTop: 18 }}>
          <button className="btn btn-primary btn-lg" type="button" onClick={() => go({ page: "analyze" })}><IPlus /> Analisis pertandingan</button>
          {records.some((r) => r.demo) && <button className="btn btn-lg" type="button" onClick={() => go({ page: "result", id: "demo" })}>Lihat contoh prediksi</button>}
        </div>
      </section>

      <div className="tiles">
        <div className="tile"><div className="upper muted">Prediksi</div><div className="v">{stats.total}</div><div className="s">{stats.pending} menunggu hasil</div></div>
        <div className="tile"><div className="upper muted">Dinilai</div><div className="v">{stats.settled}</div><div className="s">sumber pembelajaran</div></div>
        <div className="tile"><div className="upper muted">Akurasi 1X2</div><div className="v">{stats.settled ? pct(stats.hit1x2) : "—"}</div><div className="s">tebakan acak ±33%</div></div>
        <div className="tile"><div className="upper muted">Over/Under 2.5</div><div className="v">{stats.settled ? pct(stats.hitOU) : "—"}</div><div className="s">BTTS {stats.settled ? pct(stats.hitBTTS) : "—"}</div></div>
        <div className="tile"><div className="upper muted">Skor Brier</div><div className="v">{stats.brier !== null ? stats.brier.toFixed(3) : "—"}</div><div className="s">{stats.baseline !== null ? `dasar liga ${stats.baseline.toFixed(3)} · makin kecil makin baik` : "makin kecil makin baik"}</div></div>
        <div className="tile"><div className="upper muted">Rekomendasi</div><div className="v">{stats.recWin !== null ? pct(stats.recWin) : "—"}</div><div className="s">{stats.recN} pilihan aman/utama dinilai</div></div>
      </div>

      <div className="grid-2">
        <section className="panel">
          <div className="panel-head"><h3>Menunggu hasil</h3><span className="muted small">input skor akhir agar model belajar</span></div>
          {pending.length === 0 ? (
            <div className="empty" style={{ padding: 20 }}>
              <p>Belum ada prediksi yang menunggu hasil.</p>
              <button className="btn btn-sm" type="button" style={{ marginTop: 10 }} onClick={() => go({ page: "analyze" })}><IPlus /> Buat prediksi</button>
            </div>
          ) : (
            <div className="match-list">{pending.slice(0, 5).map((r) => <MatchRow key={r.id} rec={r} />)}</div>
          )}
        </section>
        <section className="panel">
          <div className="panel-head"><h3>Terbaru</h3><button className="btn btn-sm btn-ghost" type="button" onClick={() => go({ page: "history" })}>Semua <IChevron /></button></div>
          <div className="match-list">{recent.map((r) => <MatchRow key={r.id} rec={r} />)}</div>
        </section>
      </div>

      <div className="grid-2">
        <section className="panel">
          <div className="panel-head">
            <h3>Otak model</h3>
            <span className="muted small">{learn.params.learned ? `belajar dari ${learn.params.learned} laga` : "belum ada hasil — memakai bobot awal"}</span>
          </div>
          <HBars rows={weights.map((w) => ({ label: w.label, value: w.eff, note: `dasar ${w.base} × dipelajari ${w.learned.toFixed(2)}` }))} format={(v) => v.toFixed(2)} />
          <button className="btn btn-sm btn-ghost" type="button" style={{ marginTop: 12 }} onClick={() => go({ page: "lab" })}><ISpark /> Buka Lab Belajar</button>
        </section>
        <section className="panel">
          <div className="panel-head"><h3>Cara pakai</h3><span className="muted small">penyimpanan: {storeKind === "cloud" ? "cloud (sinkron antar perangkat)" : storeKind === "local" ? "perangkat ini" : "sementara"}</span></div>
          <ol className="stack-sm" style={{ margin: 0, paddingLeft: 20 }}>
            <li><b>Unggah screenshot</b> klasemen, statistik kandang/tandang, form, xG, pemain, cedera, odds. AI membacanya dan Anda meninjau sebelum diterapkan.</li>
            <li><b>Lengkapi yang kurang</b> di formulir. Meter kelengkapan menunjukkan seberapa kuat datanya.</li>
            <li><b>Hitung prediksi</b>: jawaban cepat, pasaran, handicap, skor per babak, pencetak gol, simulasi, dan analisis AI.</li>
            <li><b>Input hasil</b> setelah laga. Model mengevaluasi setiap kesalahan dan menyesuaikan diri.</li>
          </ol>
          <p className="muted small" style={{ marginTop: 12 }}>Prediksi adalah peluang, bukan kepastian. Pasang taruhan hanya dengan uang yang siap hilang.</p>
        </section>
      </div>
    </div>
  );
}
