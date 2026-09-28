import { useMemo, useState } from "react";
import { useApp } from "../state";
import { ODDS_FORMAT_LABEL, type OddsFormat, ahIndo, fmtLine, fromDecimal, kelly, overround, removeMargin, toDecimal } from "../lib/odds";
import { matrixOutcome, over, scoreMatrix, settleAsian, topScores, totalDist } from "../lib/math";
import { NumField, Panel, Seg, odds2, pct } from "../components/ui";
import { IPlus, ITrash } from "../components/icons";

export function Tools() {
  return (
    <div className="page stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">Alat</div>
          <h1>Kalkulator taruhan</h1>
        </div>
      </div>
      <div className="grid-2">
        <OddsConverter />
        <MarginTool />
      </div>
      <div className="grid-2">
        <KellyTool />
        <AHTool />
      </div>
      <ParlayTool />
      <PoissonTool />
      <p className="muted small">Taruhan mengandung risiko kehilangan uang. Tetapkan batas modal dan jangan mengejar kekalahan.</p>
    </div>
  );
}

function OddsConverter() {
  const [fmt, setFmt] = useState<OddsFormat>("indo");
  const [val, setVal] = useState("-1.25");
  const d = toDecimal(val, fmt);
  const fmts = Object.keys(ODDS_FORMAT_LABEL) as OddsFormat[];
  return (
    <Panel title="Konversi odds" sub="Indo · Malay · HK · Desimal · US · Pecahan">
      <div className="row">
        <select className="input" style={{ maxWidth: 180 }} aria-label="Format odds" value={fmt} onChange={(e) => setFmt(e.target.value as OddsFormat)}>
          {fmts.map((f) => <option key={f} value={f}>{ODDS_FORMAT_LABEL[f]}</option>)}
        </select>
        <input className="input num" style={{ maxWidth: 140 }} aria-label="Nilai odds" value={val} onChange={(e) => setVal(e.target.value)} />
      </div>
      {d === null ? <p className="notice bad small" style={{ marginTop: 10 }}>Nilai tidak valid untuk format ini. {fmt === "indo" ? "Odds Indo ≥ 1 atau ≤ -1." : fmt === "malay" ? "Odds Malay antara -1 dan 1." : ""}</p> : (
        <table className="t" style={{ marginTop: 10 }}>
          <tbody>
            {fmts.map((f) => <tr key={f}><td>{ODDS_FORMAT_LABEL[f]}</td><td className="r num">{fromDecimal(d, f)}</td></tr>)}
            <tr className="hl"><td>Peluang tersirat</td><td className="r num">{pct(1 / d, 1)}</td></tr>
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function MarginTool() {
  const [o, setO] = useState<(number | null)[]>([2.1, 3.3, 3.6]);
  const valid = o.filter((x): x is number => x !== null && x > 1);
  const ok = valid.length === o.length && valid.length >= 2;
  const fair = ok ? removeMargin(valid) : [];
  const labels = o.length === 3 ? ["1", "X", "2"] : ["A", "B"];
  return (
    <Panel title="Hapus margin bandar" sub="peluang adil dari odds desimal" right={<Seg<number> label="Jumlah hasil" value={o.length} onChange={(n) => setO(n === 3 ? [2.1, 3.3, 3.6] : [1.9, 1.9])} options={[{ v: 3, label: "1X2" }, { v: 2, label: "2 arah" }]} />}>
      <div className="field-grid">
        {o.map((x, i) => <NumField key={`${o.length}-${i}`} id={`m${i}`} label={`Odds ${labels[i]}`} value={x} onChange={(v) => setO(o.map((y, j) => (j === i ? v : y)))} />)}
      </div>
      {ok && (
        <table className="t" style={{ marginTop: 10 }}>
          <thead><tr><th>Hasil</th><th className="r">Tersirat</th><th className="r">Adil</th><th className="r">Odds adil</th></tr></thead>
          <tbody>
            {valid.map((x, i) => <tr key={i}><td>{labels[i]}</td><td className="r num">{pct(1 / x, 1)}</td><td className="r num">{pct(fair[i], 1)}</td><td className="r num">{odds2(1 / fair[i])}</td></tr>)}
            <tr className="hl"><td>Margin bandar</td><td className="r num" colSpan={3}>{pct(overround(valid), 2)}</td></tr>
          </tbody>
        </table>
      )}
    </Panel>
  );
}

function KellyTool() {
  const { meta, updateSettings } = useApp();
  const [p, setP] = useState<number | null>(55);
  const [o, setO] = useState<number | null>(2.0);
  const bank = meta.settings.bankroll;
  const frac = meta.settings.kellyFraction;
  const k = p !== null && o !== null && o > 1 ? kelly(p / 100, o) : 0;
  const ev = p !== null && o !== null ? (p / 100) * o - 1 : null;
  return (
    <Panel title="Kalkulator Kelly" sub="ukuran taruhan optimal dari peluang & odds">
      <div className="field-grid">
        <NumField id="kp" label="Peluang menang" unit="%" value={p} onChange={setP} />
        <NumField id="ko" label="Odds desimal" value={o} onChange={setO} />
        <NumField id="kb" label="Modal (bankroll)" value={bank} onChange={(v) => updateSettings({ bankroll: v ?? 0 })} />
        <div className="field" style={{ gridColumn: "1 / -1" }}>
          <span className="lbl">Fraksi Kelly</span>
          <Seg<number> label="Fraksi Kelly" value={frac} onChange={(v) => updateSettings({ kellyFraction: v })} options={[{ v: 0.25, label: "¼" }, { v: 0.5, label: "½" }, { v: 1, label: "Penuh" }]} />
        </div>
      </div>
      <div className="stack-sm small" style={{ marginTop: 12 }}>
        <div className="row-between"><span>Nilai harapan (EV) per 1 unit</span><b className="num" style={{ color: ev !== null && ev > 0 ? "var(--good)" : "var(--bad)" }}>{ev !== null ? `${ev > 0 ? "+" : ""}${pct(ev, 1)}` : "—"}</b></div>
        <div className="row-between"><span>Kelly penuh</span><b className="num">{pct(k, 1)}</b></div>
        <div className="row-between"><span>Disarankan ({frac === 1 ? "penuh" : frac === 0.5 ? "½" : "¼"} Kelly)</span><b className="num">{pct(k * frac, 1)} ≈ {Math.round(bank * k * frac).toLocaleString("id-ID")}</b></div>
        {k === 0 && <p className="notice small">EV tidak positif: secara matematis tidak ada taruhan.</p>}
      </div>
    </Panel>
  );
}

function AHTool() {
  const [line, setLine] = useState<number | null>(-0.75);
  const [h, setH] = useState<number | null>(2);
  const [a, setA] = useState<number | null>(1);
  const [odds, setOdds] = useState<number | null>(1.9);
  const [stake, setStake] = useState<number | null>(100000);
  const s = line !== null && h !== null && a !== null ? settleAsian(h - a, line, 1) : null;
  const label = !s ? "—" : s.win ? "Menang penuh" : s.halfWin ? "Menang setengah" : s.push ? "Refund (push)" : s.halfLoss ? "Kalah setengah" : "Kalah";
  const payout = s && odds && stake ? s.win * stake * odds + s.halfWin * (stake / 2) * odds + s.halfWin * (stake / 2) + s.push * stake + s.halfLoss * (stake / 2) : null;
  return (
    <Panel title="Kalkulator handicap Asia" sub="hasil taruhan untuk tim yang Anda pilih">
      <div className="field-grid">
        <NumField id="al" label="Garis tim pilihan" value={line} onChange={setLine} hint={line !== null ? `${fmtLine(line)} = ${ahIndo(line)}` : undefined} />
        <NumField id="ah" label="Gol tim pilihan" value={h} onChange={setH} />
        <NumField id="aa" label="Gol lawan" value={a} onChange={setA} />
        <NumField id="ao" label="Odds desimal" value={odds} onChange={setOdds} />
        <NumField id="as" label="Taruhan" value={stake} onChange={setStake} />
      </div>
      <div className="row-between" style={{ marginTop: 12 }}>
        <span className={`chip ${s?.win || s?.halfWin ? "good" : s?.push ? "warn" : "bad"}`}>{label}</span>
        {payout !== null && <span className="small">Kembali <b className="num">{Math.round(payout).toLocaleString("id-ID")}</b> · laba <b className="num">{Math.round(payout - (stake ?? 0)).toLocaleString("id-ID")}</b></span>}
      </div>
    </Panel>
  );
}

function ParlayTool() {
  const [legs, setLegs] = useState<{ name: string; odds: number | null; p: number | null }[]>([
    { name: "Laga 1", odds: 1.85, p: 58 },
    { name: "Laga 2", odds: 1.9, p: 55 },
    { name: "Laga 3", odds: 2.05, p: 52 },
  ]);
  const [stake, setStake] = useState<number | null>(50000);
  const valid = legs.filter((l) => l.odds && l.odds > 1);
  const total = valid.reduce((s, l) => s * (l.odds as number), 1);
  const allP = legs.every((l) => l.p !== null);
  const pAll = allP ? legs.reduce((s, l) => s * ((l.p as number) / 100), 1) : null;
  return (
    <Panel title="Kalkulator mix parlay" sub="semua laga harus menang" right={<button className="btn btn-sm" type="button" onClick={() => setLegs([...legs, { name: `Laga ${legs.length + 1}`, odds: null, p: null }])}><IPlus /> Laga</button>}>
      <div className="table-wrap">
        <table className="t">
          <thead><tr><th>Pilihan</th><th>Odds desimal</th><th>Peluang model (%)</th><th></th></tr></thead>
          <tbody>
            {legs.map((l, i) => (
              <tr key={i}>
                <td><input className="input" aria-label="Nama pilihan" value={l.name} onChange={(e) => setLegs(legs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} /></td>
                <td><input className="input num" style={{ width: 90 }} inputMode="decimal" aria-label="Odds" value={l.odds ?? ""} onChange={(e) => setLegs(legs.map((x, j) => (j === i ? { ...x, odds: parseFloat(e.target.value) || null } : x)))} /></td>
                <td><input className="input num" style={{ width: 90 }} inputMode="decimal" aria-label="Peluang" value={l.p ?? ""} onChange={(e) => setLegs(legs.map((x, j) => (j === i ? { ...x, p: parseFloat(e.target.value) || null } : x)))} /></td>
                <td><button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label="Hapus laga" onClick={() => setLegs(legs.filter((_, j) => j !== i))}><ITrash /></button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="grid-3" style={{ marginTop: 12 }}>
        <NumField id="ps" label="Taruhan" value={stake} onChange={setStake} />
        <div className="tile"><div className="upper muted">Odds total</div><div className="v">{odds2(total)}</div><div className="s">bayar {stake ? Math.round(stake * total).toLocaleString("id-ID") : "—"}</div></div>
        <div className="tile"><div className="upper muted">Peluang semua masuk</div><div className="v">{pAll !== null ? pct(pAll, 1) : "—"}</div><div className="s">{pAll !== null ? `EV ${pAll * total - 1 > 0 ? "+" : ""}${pct(pAll * total - 1, 1)} · ${pAll * total - 1 > 0 ? "positif" : "negatif"}` : "isi peluang tiap laga"}</div></div>
      </div>
      <p className="muted small" style={{ marginTop: 8 }}>Peluang parlay menyusut cepat: 4 laga × 55% hanya 9%. Anggap sebagai hiburan porsi kecil.</p>
    </Panel>
  );
}

function PoissonTool() {
  const [lh, setLh] = useState<number | null>(1.6);
  const [la, setLa] = useState<number | null>(1.1);
  const res = useMemo(() => {
    if (lh === null || la === null || lh <= 0 || la <= 0) return null;
    const m = scoreMatrix(lh, la, -0.07, 1.04);
    const o = matrixOutcome(m);
    const td = totalDist(m);
    let btts = 0;
    for (let i = 1; i < m.length; i++) for (let j = 1; j < m.length; j++) btts += m[i][j];
    return { o, o25: over(td, 2.5), o15: over(td, 1.5), btts, top: topScores(m, 6) };
  }, [lh, la]);
  return (
    <Panel title="Poisson cepat" sub="dari ekspektasi gol (xG) langsung ke peluang">
      <div className="row">
        <NumField id="plh" label="xG tuan rumah" value={lh} onChange={setLh} />
        <NumField id="pla" label="xG tim tamu" value={la} onChange={setLa} />
      </div>
      {res && (
        <div className="grid-2" style={{ marginTop: 12 }}>
          <div className="stack-sm small">
            <div className="row-between"><span>1 / X / 2</span><b className="num">{pct(res.o.home)} / {pct(res.o.draw)} / {pct(res.o.away)}</b></div>
            <div className="row-between"><span>Over 1.5 / 2.5</span><b className="num">{pct(res.o15)} / {pct(res.o25)}</b></div>
            <div className="row-between"><span>BTTS</span><b className="num">{pct(res.btts)}</b></div>
          </div>
          <div className="row">{res.top.map((s) => <span key={`${s.h}-${s.a}`} className="chip plain num">{s.h}-{s.a} · {pct(s.p, 1)}</span>)}</div>
        </div>
      )}
    </Panel>
  );
}
