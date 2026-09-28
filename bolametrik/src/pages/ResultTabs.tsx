import { useEffect, useMemo, useRef, useState } from "react";
import type { Prediction } from "../lib/predict";
import type { MatchRecord } from "../lib/types";
import type { Evaluation } from "../lib/learning";
import { effectiveP, fairOddsAsian, evAsian } from "../lib/math";
import { ahIndo, fmtLine, fmtTotalLine, kelly, removeMargin } from "../lib/odds";
import { CAT_LABEL, type Rec, type Tier } from "../lib/recommend";
import { likelihoodLabel } from "../lib/extras";
import { simulateMatch, type SimEvent } from "../lib/simulate";
import { aiErrorText, analyzeMatch, aiStatus, type AIStatus } from "../lib/ai";
import { useApp } from "../state";
import { DistBars, Gauge, HBars, ScoreHeatmap } from "../components/charts";
import { ConfChip, Meter, Panel, ProbBar, odds2, pct } from "../components/ui";
import { Markdown } from "../components/Markdown";
import { IPlay, ISpark, IStop } from "../components/icons";


const TIER_INFO: Record<Tier, { title: string; desc: string; cls: string }> = {
  aman: { title: "Paling aman", desc: "Peluang tertinggi & varians terendah — aman tidak berarti pasti.", cls: "good" },
  utama: { title: "Rekomendasi utama", desc: "Keseimbangan peluang dan nilai odds.", cls: "flood" },
  value: { title: "Value bet", desc: "Peluang model lebih tinggi dari harga bandar.", cls: "home" },
  spekulatif: { title: "Spekulatif", desc: "Odds besar, peluang kecil — porsi kecil saja.", cls: "away" },
};

export function RecList({ recs, bankroll, kFrac }: { recs: Rec[]; bankroll: number; kFrac: number }) {
  const tiers: Tier[] = ["aman", "utama", "value", "spekulatif"];
  return (
    <div className="stack">
      {tiers.map((t) => {
        const list = recs.filter((r) => r.tier === t);
        if (!list.length) return null;
        return (
          <div key={t} className="stack-sm">
            <div className="tier-head"><span className={`chip ${TIER_INFO[t].cls}`}>{TIER_INFO[t].title}</span><span className="muted small">{TIER_INFO[t].desc}</span></div>
            <div className="recs">
              {list.map((r, i) => (
                <div className="rec" key={`${t}-${i}`}>
                  <div className={`rec-bar ${t}`} />
                  <div style={{ minWidth: 0 }}>
                    <div className="rec-title">{r.label}</div>
                    <div className="rec-reason">{r.reason}</div>
                    <div className="row small" style={{ marginTop: 6, gap: 8 }}>
                      <span className="muted">{CAT_LABEL[r.pick.cat]}</span>
                      <ConfChip c={r.conf} />
                      <span className="muted">odds adil <b className="num">{odds2(r.fair)}</b></span>
                      {r.odds && <span className="muted">odds bandar <b className="num">{r.odds.toFixed(2)}</b> · edge <b className="num" style={{ color: "var(--good)" }}>+{pct(r.edge ?? 0, 1)}</b></span>}
                      {r.kellyPct !== undefined && r.kellyPct > 0 && <span className="muted">Kelly {kFrac * 100}%: <b className="num">{(r.kellyPct * kFrac).toFixed(1)}%</b> bankroll (≈ {Math.round((bankroll * r.kellyPct * kFrac) / 100).toLocaleString("id-ID")})</span>}
                    </div>
                  </div>
                  <div className="rec-p"><b>{pct(r.p)}</b></div>
                </div>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function EvalPanel({ rec, ev }: { rec: MatchRecord; ev: Evaluation }) {
  const r = rec.result!;
  const ok = (b: boolean | null) => (b === null ? <span className="chip plain">—</span> : b ? <span className="chip good">Benar</span> : <span className="chip bad">Salah</span>);
  return (
    <Panel title="Evaluasi setelah laga" sub={`hasil ${r.ftH}-${r.ftA}${r.htH !== null ? ` (HT ${r.htH}-${r.htA})` : ""}`} className={ev.outcomeHit ? "" : ""}>
      <div className="grid-2">
        <div className="stack-sm">
          <div className="row-between"><span>Arah hasil (1X2)</span>{ok(ev.outcomeHit)}</div>
          <div className="row-between"><span>Over/Under 2.5</span>{ok(ev.ouHit)}</div>
          <div className="row-between"><span>BTTS</span>{ok(ev.bttsHit)}</div>
          <div className="row-between"><span>Skor FT tepat</span>{ok(ev.ftExact)}</div>
          <div className="row-between"><span>Skor HT tepat</span>{ok(ev.htExact)}</div>
          <div className="row-between"><span>Penalti (ya/tidak)</span>{ok(ev.penOk)}</div>
          <div className="row-between"><span>Kartu merah (ya/tidak)</span>{ok(ev.redOk)}</div>
          <div className="row-between"><span>Skor Brier 1X2</span><b className="num">{ev.brier.toFixed(3)} <span className="muted">(dasar {ev.baselineBrier.toFixed(3)})</span></b></div>
        </div>
        <div className="stack-sm">
          <span className="upper muted">Rekomendasi</span>
          {ev.recs.map((x, i) => (
            <div key={i} className="row-between small">
              <span style={{ minWidth: 0 }}>{x.rec.label} <span className="muted">({pct(x.rec.p)})</span></span>
              {x.out ? <span className={`chip ${x.out.score >= 0.75 ? "good" : x.out.score === 0.5 ? "warn" : "bad"}`}>{x.out.label}</span> : <span className="chip plain">tanpa data</span>}
            </div>
          ))}
          {ev.scorerHits.length > 0 && (
            <>
              <span className="upper muted" style={{ marginTop: 8 }}>Calon pencetak gol</span>
              {ev.scorerHits.map((s) => (
                <div key={s.name} className="row-between small"><span>{s.name} <span className="muted">({pct(s.p)})</span></span>{s.scored ? <span className="chip good">Cetak gol</span> : <span className="chip plain">Tidak</span>}</div>
              ))}
            </>
          )}
        </div>
      </div>
      <div className="divider" />
      <span className="upper muted">Pelajaran dari laga ini</span>
      <ul className="small" style={{ margin: "8px 0 0", paddingLeft: 18 }}>
        {ev.lessons.map((l, i) => <li key={i} style={{ margin: "4px 0" }}>{l}</li>)}
      </ul>
    </Panel>
  );
}

export function SummaryTab({ rec, pred, ev }: { rec: MatchRecord; pred: Prediction; ev: Evaluation | null }) {
  const { meta } = useApp();
  const H = rec.input.home.name, A = rec.input.away.name;
  const x = pred.mk.x12;
  const sorted = [
    { k: H, p: x.home },
    { k: "Seri", p: x.draw },
    { k: A, p: x.away },
  ].sort((a, b) => b.p - a.p);
  return (
    <div className="stack">
      {ev && <EvalPanel rec={rec} ev={ev} />}
      <div>
        <div className="row-between" style={{ marginBottom: 10 }}>
          <h2>Jawaban cepat</h2>
          <span className="muted small">Skor babak 1 + babak 2 = skor FT (skenario koheren)</span>
        </div>
        <div className="quick">
          {pred.quick.map((q) => (
            <div className="qa" key={q.key}>
              <div className="qa-label">{q.label}</div>
              <div className={`qa-answer${q.answer.length > 24 ? " long" : ""}`}>{q.answer}</div>
              {q.note && <div className="qa-note">{q.note}</div>}
              <div className="qa-foot">
                {q.p !== null && <span className="num small dim">{pct(q.p)}</span>}
                <ConfChip c={q.conf} />
              </div>
            </div>
          ))}
        </div>
      </div>

      <Panel title="Peluang hasil (1X2)" sub={`ekspektasi gol ${pred.lam.lh.toFixed(2)} – ${pred.lam.la.toFixed(2)}`}>
        <ProbBar home={x.home} draw={x.draw} away={x.away} homeName={H} awayName={A} />
        {pred.consensus && (
          <div style={{ marginTop: 16 }}>
            <div className="row-between small" style={{ marginBottom: 6 }}>
              <b>Konsensus model + AI</b>
              <span className="muted">bobot AI {pct(pred.consensus.w)} (dipelajari dari akurasi)</span>
            </div>
            <ProbBar home={pred.consensus.home} draw={pred.consensus.draw} away={pred.consensus.away} homeName={H} awayName={A} />
            <p className="small dim" style={{ marginTop: 6 }}>Over 2.5: {pct(pred.consensus.over25)} · BTTS: {pct(pred.consensus.btts)}</p>
          </div>
        )}
      </Panel>

      <Panel title="Rekomendasi pasaran" sub="diurutkan berdasar peluang x keandalan kategori yang dipelajari">
        <RecList recs={pred.recs} bankroll={meta.settings.bankroll} kFrac={meta.settings.kellyFraction} />
      </Panel>

      <div className="grid-2">
        <Panel title="Keyakinan prediksi">
          <div className="row" style={{ alignItems: "center", gap: 18 }}>
            <Gauge value={pred.confidence.score} label={pred.confidence.label} />
            <div className="stack-sm grow">
              <ConfChip c={pred.confidence.label} />
              <p className="small dim">Mencerminkan kualitas & konsistensi data, bukan jaminan hasil.</p>
            </div>
          </div>
          <div className="stack-sm" style={{ marginTop: 12 }}>
            {pred.confidence.parts.map((p) => (
              <div key={p.label}>
                <div className="row-between small"><span>{p.label}</span><span className="muted">{p.note}</span></div>
                <Meter value={p.value} />
              </div>
            ))}
          </div>
        </Panel>
        <Panel title="Sinyal yang bertentangan" sub={pred.conflicts.length ? `${pred.conflicts.length} ditemukan` : "tidak ada yang berarti"}>
          {pred.conflicts.length === 0 ? (
            <p className="small dim">Sumber-sumber data menunjuk ke arah yang sama. Tetap ingat: varians sepak bola tinggi.</p>
          ) : (
            <div className="stack">
              {pred.conflicts.map((c, i) => (
                <div key={i} className={`conflict s${c.severity}`}>
                  <b>{c.title}</b>
                  <p className="small dim">{c.detail}</p>
                  <p className="small" style={{ marginTop: 4 }}><span className="muted">Bobot → </span>{c.resolution}</p>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </div>

      <div className="grid-2">
        <Panel title="Bukti terkuat">
          <div className="stack-sm">
            {pred.evidence.length === 0 && <p className="small muted">Tambahkan data tim untuk melihat bukti.</p>}
            {pred.evidence.slice(0, 5).map((e, i) => (
              <div key={i} className="evidence"><span className="n">{i + 1}</span><span className="small">{e.text}</span></div>
            ))}
          </div>
        </Panel>
        <Panel title="Risiko terbesar">
          <div className="stack-sm small">
            <p>Hasil alternatif utama: <b>{sorted[1].k}</b> ({pct(sorted[1].p)}). Peluang prediksi utama <b>{sorted[0].k}</b> gagal: <b>{pct(1 - sorted[0].p)}</b>.</p>
            {pred.conflicts[0] && <p className="dim">{pred.conflicts[0].detail}</p>}
            <p className="dim">Gol cepat, kartu merah, atau penalti ({pct(pred.pen.p)}) dapat mengubah jalannya laga. Skor tepat hanya {pct(pred.scenario.p)} — gunakan sebagai skenario, bukan kepastian.</p>
            {pred.confidence.parts[0].value < 0.5 && <p className="notice bad">Data kurang lengkap ({pct(pred.confidence.parts[0].value)}). Unggah screenshot statistik tambahan untuk menaikkan akurasi.</p>}
          </div>
        </Panel>
      </div>
    </div>
  );
}

export function ScoresTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const H = rec.input.home.name, A = rec.input.away.name;
  const mk = pred.mk;
  const td = mk.exactGoals;
  return (
    <div className="stack">
      <div className="grid-2">
        <Panel title="Peluang skor akhir" sub="angka = % · kotak bergaris = skor utama">
          <ScoreHeatmap m={pred.M.ft} homeName={H} awayName={A} mark={pred.scenario.ft} />
        </Panel>
        <Panel title="Skor paling mungkin">
          <table className="t">
            <thead><tr><th>Skor FT</th><th className="r">Peluang</th><th className="r">Odds adil</th></tr></thead>
            <tbody>
              {pred.topFT.map((s) => (
                <tr key={`${s.h}-${s.a}`} className={s.h === pred.scenario.ft[0] && s.a === pred.scenario.ft[1] ? "hl" : ""}>
                  <td className="num">{H} {s.h} - {s.a} {A}</td><td className="r num">{pct(s.p, 1)}</td><td className="r num">{odds2(1 / s.p)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      </div>
      <div className="grid-3">
        <Panel title="Skor babak 1">
          <table className="t"><tbody>
            {pred.topHT.map((s) => <tr key={`${s.h}-${s.a}`} className={s.h === pred.scenario.ht[0] && s.a === pred.scenario.ht[1] ? "hl" : ""}><td className="num">{s.h} - {s.a}</td><td className="r num">{pct(s.p, 1)}</td></tr>)}
          </tbody></table>
          <p className="small dim" style={{ marginTop: 8 }}>{H} unggul {pct(mk.ht.home)} · seri {pct(mk.ht.draw)} · {A} unggul {pct(mk.ht.away)}</p>
        </Panel>
        <Panel title="Skor babak 2 saja" sub="gol yang terjadi setelah turun minum">
          <table className="t"><tbody>
            {pred.top2H.map((s) => <tr key={`${s.h}-${s.a}`} className={s.h === pred.scenario.h2[0] && s.a === pred.scenario.h2[1] ? "hl" : ""}><td className="num">{s.h} - {s.a}</td><td className="r num">{pct(s.p, 1)}</td></tr>)}
          </tbody></table>
          <p className="small dim" style={{ marginTop: 8 }}>Babak 2: {H} {pct(mk.h2.home)} · seri {pct(mk.h2.draw)} · {A} {pct(mk.h2.away)}</p>
        </Panel>
        <Panel title="Waktu gol">
          <div className="stack-sm small">
            <div className="row-between"><span>Gol sebelum menit 15</span><b className="num">{pct(mk.goalBefore.m15)}</b></div>
            <div className="row-between"><span>Gol sebelum menit 30</span><b className="num">{pct(mk.goalBefore.m30)}</b></div>
            <div className="row-between"><span>Gol di babak 1</span><b className="num">{pct(mk.ht.over05)}</b></div>
            <div className="row-between"><span>Gol di babak 2</span><b className="num">{pct(mk.h2.over05)}</b></div>
            <div className="row-between"><span>Gol di kedua babak</span><b className="num">{pct(mk.goalBothHalves)}</b></div>
            <div className="row-between"><span>Babak tersubur</span><b className="num">1: {pct(mk.highestHalf.first)} · 2: {pct(mk.highestHalf.second)} · sama: {pct(mk.highestHalf.equal)}</b></div>
            <div className="row-between"><span>Cetak gol pertama</span><b className="num">{H} {pct(mk.firstToScore.home)} · {A} {pct(mk.firstToScore.away)}</b></div>
          </div>
        </Panel>
      </div>
      <div className="grid-2">
        <Panel title="HT/FT" sub="hasil babak 1 / hasil akhir">
          <table className="t">
            <thead><tr><th>HT \ FT</th><th className="r">{H}</th><th className="r">Seri</th><th className="r">{A}</th></tr></thead>
            <tbody>
              {(["1", "X", "2"] as const).map((a) => (
                <tr key={a}>
                  <td>{a === "1" ? H : a === "2" ? A : "Seri"}</td>
                  {(["1", "X", "2"] as const).map((b) => <td key={b} className="r num">{pct(mk.htft[`${a}/${b}`], 1)}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
        <Panel title="Total gol" sub={`lingkungan gol: ${pred.goalEnv}`}>
          <DistBars data={td} labels={["0", "1", "2", "3", "4", "5", "6+"]} highlight={td.indexOf(Math.max(...td))} />
        </Panel>
      </div>
      <Panel title="Selisih kemenangan">
        <HBars rows={mk.margin.map((m) => ({ label: m.label, value: m.p, color: m.label.startsWith("Tuan") ? "var(--home)" : m.label.startsWith("Tim") ? "var(--away)" : "var(--draw)" }))} max={Math.max(...mk.margin.map((m) => m.p))} format={(v) => pct(v, 1)} />
      </Panel>
    </div>
  );
}

export function MarketsTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const { meta } = useApp();
  const H = rec.input.home.name, A = rec.input.away.name;
  const mk = pred.mk;
  const o = rec.input.odds;
  const hasOdds = o.home !== null && o.draw !== null && o.away !== null;
  const fair = hasOdds ? removeMargin([o.home!, o.draw!, o.away!]) : null;
  const row = (label: string, p: number, odds: number | null, fairMkt?: number) => {
    const edge = odds ? p * odds - 1 : null;
    return (
      <tr key={label}>
        <td>{label}</td><td className="r num">{pct(p, 1)}</td><td className="r num">{odds2(1 / p)}</td>
        <td className="r num">{odds ? odds.toFixed(2) : "—"}</td>
        <td className="r num">{fairMkt !== undefined ? pct(fairMkt, 1) : "—"}</td>
        <td className="r num" style={{ color: edge !== null ? (edge > 0.03 ? "var(--good)" : edge < -0.05 ? "var(--bad)" : undefined) : undefined }}>{edge !== null ? `${edge > 0 ? "+" : ""}${pct(edge, 1)}` : "—"}</td>
        <td className="r num">{edge !== null && edge > 0 && odds ? `${(kelly(p, odds) * meta.settings.kellyFraction * 100).toFixed(1)}%` : "—"}</td>
      </tr>
    );
  };
  const ahRows = mk.ah.filter((r) => Math.abs(r.line - mk.fairAhLine) <= 1.25);
  return (
    <div className="stack">
      <Panel title="1X2, Double Chance, Draw No Bet" sub={hasOdds ? `margin bandar ${pct(1 / o.home! + 1 / o.draw! + 1 / o.away! - 1, 1)}` : "isi odds di formulir untuk menghitung edge"}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Pasaran</th><th className="r">Model</th><th className="r">Odds adil</th><th className="r">Odds bandar</th><th className="r">Pasar (tanpa margin)</th><th className="r">Edge</th><th className="r">Kelly</th></tr></thead>
            <tbody>
              {row(`${H} menang`, mk.x12.home, o.home, fair?.[0])}
              {row("Seri", mk.x12.draw, o.draw, fair?.[1])}
              {row(`${A} menang`, mk.x12.away, o.away, fair?.[2])}
              {row(`1X (${H} / seri)`, mk.dc.hx, null)}
              {row(`X2 (${A} / seri)`, mk.dc.xa, null)}
              {row("12 (tidak seri)", mk.dc.ha, null)}
              {row(`DNB ${H}`, mk.dnb.home, null)}
              {row(`DNB ${A}`, mk.dnb.away, null)}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="Handicap Asia" sub={`garis adil model: ${H} ${fmtLine(mk.fairAhLine)} (${ahIndo(mk.fairAhLine)})`}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Garis {H}</th><th className="r">Menang</th><th className="r">Menang ½</th><th className="r">Refund</th><th className="r">Kalah ½</th><th className="r">Kalah</th><th className="r">P efektif</th><th className="r">Odds adil {H}</th><th className="r">Odds adil {A}</th>{o.ahLine !== null && <th className="r">EV bandar</th>}</tr></thead>
            <tbody>
              {ahRows.map((r) => {
                const isBook = o.ahLine !== null && Math.abs(o.ahLine - r.line) < 1e-9;
                return (
                  <tr key={r.line} className={Math.abs(r.line - mk.fairAhLine) < 1e-9 || isBook ? "hl" : ""}>
                    <td className="num">{fmtLine(r.line)} <span className="muted">({ahIndo(r.line)})</span></td>
                    <td className="r num">{pct(r.home.win)}</td><td className="r num">{pct(r.home.halfWin)}</td><td className="r num">{pct(r.home.push)}</td><td className="r num">{pct(r.home.halfLoss)}</td><td className="r num">{pct(r.home.loss)}</td>
                    <td className="r num">{pct(effectiveP(r.home))}</td>
                    <td className="r num">{odds2(fairOddsAsian(r.home))}</td><td className="r num">{odds2(fairOddsAsian(r.away))}</td>
                    {o.ahLine !== null && <td className="r num">{isBook && o.ahHome && o.ahAway ? `${H} ${pct(evAsian(r.home, o.ahHome), 1)} · ${A} ${pct(evAsian(r.away, o.ahAway), 1)}` : ""}</td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <p className="muted small" style={{ marginTop: 8 }}>Garis negatif = tuan rumah memberi voor. P efektif: menang=1, menang½=0.75, refund=0.5, kalah½=0.25.</p>
      </Panel>

      <div className="grid-2">
        <Panel title="Over/Under total gol" sub={`garis adil ${fmtTotalLine(mk.fairTotalLine)}`}>
          <table className="t">
            <thead><tr><th>Garis</th><th className="r">Over</th><th className="r">Under</th><th className="r">Odds adil O/U</th></tr></thead>
            <tbody>
              {mk.totals.map((t) => (
                <tr key={t.line} className={t.line === 2.5 ? "hl" : ""}><td className="num">{t.line}</td><td className="r num">{pct(t.over, 1)}</td><td className="r num">{pct(t.under, 1)}</td><td className="r num">{odds2(1 / t.over)} / {odds2(1 / t.under)}</td></tr>
              ))}
            </tbody>
          </table>
          <div className="divider" />
          <span className="upper muted">Garis Asia</span>
          <table className="t">
            <thead><tr><th>Garis</th><th className="r">Over (P efektif)</th><th className="r">Under (P efektif)</th><th className="r">Odds adil O/U</th></tr></thead>
            <tbody>
              {mk.asianTotals.filter((t) => Math.abs(t.line - mk.fairTotalLine) <= 1).map((t) => (
                <tr key={t.line}><td className="num">{fmtTotalLine(t.line)}</td><td className="r num">{pct(effectiveP(t.over))}</td><td className="r num">{pct(effectiveP(t.under))}</td><td className="r num">{odds2(fairOddsAsian(t.over))} / {odds2(fairOddsAsian(t.under))}</td></tr>
              ))}
            </tbody>
          </table>
        </Panel>
        <Panel title="Gol per tim">
          <table className="t">
            <thead><tr><th>Pasaran</th><th className="r">{H}</th><th className="r">{A}</th></tr></thead>
            <tbody>
              {[0.5, 1.5, 2.5, 3.5].map((l, i) => (
                <tr key={l}><td>Over {l}</td><td className="r num">{pct(mk.teamTotals.home[i].over, 1)}</td><td className="r num">{pct(mk.teamTotals.away[i].over, 1)}</td></tr>
              ))}
              <tr><td>Clean sheet</td><td className="r num">{pct(mk.cleanSheet.home, 1)}</td><td className="r num">{pct(mk.cleanSheet.away, 1)}</td></tr>
              <tr><td>Menang tanpa kebobolan</td><td className="r num">{pct(mk.winToNil.home, 1)}</td><td className="r num">{pct(mk.winToNil.away, 1)}</td></tr>
              <tr><td>Cetak gol pertama</td><td className="r num">{pct(mk.firstToScore.home, 1)}</td><td className="r num">{pct(mk.firstToScore.away, 1)}</td></tr>
            </tbody>
          </table>
          <div className="divider" />
          <div className="stack-sm small">
            <div className="row-between"><span>Ada tim cetak 2+ gol (Over 1.5 tim)</span><b className="num">{pct(mk.anyTeam2plus, 1)}</b></div>
            <div className="row-between"><span>Kedua tim cetak 2+ gol</span><b className="num">{pct(mk.bothTeams2plus, 1)}</b></div>
            <div className="row-between"><span>BTTS Ya / Tidak</span><b className="num">{pct(mk.btts.yes, 1)} / {pct(mk.btts.no, 1)}</b></div>
            <div className="row-between"><span>BTTS & Over 2.5</span><b className="num">{pct(mk.bttsOver25, 1)}</b></div>
            <div className="row-between"><span>Ganjil / Genap</span><b className="num">{pct(mk.oddEven.odd, 1)} / {pct(mk.oddEven.even, 1)}</b></div>
            <div className="row-between"><span>Babak 1 Over 0.5 / 1.5</span><b className="num">{pct(mk.ht.over05, 1)} / {pct(mk.ht.over15, 1)}</b></div>
            <div className="row-between"><span>BTTS babak 1</span><b className="num">{pct(mk.ht.btts, 1)}</b></div>
          </div>
        </Panel>
      </div>

      <div className="grid-2">
        <Panel title="Hasil + BTTS">
          <table className="t">
            <thead><tr><th></th><th className="r">BTTS Ya</th><th className="r">BTTS Tidak</th></tr></thead>
            <tbody>
              <tr><td>{H} menang</td><td className="r num">{pct(mk.resultBtts["1Y"], 1)}</td><td className="r num">{pct(mk.resultBtts["1N"], 1)}</td></tr>
              <tr><td>Seri</td><td className="r num">{pct(mk.resultBtts.XY, 1)}</td><td className="r num">{pct(mk.resultBtts.XN, 1)}</td></tr>
              <tr><td>{A} menang</td><td className="r num">{pct(mk.resultBtts["2Y"], 1)}</td><td className="r num">{pct(mk.resultBtts["2N"], 1)}</td></tr>
            </tbody>
          </table>
        </Panel>
        <Panel title="Hasil + Over/Under 2.5">
          <table className="t">
            <thead><tr><th></th><th className="r">Over 2.5</th><th className="r">Under 2.5</th></tr></thead>
            <tbody>
              <tr><td>{H} menang</td><td className="r num">{pct(mk.resultOU["1O"], 1)}</td><td className="r num">{pct(mk.resultOU["1U"], 1)}</td></tr>
              <tr><td>Seri</td><td className="r num">{pct(mk.resultOU.XO, 1)}</td><td className="r num">{pct(mk.resultOU.XU, 1)}</td></tr>
              <tr><td>{A} menang</td><td className="r num">{pct(mk.resultOU["2O"], 1)}</td><td className="r num">{pct(mk.resultOU["2U"], 1)}</td></tr>
            </tbody>
          </table>
        </Panel>
      </div>

      <Panel title="Semua kandidat pasaran" sub="diurutkan dari peluang tertinggi">
        <div className="table-wrap" style={{ maxHeight: 420, overflowY: "auto" }}>
          <table className="t">
            <thead><tr><th>Pilihan</th><th>Kategori</th><th className="r">Peluang</th><th className="r">Odds adil</th><th>Keyakinan</th></tr></thead>
            <tbody>
              {[...pred.candidates].sort((a, b) => b.p - a.p).map((r, i) => (
                <tr key={i}><td>{r.label}</td><td className="muted">{CAT_LABEL[r.pick.cat]}</td><td className="r num">{pct(r.p, 1)}</td><td className="r num">{odds2(r.fair)}</td><td><ConfChip c={r.conf} /></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  );
}

export function PlayersTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const H = rec.input.home.name, A = rec.input.away.name;
  const table = (list: Prediction["scorers"]["home"], name: string) => (
    <Panel title={`Pencetak gol ${name}`} sub={`ekspektasi gol tim ${(list === pred.scorers.home ? pred.lam.lh : pred.lam.la).toFixed(2)}`}>
      {list.length === 0 ? <p className="muted small">Belum ada data pemain. Tambahkan di formulir atau lewat screenshot statistik pemain.</p> : (
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Pemain</th><th className="r">Kapan saja</th><th className="r">Pertama</th><th className="r">2+ gol</th><th>Kategori</th><th>Dasar</th></tr></thead>
            <tbody>
              {list.map((s) => (
                <tr key={s.id}>
                  <td><b>{s.name}</b> <span className="muted small">{s.pos}{s.playProb === 0 ? " · ABSEN" : s.playProb < 0.6 ? " · diragukan" : ""}</span></td>
                  <td className="r num">{pct(s.anytime, 1)}</td><td className="r num">{pct(s.first, 1)}</td><td className="r num">{pct(s.twoPlus, 1)}</td>
                  <td><span className="chip plain">{likelihoodLabel(s.anytime)}</span></td>
                  <td className="small muted">{s.basis}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
  const sc = pred.scenario.scorers;
  return (
    <div className="stack">
      <Panel title="Skenario gol utama" sub={`${H} ${pred.scenario.ft[0]}-${pred.scenario.ft[1]} ${A}`}>
        {sc.length === 0 ? <p className="small dim">{pred.scenario.ft[0] + pred.scenario.ft[1] === 0 ? "Skenario utama tanpa gol." : "Tambahkan pemain untuk melihat siapa pencetak golnya."}</p> : (
          <div className="row">
            {sc.map((s) => <span key={s.name} className={`chip ${s.team === "H" ? "home" : "away"}`}>{s.name}{s.goals > 1 ? ` ×${s.goals}` : ""}</span>)}
          </div>
        )}
        <p className="small muted" style={{ marginTop: 8 }}>Tidak ada pemain yang "pasti" mencetak gol. Angka di bawah adalah peluang model berdasar porsi gol/xG pemain terhadap ekspektasi gol tim.</p>
      </Panel>
      {table(pred.scorers.home, H)}
      {table(pred.scorers.away, A)}
    </div>
  );
}

export function SetPiecesTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const H = rec.input.home.name, A = rec.input.away.name;
  const c = pred.corners, k = pred.cards;
  return (
    <div className="stack">
      <div className="grid-2">
        <Panel title="Corner" sub={`data: ${c.dataQuality}`}>
          <div className="tiles" style={{ marginBottom: 12 }}>
            <div className="tile"><div className="upper muted">Total</div><div className="v">{c.lambda.toFixed(1)}</div></div>
            <div className="tile"><div className="upper muted">{H}</div><div className="v" style={{ color: "#9fd6f3" }}>{c.home.toFixed(1)}</div></div>
            <div className="tile"><div className="upper muted">{A}</div><div className="v" style={{ color: "#f7ac96" }}>{c.away.toFixed(1)}</div></div>
          </div>
          <table className="t">
            <thead><tr><th>Garis</th><th className="r">Over</th><th className="r">Under</th></tr></thead>
            <tbody>{c.lines.map((l) => <tr key={l.line}><td className="num">{l.line}</td><td className="r num">{pct(l.over, 1)}</td><td className="r num">{pct(1 - l.over, 1)}</td></tr>)}</tbody>
          </table>
          <p className="small dim" style={{ marginTop: 8 }}>Babak 1: ekspektasi {c.firstHalf.toFixed(1)} corner · {c.fhLines.map((l) => `O${l.line} ${pct(l.over)}`).join(" · ")}</p>
          <p className="small dim">Lebih banyak corner: {H} {pct(c.homeMore)} · sama {pct(c.equal)} · {A} {pct(c.awayMore)}</p>
          {c.dataQuality === "default liga" && <p className="notice small" style={{ marginTop: 8 }}>Belum ada data corner tim — memakai rata-rata liga. Unggah statistik corner untuk hasil yang lebih tajam.</p>}
        </Panel>
        <Panel title="Kartu" sub={`data: ${k.dataQuality}`}>
          <div className="tiles" style={{ marginBottom: 12 }}>
            <div className="tile"><div className="upper muted">Total</div><div className="v">{k.lambda.toFixed(1)}</div></div>
            <div className="tile"><div className="upper muted">{H}</div><div className="v" style={{ color: "#9fd6f3" }}>{k.home.toFixed(1)}</div></div>
            <div className="tile"><div className="upper muted">{A}</div><div className="v" style={{ color: "#f7ac96" }}>{k.away.toFixed(1)}</div></div>
          </div>
          <table className="t">
            <thead><tr><th>Garis</th><th className="r">Over</th><th className="r">Under</th></tr></thead>
            <tbody>{k.lines.map((l) => <tr key={l.line}><td className="num">{l.line}</td><td className="r num">{pct(l.over, 1)}</td><td className="r num">{pct(1 - l.over, 1)}</td></tr>)}</tbody>
          </table>
          <p className="small dim" style={{ marginTop: 8 }}>Babak 1: ekspektasi {k.firstHalf.toFixed(1)} kartu · {k.fhLines.map((l) => `O${l.line} ${pct(l.over)}`).join(" · ")}</p>
          {pred.cards.reasons.length > 0 && <p className="small dim">Faktor: {pred.cards.reasons.join(", ")}</p>}
        </Panel>
      </div>
      <div className="grid-2">
        <Panel title="Potensi penalti">
          <div className="row" style={{ gap: 16 }}>
            <div className="led" style={{ fontSize: 56 }}>{Math.round(pred.pen.p * 100)}<span style={{ fontSize: 26 }}>%</span></div>
            <div className="stack-sm small grow">
              <span>Ekspektasi {pred.pen.lambda.toFixed(2)} penalti per laga</span>
              <span className="dim">Jika ada penalti: {H} {pct(pred.pen.homeShare)} · {A} {pct(1 - pred.pen.homeShare)}</span>
              {pred.pen.reasons.map((r) => <span key={r} className="dim">• {r}</span>)}
            </div>
          </div>
        </Panel>
        <Panel title="Potensi kartu merah / pemain dikeluarkan">
          <div className="row" style={{ gap: 16 }}>
            <div className="led" style={{ fontSize: 56, color: "var(--bad)", textShadow: "0 0 18px rgba(255,93,108,.45)" }}>{Math.round(pred.red.p * 100)}<span style={{ fontSize: 26 }}>%</span></div>
            <div className="stack-sm small grow">
              <span>Ekspektasi {pred.red.lambda.toFixed(2)} kartu merah per laga</span>
              <span className="dim">Lebih mungkin: {pred.red.homeShare >= 0.5 ? H : A} ({pct(Math.max(pred.red.homeShare, 1 - pred.red.homeShare))})</span>
              {pred.red.reasons.map((r) => <span key={r} className="dim">• {r}</span>)}
            </div>
          </div>
        </Panel>
      </div>
    </div>
  );
}

export function SimTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const H = rec.input.home.name, A = rec.input.away.name;
  const [events, setEvents] = useState<SimEvent[]>([]);
  const [shown, setShown] = useState(0);
  const [running, setRunning] = useState(false);
  const [batch, setBatch] = useState<{ h: number; w: number; d: number; a: number; tally: Map<string, number> } | null>(null);
  const timer = useRef<number | null>(null);
  const tickRef = useRef<HTMLDivElement>(null);
  const params = useMemo(() => ({
    lh1: pred.lam.lh * pred.lam.fh, la1: pred.lam.la * pred.lam.fa, lh2: pred.lam.lh * (1 - pred.lam.fh), la2: pred.lam.la * (1 - pred.lam.fa),
    cornersH: pred.corners.home, cornersA: pred.corners.away, cardsH: pred.cards.home, cardsA: pred.cards.away,
    redLambda: pred.red.lambda, penLambda: pred.pen.lambda, penHomeShare: pred.pen.homeShare, homeName: H, awayName: A,
    scorersH: pred.scorers.home.filter((s) => s.lambda > 0).map((s) => ({ name: s.name, lambda: s.lambda / Math.max(pred.lam.lh, 0.1) })),
    scorersA: pred.scorers.away.filter((s) => s.lambda > 0).map((s) => ({ name: s.name, lambda: s.lambda / Math.max(pred.lam.la, 0.1) })),
  }), [pred, H, A]);

  useEffect(() => () => { if (timer.current) window.clearInterval(timer.current); }, []);
  useEffect(() => { tickRef.current?.scrollTo({ top: tickRef.current.scrollHeight, behavior: "smooth" }); }, [shown]);

  function start() {
    const ev = simulateMatch(params, Date.now()).filter((e) => e.type !== "chance" || Math.random() < 0.5);
    setEvents(ev);
    setShown(0);
    setRunning(true);
    if (timer.current) window.clearInterval(timer.current);
    let i = 0;
    timer.current = window.setInterval(() => {
      i++;
      setShown(i);
      if (i >= ev.length) {
        window.clearInterval(timer.current!);
        timer.current = null;
        setRunning(false);
      }
    }, 650);
  }
  function stop() {
    if (timer.current) window.clearInterval(timer.current);
    timer.current = null;
    setRunning(false);
    setShown(events.length);
  }
  function runBatch() {
    const tally = new Map<string, number>();
    let w = 0, d = 0, a = 0;
    const N = 1000;
    for (let i = 0; i < N; i++) {
      const ev = simulateMatch(params, i * 7919 + 13);
      const [x, y] = ev[ev.length - 1].score;
      if (x > y) w++;
      else if (x === y) d++;
      else a++;
      const k = `${x}-${y}`;
      tally.set(k, (tally.get(k) ?? 0) + 1);
    }
    setBatch({ h: N, w, d, a, tally });
  }
  const vis = events.slice(0, shown);
  const cur = vis.length ? vis[vis.length - 1] : null;
  const score = cur?.score ?? [0, 0];
  const last = [...vis].reverse().find((e) => e.team);
  const ballX = last ? (last.team === "H" ? (last.type === "goal" || last.type === "pen" ? 94 : 72) : last.type === "goal" || last.type === "pen" ? 6 : 28) : 50;
  const ballY = last ? 30 + ((last.minute * 37) % 40) : 50;
  return (
    <div className="stack">
      <div className="grid-2">
        <Panel title="Simulasi langsung" sub="satu kemungkinan jalannya laga, dari parameter model" right={
          running ? <button className="btn btn-sm" type="button" onClick={stop}><IStop /> Lewati</button> : <button className="btn btn-sm btn-primary" type="button" onClick={start}><IPlay /> {events.length ? "Ulangi" : "Mulai"}</button>
        }>
          <div className="pitch" aria-hidden="true">
            <svg viewBox="0 0 160 90" preserveAspectRatio="none">
              <g fill="none" stroke="rgba(255,255,255,.55)" strokeWidth=".6">
                <rect x="3" y="3" width="154" height="84" />
                <line x1="80" y1="3" x2="80" y2="87" />
                <circle cx="80" cy="45" r="11" />
                <rect x="3" y="25" width="20" height="40" /><rect x="137" y="25" width="20" height="40" />
                <rect x="3" y="36" width="7" height="18" /><rect x="150" y="36" width="7" height="18" />
              </g>
            </svg>
            <div className="ball" style={{ left: `${ballX}%`, top: `${ballY}%` }} />
            <div style={{ position: "absolute", top: 8, left: "50%", transform: "translateX(-50%)", background: "rgba(6,20,15,.85)", borderRadius: 8, padding: "4px 12px", display: "flex", gap: 10, alignItems: "center" }}>
              <span className="small" style={{ color: "#9fd6f3", fontWeight: 700 }}>{H}</span>
              <span className="led" style={{ fontSize: 26 }}>{score[0]}-{score[1]}</span>
              <span className="small" style={{ color: "#f7ac96", fontWeight: 700 }}>{A}</span>
              <span className="num tiny muted">{cur?.clock ?? "0'"}</span>
            </div>
          </div>
        </Panel>
        <Panel title="Jalannya laga">
          <div className="ticker" ref={tickRef}>
            {vis.length === 0 && <p className="muted small">Tekan Mulai untuk mensimulasikan 90 menit.</p>}
            {vis.map((e, i) => (
              <div key={i} className={`tick ${e.type}`}>
                <span className="m">{e.clock}</span>
                <span className="txt">{e.text}</span>
                <span className="num small">{e.score[0]}-{e.score[1]}</span>
              </div>
            ))}
          </div>
        </Panel>
      </div>
      <Panel title="Simulasi 1.000 laga" sub="memperlihatkan varians: hasil nyata bisa jauh dari prediksi utama" right={<button className="btn btn-sm" type="button" onClick={runBatch}>Jalankan</button>}>
        {!batch ? <p className="muted small">Jalankan untuk melihat sebaran hasil dari 1.000 simulasi.</p> : (
          <div className="stack">
            <ProbBar home={batch.w / batch.h} draw={batch.d / batch.h} away={batch.a / batch.h} homeName={H} awayName={A} />
            <div className="row">
              {[...batch.tally.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([k, v]) => <span key={k} className="chip plain num">{k} · {pct(v / batch.h, 1)}</span>)}
            </div>
          </div>
        )}
      </Panel>
    </div>
  );
}

export function AITab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const { meta, attachments, saveRecord, lessons, toast, go } = useApp();
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [withImgs, setWithImgs] = useState(true);
  const ctl = useRef<AbortController | null>(null);
  useEffect(() => {
    aiStatus(meta.settings.apiKey).then(setStatus);
  }, [meta.settings.apiKey]);
  const ready = !!status && (status.sample || status.api);

  async function run() {
    ctl.current = new AbortController();
    setBusy(true);
    setText("");
    try {
      const imgs = withImgs && status?.images ? attachments.map((a) => a.file).slice(0, status.maxImages || 5) : undefined;
      const op = await analyzeMatch(rec.input, pred, lessons, { apiKey: meta.settings.apiKey, model: meta.settings.aiModel, images: imgs, signal: ctl.current.signal, onText: setText });
      await saveRecord({ ...rec, ai: op });
      setText("");
      toast(op.probs ? "Analisis AI tersimpan — konsensus model+AI diperbarui" : "Analisis AI tersimpan");
    } catch (e) {
      const partial = (e as { text?: string; partial?: string })?.text ?? (e as { partial?: string })?.partial;
      if (partial) setText(partial);
      if ((e as { code?: string })?.code !== "cancelled") toast(aiErrorText(e), true);
    } finally {
      setBusy(false);
    }
  }

  const shown = busy || text ? text : rec.ai?.text ?? "";
  return (
    <div className="stack">
      <Panel title="Analis AI (Claude)" sub="narasi mendalam: fakta → interpretasi → inferensi → prediksi" right={
        busy ? <button className="btn btn-sm" type="button" onClick={() => ctl.current?.abort()}><IStop /> Hentikan</button> :
          <button className="btn btn-primary btn-sm" type="button" disabled={!ready} onClick={run}><ISpark /> {rec.ai ? "Analisis ulang" : "Minta analisis AI"}</button>
      }>
        <div className="stack-sm small">
          {!status && <span className="row dim"><span className="spin" /> Memeriksa ketersediaan AI…</span>}
          {status && !ready && (
            <p className="notice">AI belum aktif. Buka aplikasi ini sebagai Artifact di claude.ai (memakai akun Claude Anda), atau isi API key Anthropic di <button className="btn btn-sm btn-ghost" type="button" onClick={() => go({ page: "settings" })}>Pengaturan</button>.</p>
          )}
          {ready && attachments.length > 0 && status?.images && (
            <label className="check"><input type="checkbox" checked={withImgs} onChange={(e) => setWithImgs(e.target.checked)} /> Sertakan {attachments.length} screenshot yang diunggah</label>
          )}
          {ready && <p className="dim">AI menerima semua data formulir, output model, dan {lessons.length} pelajaran dari kesalahan sebelumnya. Estimasi peluang AI digabung dengan model; bobotnya dipelajari dari mana yang lebih akurat.</p>}
          {busy && !text && <span className="row dim"><span className="spin" /> Claude sedang berpikir… (bisa 30-90 detik)</span>}
        </div>
      </Panel>
      {rec.ai?.probs && !busy && (
        <Panel title="Perbandingan: model vs AI vs konsensus">
          <table className="t">
            <thead><tr><th></th><th className="r">{rec.input.home.name}</th><th className="r">Seri</th><th className="r">{rec.input.away.name}</th><th className="r">Over 2.5</th><th className="r">BTTS</th></tr></thead>
            <tbody>
              <tr><td>Model statistik</td><td className="r num">{pct(pred.mk.x12.home, 1)}</td><td className="r num">{pct(pred.mk.x12.draw, 1)}</td><td className="r num">{pct(pred.mk.x12.away, 1)}</td><td className="r num">{pct(pred.mk.totals[2].over, 1)}</td><td className="r num">{pct(pred.mk.btts.yes, 1)}</td></tr>
              <tr><td>AI Claude</td><td className="r num">{pct(rec.ai.probs.home, 1)}</td><td className="r num">{pct(rec.ai.probs.draw, 1)}</td><td className="r num">{pct(rec.ai.probs.away, 1)}</td><td className="r num">{pct(rec.ai.probs.over25, 1)}</td><td className="r num">{pct(rec.ai.probs.btts, 1)}</td></tr>
              {pred.consensus && <tr className="hl"><td><b>Konsensus</b></td><td className="r num">{pct(pred.consensus.home, 1)}</td><td className="r num">{pct(pred.consensus.draw, 1)}</td><td className="r num">{pct(pred.consensus.away, 1)}</td><td className="r num">{pct(pred.consensus.over25, 1)}</td><td className="r num">{pct(pred.consensus.btts, 1)}</td></tr>}
            </tbody>
          </table>
        </Panel>
      )}
      {shown && (
        <Panel title={busy ? "Menulis…" : "Laporan analisis"} sub={rec.ai && !busy ? new Date(rec.ai.createdAt).toLocaleString("id-ID") : undefined}>
          <Markdown text={shown} />
        </Panel>
      )}
    </div>
  );
}

export function ModelTab({ rec, pred }: { rec: MatchRecord; pred: Prediction }) {
  const H = rec.input.home.name, A = rec.input.away.name;
  const s = rec.snapshot;
  return (
    <div className="stack">
      <Panel title="Sumber data & bobot" sub="ekspektasi gol yang ditunjukkan tiap sumber">
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Sumber</th><th className="r">{H}</th><th className="r">{A}</th><th className="r">Reliabilitas</th><th className="r">Bobot</th><th>Rincian</th></tr></thead>
            <tbody>
              {pred.lam.signals.map((x) => (
                <tr key={x.key}><td>{x.label}</td><td className="r num">{x.lh.toFixed(2)}</td><td className="r num">{x.la.toFixed(2)}</td><td className="r num">{pct(x.rel)}</td><td className="r num">{x.weight.toFixed(2)}</td><td className="small muted">{x.detail}</td></tr>
              ))}
              <tr className="hl"><td><b>Gabungan berbobot</b></td><td className="r num">{pred.lam.combined.lh.toFixed(2)}</td><td className="r num">{pred.lam.combined.la.toFixed(2)}</td><td /><td /><td className="small muted">rata-rata geometris berbobot</td></tr>
            </tbody>
          </table>
        </div>
      </Panel>
      <div className="grid-2">
        <Panel title="Penyesuaian konteks">
          {pred.lam.adjustments.length === 0 ? <p className="small muted">Tidak ada.</p> : (
            <table className="t"><tbody>
              {pred.lam.adjustments.map((a) => <tr key={a.label}><td className="small">{a.label}</td><td className="r num">×{a.home.toFixed(3)}</td><td className="r num">×{a.away.toFixed(3)}</td></tr>)}
            </tbody></table>
          )}
          <div className="divider" />
          <div className="stack-sm small">
            <div className="row-between"><span>λ mentah</span><b className="num">{pred.lam.raw.lh.toFixed(2)} – {pred.lam.raw.la.toFixed(2)}</b></div>
            <div className="row-between"><span>λ akhir (setelah parameter dipelajari)</span><b className="num">{pred.lam.lh.toFixed(2)} – {pred.lam.la.toFixed(2)}</b></div>
            <div className="row-between"><span>Porsi gol babak 1</span><b className="num">{pct(pred.lam.fh)} – {pct(pred.lam.fa)}</b></div>
          </div>
        </Panel>
        <Panel title="Parameter saat prediksi dibuat" sub={`belajar dari ${s.learned} laga`}>
          <div className="stack-sm small">
            <div className="row-between"><span>Skala gol global</span><b className="num">{s.goalScale.toFixed(3)}</b></div>
            <div className="row-between"><span>Faktor liga ini</span><b className="num">{(s.leagueFactor[rec.input.leagueKey] ?? 1).toFixed(3)}</b></div>
            <div className="row-between"><span>Keunggulan kandang</span><b className="num">{s.homeAdv.toFixed(3)}</b></div>
            <div className="row-between"><span>Faktor seri</span><b className="num">{s.drawBoost.toFixed(3)}</b></div>
            <div className="row-between"><span>Rho Dixon-Coles</span><b className="num">{s.rho.toFixed(3)}</b></div>
            <div className="row-between"><span>Skala corner / kartu</span><b className="num">{s.cornerScale.toFixed(2)} / {s.cardScale.toFixed(2)}</b></div>
            <div className="row-between"><span>Skala penalti / merah</span><b className="num">{s.penScale.toFixed(2)} / {s.redScale.toFixed(2)}</b></div>
            <div className="row-between"><span>Bobot AI</span><b className="num">{pct(s.aiWeight)}</b></div>
          </div>
        </Panel>
      </div>
      <Panel title="Cara kerja model">
        <ol className="small dim" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.7 }}>
          <li>Setiap sumber data (musim, kandang/tandang, form, xG, tembakan, poin, H2H, rata-rata liga) menghasilkan ekspektasi gol sendiri, disusutkan ke rata-rata liga bila sampelnya kecil.</li>
          <li>Ekspektasi digabung dengan bobot = bobot dasar × bobot yang dipelajari × reliabilitas sampel.</li>
          <li>Konteks (absen, motivasi, istirahat, derby, final) mengalikan ekspektasi gol.</li>
          <li>Parameter global yang dipelajari dari hasil nyata (skala gol, faktor liga, keunggulan kandang) diterapkan.</li>
          <li>Matriks skor Poisson dengan koreksi Dixon-Coles & faktor seri → semua pasaran (1X2, AH, O/U, BTTS, HT/FT, dll.).</li>
          <li>Babak 1 & 2 dimodelkan terpisah memakai porsi gol babak 1 tiap tim; corner & kartu memakai distribusi Negative Binomial.</li>
          <li>Setelah hasil diinput: semua laga selesai di-replay secara kronologis, bobot sumber diperbarui (algoritma Hedge), dan keandalan tiap kategori pasaran dikalibrasi.</li>
        </ol>
      </Panel>
    </div>
  );
}

