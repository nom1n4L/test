import { useApp } from "../state";
import { defaultParams } from "../lib/model";
import { effectiveWeights } from "../lib/learning";
import { CAT_LABEL, marketReliability } from "../lib/recommend";
import type { MarketCat } from "../lib/types";
import { CalibChart, HBars, LineChart } from "../components/charts";
import { Panel, fmtDate, pct } from "../components/ui";
import { IChevron } from "../components/icons";

function rolling(xs: number[], w: number) {
  return xs.map((_, i) => {
    const s = xs.slice(Math.max(0, i - w + 1), i + 1);
    return s.reduce((a, b) => a + b, 0) / s.length;
  });
}

export function Lab() {
  const { learn, stats, go, records } = useApp();
  const p = learn.params;
  const d = defaultParams();
  const hist = learn.history;
  const weights = effectiveWeights(p).sort((a, b) => b.eff - a.eff);
  const cats = Object.entries(p.market).filter(([, s]) => s && s.n > 0) as [MarketCat, { n: number; hits: number; sumP: number }][];
  const leagueNames = new Map(records.map((r) => [r.input.leagueKey, r.input.leagueName]));
  const rows: { label: string; now: number; def: number; meaning: string }[] = [
    { label: "Skala gol global", now: p.goalScale, def: d.goalScale, meaning: ">1: laga-laga Anda lebih banyak gol dari perkiraan awal" },
    { label: "Keunggulan kandang", now: p.homeAdv, def: d.homeAdv, meaning: ">1: tuan rumah lebih kuat dari rata-rata liga" },
    { label: "Faktor seri", now: p.drawBoost, def: d.drawBoost, meaning: ">1: seri lebih sering dari model Poisson murni" },
    { label: "Porsi gol babak 1", now: p.fhShare, def: d.fhShare, meaning: "bagian gol yang terjadi sebelum turun minum" },
    { label: "Skala corner", now: p.cornerScale, def: d.cornerScale, meaning: "koreksi ekspektasi corner" },
    { label: "Skala kartu", now: p.cardScale, def: d.cardScale, meaning: "koreksi ekspektasi kartu" },
    { label: "Skala penalti", now: p.penScale, def: d.penScale, meaning: "koreksi peluang penalti" },
    { label: "Skala kartu merah", now: p.redScale, def: d.redScale, meaning: "koreksi peluang kartu merah" },
    { label: "Bobot AI dalam konsensus", now: p.aiWeight, def: d.aiWeight, meaning: "naik bila AI lebih akurat dari model statistik" },
    ...Object.entries(p.leagueFactor).map(([k, v]) => ({ label: `Faktor liga: ${leagueNames.get(k) ?? k}`, now: v, def: 1, meaning: "koreksi total gol khusus liga ini" })),
  ];
  return (
    <div className="page stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">Lab belajar</div>
          <h1>Belajar dari kesalahan</h1>
          <p className="dim" style={{ marginTop: 8, maxWidth: "70ch" }}>Setiap hasil nyata di-replay secara kronologis. Model memperbarui skala gol, faktor liga, keunggulan kandang, faktor seri, bobot tiap sumber data, dan keandalan tiap kategori pasaran. Sumber yang terbukti akurat mendapat bobot lebih besar.</p>
        </div>
      </div>

      {stats.settled === 0 ? (
        <Panel title="Belum ada yang dipelajari">
          <div className="stack-sm small dim">
            <p>Setelah pertandingan selesai, buka prediksinya lalu tekan <b>Input hasil</b>. Semakin banyak hasil, semakin tajam kalibrasinya — mulai terasa setelah ±10 laga dan stabil setelah ±50 laga.</p>
            <p>Yang akan dipelajari: apakah model terlalu banyak/sedikit memprediksi gol, apakah seri diremehkan, sumber data mana (xG, form, kandang/tandang, H2H…) yang paling sering benar, dan pasaran mana yang rekomendasinya terlalu percaya diri.</p>
          </div>
          <button className="btn" type="button" style={{ marginTop: 12 }} onClick={() => go({ page: "history" })}>Ke riwayat <IChevron /></button>
        </Panel>
      ) : (
        <>
          <div className="tiles">
            <div className="tile"><div className="upper muted">Laga dinilai</div><div className="v">{stats.settled}</div></div>
            <div className="tile"><div className="upper muted">Akurasi 1X2</div><div className="v">{pct(stats.hit1x2)}</div></div>
            <div className="tile"><div className="upper muted">Brier model</div><div className="v">{stats.brier?.toFixed(3)}</div><div className="s">dasar liga {stats.baseline?.toFixed(3)}</div></div>
            <div className="tile"><div className="upper muted">O/U 2.5 · BTTS</div><div className="v">{pct(stats.hitOU)}</div><div className="s">BTTS {pct(stats.hitBTTS)}</div></div>
            <div className="tile"><div className="upper muted">Skor FT / HT tepat</div><div className="v">{pct(stats.exactFT)}</div><div className="s">HT {pct(stats.exactHT)}</div></div>
            <div className="tile"><div className="upper muted">ROI value bet</div><div className="v">{stats.roi !== null ? `${stats.roi > 0 ? "+" : ""}${pct(stats.roi, 1)}` : "—"}</div><div className="s">{stats.valueN} taruhan value (stake rata)</div></div>
          </div>
          <div className="grid-2">
            <Panel title="Tren skor Brier" sub="rata-rata bergulir 5 laga · lebih rendah = lebih baik">
              <LineChart yLabel="Skor Brier" series={[
                { name: "Model", color: "var(--flood)", values: rolling(hist.map((h) => h.brier), 5) },
                { name: "Tebakan dasar liga", color: "var(--chalk-3)", values: rolling(hist.map((h) => h.baseline), 5), dashed: true },
              ]} />
            </Panel>
            <Panel title="Kalibrasi peluang 1X2">
              <CalibChart bins={stats.calibration} />
            </Panel>
          </div>
        </>
      )}

      <div className="grid-2">
        <Panel title="Bobot sumber data" sub="dasar × dipelajari">
          <HBars rows={weights.map((w) => ({ label: w.label, value: w.eff, note: `dasar ${w.base} × dipelajari ${w.learned.toFixed(2)}`, color: w.learned > 1.05 ? "var(--good)" : w.learned < 0.95 ? "var(--bad)" : "var(--flood)" }))} />
          <p className="muted small" style={{ marginTop: 10 }}>Hijau = dipercaya lebih dari awal, merah = sering meleset.</p>
        </Panel>
        <Panel title="Parameter yang dipelajari">
          <table className="t">
            <thead><tr><th>Parameter</th><th className="r">Awal</th><th className="r">Sekarang</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.label}>
                  <td>{r.label}<div className="tiny muted">{r.meaning}</div></td>
                  <td className="r num muted">{r.def.toFixed(3)}</td>
                  <td className="r num" style={{ color: Math.abs(r.now - r.def) > 0.02 ? "var(--flood)" : undefined }}>{r.now.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      </div>

      <Panel title="Keandalan tiap pasaran" sub="rekomendasi yang menang vs peluang yang dijanjikan model">
        {cats.length === 0 ? <p className="muted small">Belum ada rekomendasi yang dinilai.</p> : (
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>Pasaran</th><th className="r">Dinilai</th><th className="r">Menang</th><th className="r">Dijanjikan</th><th className="r">Pengali</th><th>Status</th></tr></thead>
              <tbody>
                {cats.map(([cat, s]) => {
                  const rel = marketReliability(p, cat);
                  const status = s.n < 3 ? ["plain", "sampel kecil"] : rel < 0.95 ? ["bad", "terlalu yakin → diturunkan"] : rel > 1.05 ? ["good", "lebih akurat → dinaikkan"] : ["okay", "terkalibrasi"];
                  return (
                    <tr key={cat}>
                      <td>{CAT_LABEL[cat]}</td>
                      <td className="r num">{s.n}</td>
                      <td className="r num">{pct(s.hits / s.n)}</td>
                      <td className="r num">{pct(s.sumP / s.n)}</td>
                      <td className="r num">×{rel.toFixed(2)}</td>
                      <td><span className={`chip ${status[0]}`}>{status[1]}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="Jurnal pembelajaran" sub="perubahan parameter setelah tiap laga (terbaru di atas)">
        {learn.log.length === 0 ? <p className="muted small">Kosong.</p> : (
          <div className="stack">
            {[...learn.log].reverse().slice(0, 40).map((l) => (
              <div key={l.recordId} className="stack-sm" style={{ borderLeft: "3px solid var(--turf-3)", paddingLeft: 12 }}>
                <div className="row-between">
                  <button className="btn btn-ghost btn-sm" type="button" style={{ padding: 0 }} onClick={() => go({ page: "result", id: l.recordId })}><b>{l.label}</b></button>
                  <span className="muted small">{fmtDate(l.date)} · Brier {l.brier.toFixed(3)}</span>
                </div>
                {l.changes.length ? <ul className="small dim" style={{ margin: 0, paddingLeft: 18 }}>{l.changes.map((c) => <li key={c}>{c}</li>)}</ul> : <span className="small muted">Prediksi sesuai; tidak ada perubahan berarti.</span>}
              </div>
            ))}
          </div>
        )}
      </Panel>
    </div>
  );
}
