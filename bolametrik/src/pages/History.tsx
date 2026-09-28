import { useState } from "react";
import { useApp } from "../state";
import { MatchRow } from "./Home";
import { Seg } from "../components/ui";
import { IPlus } from "../components/icons";

type Filter = "semua" | "menunggu" | "selesai";

export function History() {
  const { records, go } = useApp();
  const [f, setF] = useState<Filter>("semua");
  const [q, setQ] = useState("");
  const list = records
    .filter((r) => (f === "menunggu" ? !r.result && !r.demo : f === "selesai" ? !!r.result : true))
    .filter((r) => {
      const s = q.trim().toLowerCase();
      if (!s) return true;
      return [r.input.home.name, r.input.away.name, r.input.leagueName].some((x) => x.toLowerCase().includes(s));
    })
    .sort((a, b) => b.input.kickoff.localeCompare(a.input.kickoff));
  return (
    <div className="page stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">Riwayat</div>
          <h1>Semua prediksi</h1>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => go({ page: "analyze" })}><IPlus /> Analisis baru</button>
      </div>
      <div className="row-between">
        <Seg<Filter> label="Saring" value={f} onChange={setF} options={[{ v: "semua", label: "Semua" }, { v: "menunggu", label: "Menunggu hasil" }, { v: "selesai", label: "Selesai" }]} />
        <input className="input" style={{ maxWidth: 280 }} aria-label="Cari tim atau liga" placeholder="Cari tim atau liga…" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      {list.length === 0 ? (
        <div className="panel empty"><h3>Tidak ada prediksi</h3><p>Ubah saringan, atau buat analisis baru.</p></div>
      ) : (
        <div className="match-list">{list.map((r) => <MatchRow key={r.id} rec={r} />)}</div>
      )}
    </div>
  );
}
