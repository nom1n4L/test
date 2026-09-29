import { useEffect, useMemo, useState } from "react";
import { useApp } from "../state";
import { LEAGUES, leagueByKey } from "../lib/leagues";
import { teamGroups } from "../lib/fields";
import { dataCompleteness } from "../lib/insights";
import { demoMatch, emptyPlayer, emptyTeam } from "../lib/sample";
import type { Competition, H2HMatch, Importance, MatchInput, Player, TeamInput } from "../lib/types";
import { UploadPanel } from "../components/UploadPanel";
import { FormLetters, Meter, NumField, Panel, Seg, TextField, pct } from "../components/ui";
import { IChevron, IPlus, ITrash } from "../components/icons";

type Side = "home" | "away";
type Mode = "cepat" | "lengkap";

export function Analyze({ editId }: { editId?: string }) {
  const { draft, setDraft, resetDraft, createFromDraft, records, saveRecord, go, toast, learn, meta, saveTeam, clearAttachments } = useApp();
  const editing = editId ? records.find((r) => r.id === editId) : undefined;
  const [loadedEdit, setLoadedEdit] = useState<string | null>(null);
  const [mode, setModeState] = useState<Mode>(() => {
    try {
      return (localStorage.getItem("bolametrik:mode") as Mode) || "cepat";
    } catch {
      return "cepat";
    }
  });
  const setMode = (m: Mode) => {
    setModeState(m);
    try {
      localStorage.setItem("bolametrik:mode", m);
    } catch {
      /* diabaikan */
    }
  };
  const quick = mode === "cepat";

  useEffect(() => {
    if (editing && loadedEdit !== editing.id) {
      setDraft(JSON.parse(JSON.stringify(editing.input)));
      setLoadedEdit(editing.id);
    }
  }, [editing, loadedEdit, setDraft]);

  const set = (fn: (m: MatchInput) => void) =>
    setDraft((prev) => {
      const next: MatchInput = JSON.parse(JSON.stringify(prev));
      fn(next);
      return next;
    });

  const completeness = useMemo(() => dataCompleteness(draft), [draft]);
  const canRun = draft.home.name.trim() !== "" && draft.away.name.trim() !== "";

  async function run() {
    if (!canRun) {
      toast("Isi nama kedua tim terlebih dahulu.", true);
      return;
    }
    if (editing && !editing.demo) {
      const input: MatchInput = JSON.parse(JSON.stringify(draft));
      await saveRecord({ ...editing, input, snapshot: learn.params, ai: null });
      saveTeam(input.home);
      saveTeam(input.away);
      toast("Data diperbarui & prediksi dihitung ulang");
      go({ page: "result", id: editing.id });
      return;
    }
    const id = await createFromDraft();
    toast("Prediksi dibuat");
    go({ page: "result", id });
  }

  return (
    <div className="page stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">{editing ? "Edit data pertandingan" : "Analisis baru"}</div>
          <h1>{draft.home.name || "Tuan rumah"} <span className="muted">vs</span> {draft.away.name || "Tim tamu"}</h1>
        </div>
        <div className="row">
          <button className="btn btn-ghost" type="button" onClick={() => { setDraft(demoMatch()); toast("Contoh dimuat (tim fiktif)"); }}>Muat contoh</button>
          <button className="btn btn-ghost" type="button" onClick={() => { resetDraft(); clearAttachments(); }}>Kosongkan</button>
        </div>
      </div>

      <div className="row-between">
        <Seg<Mode> label="Mode input" value={mode} onChange={setMode} options={[{ v: "cepat", label: "Mode cepat" }, { v: "lengkap", label: "Mode lengkap" }]} />
        <span className="muted small">{quick ? "Cukup nama tim — data lain opsional" : "Semua statistik untuk akurasi maksimal"}</span>
      </div>
      {quick && (
        <p className="notice small">
          Isi <b>nama kedua tim</b> saja sudah bisa dihitung. Setiap tambahan (posisi klasemen, form, penilaian kekuatan, <b>odds bandar</b>) menaikkan akurasi & keyakinan. Odds 1X2 + Over/Under paling membantu bila statistik tim tidak ada.
        </p>
      )}

      {!quick && <UploadPanel />}

      <Panel title="Pertandingan" sub="liga menentukan rata-rata gol dasar">
        <div className="field-grid wide">
          <div className="field">
            <label htmlFor="league">Liga / kompetisi</label>
            <select id="league" className="input" value={draft.leagueKey} onChange={(e) => set((m) => {
              const lg = leagueByKey(e.target.value);
              m.leagueKey = lg.key;
              m.leagueName = lg.name;
              m.homeAvg = lg.homeAvg;
              m.awayAvg = lg.awayAvg;
            })}>
              {LEAGUES.map((l) => <option key={l.key} value={l.key}>{l.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="kickoff">Waktu kick-off</label>
            <input id="kickoff" type="datetime-local" className="input" value={draft.kickoff} onChange={(e) => set((m) => void (m.kickoff = e.target.value))} />
          </div>
          {!quick && <NumField id="homeAvg" label="Rata-rata gol tuan rumah (liga)" value={draft.homeAvg} onChange={(v) => set((m) => void (m.homeAvg = v ?? leagueByKey(m.leagueKey).homeAvg))} hint={`Faktor liga dipelajari: ${(learn.params.leagueFactor[draft.leagueKey] ?? 1).toFixed(2)}`} />}
          {!quick && <NumField id="awayAvg" label="Rata-rata gol tim tamu (liga)" value={draft.awayAvg} onChange={(v) => set((m) => void (m.awayAvg = v ?? leagueByKey(m.leagueKey).awayAvg))} />}
        </div>
        <div className="row" style={{ marginTop: 14, gap: 16 }}>
          {!quick && <>
          <div className="stack-sm">
            <span className="upper muted">Jenis laga</span>
            <Seg<Competition> label="Jenis laga" value={draft.competition} onChange={(v) => set((m) => void (m.competition = v))} options={[{ v: "league", label: "Liga" }, { v: "cup", label: "Piala" }, { v: "continental", label: "Kontinental" }, { v: "friendly", label: "Persahabatan" }]} />
          </div>
          <div className="stack-sm">
            <span className="upper muted">Kepentingan</span>
            <Seg<Importance> label="Kepentingan" value={draft.importance} onChange={(v) => set((m) => void (m.importance = v))} options={[{ v: "normal", label: "Normal" }, { v: "high", label: "Penting" }, { v: "final", label: "Final" }]} />
          </div>
          </>}
          <label className="check"><input type="checkbox" checked={draft.derby} onChange={(e) => set((m) => void (m.derby = e.target.checked))} /> Derby / rivalitas</label>
          <label className="check"><input type="checkbox" checked={draft.neutral} onChange={(e) => set((m) => void (m.neutral = e.target.checked))} /> Venue netral</label>
        </div>
      </Panel>

      {quick ? (
        <>
          <div className="grid-2">
            <QuickTeam side="home" team={draft.home} onChange={(t) => set((m) => void (m.home = t))} teams={meta.teams} />
            <QuickTeam side="away" team={draft.away} onChange={(t) => set((m) => void (m.away = t))} teams={meta.teams} />
          </div>
          <Panel title="Odds bandar (opsional)" sub="desimal — sangat membantu bila statistik tim minim">
            <div className="field-grid">
              <NumField id="q-o-h" label="1 (tuan rumah)" value={draft.odds.home} onChange={(v) => set((m) => void (m.odds.home = v))} />
              <NumField id="q-o-d" label="X (seri)" value={draft.odds.draw} onChange={(v) => set((m) => void (m.odds.draw = v))} />
              <NumField id="q-o-a" label="2 (tim tamu)" value={draft.odds.away} onChange={(v) => set((m) => void (m.odds.away = v))} />
              <NumField id="q-o-o" label="Over 2.5" value={draft.odds.over25} onChange={(v) => set((m) => void (m.odds.over25 = v))} />
              <NumField id="q-o-u" label="Under 2.5" value={draft.odds.under25} onChange={(v) => set((m) => void (m.odds.under25 = v))} />
            </div>
            <p className="muted tiny" style={{ marginTop: 8 }}>Odds Indo/Malay/HK bisa diubah ke desimal di menu Alat. Butuh statistik lebih lengkap? Pindah ke Mode lengkap — data yang sudah diisi tetap tersimpan.</p>
          </Panel>
          <UploadPanel />
        </>
      ) : (
        <>
        <div className="grid-2">
          <TeamPanel side="home" team={draft.home} neutral={draft.neutral} onChange={(t) => set((m) => void (m.home = t))} teams={meta.teams} />
          <TeamPanel side="away" team={draft.away} neutral={draft.neutral} onChange={(t) => set((m) => void (m.away = t))} teams={meta.teams} />
        </div>

        <div className="grid-2">
          <PlayersPanel side="home" team={draft.home} onChange={(t) => set((m) => void (m.home = t))} />
          <PlayersPanel side="away" team={draft.away} onChange={(t) => set((m) => void (m.away = t))} />
        </div>

        <H2HPanel h2h={draft.h2h} homeName={draft.home.name || "Tuan rumah"} awayName={draft.away.name || "Tim tamu"} onChange={(h) => set((m) => void (m.h2h = h))} />

        <div className="grid-2">
          <Panel title="Wasit" sub="opsional — memengaruhi kartu & penalti">
            <div className="field-grid">
              <TextField id="ref-name" label="Nama" value={draft.referee.name} onChange={(v) => set((m) => void (m.referee.name = v))} />
              <NumField id="ref-y" label="Kuning / laga" value={draft.referee.yellowPg} onChange={(v) => set((m) => void (m.referee.yellowPg = v))} />
              <NumField id="ref-r" label="Merah / laga" value={draft.referee.redPg} onChange={(v) => set((m) => void (m.referee.redPg = v))} />
              <NumField id="ref-p" label="Penalti / laga" value={draft.referee.pensPg} onChange={(v) => set((m) => void (m.referee.pensPg = v))} />
            </div>
          </Panel>
          <Panel title="Odds bandar" sub="desimal — untuk mendeteksi value bet">
            <div className="field-grid">
              <NumField id="o-h" label="1 (tuan rumah)" value={draft.odds.home} onChange={(v) => set((m) => void (m.odds.home = v))} />
              <NumField id="o-d" label="X (seri)" value={draft.odds.draw} onChange={(v) => set((m) => void (m.odds.draw = v))} />
              <NumField id="o-a" label="2 (tim tamu)" value={draft.odds.away} onChange={(v) => set((m) => void (m.odds.away = v))} />
              <NumField id="o-o" label="Over 2.5" value={draft.odds.over25} onChange={(v) => set((m) => void (m.odds.over25 = v))} />
              <NumField id="o-u" label="Under 2.5" value={draft.odds.under25} onChange={(v) => set((m) => void (m.odds.under25 = v))} />
              <NumField id="o-by" label="BTTS Ya" value={draft.odds.bttsYes} onChange={(v) => set((m) => void (m.odds.bttsYes = v))} />
              <NumField id="o-bn" label="BTTS Tidak" value={draft.odds.bttsNo} onChange={(v) => set((m) => void (m.odds.bttsNo = v))} />
              <NumField id="o-al" label="Garis AH tuan rumah" value={draft.odds.ahLine} onChange={(v) => set((m) => void (m.odds.ahLine = v))} hint="mis. -0.5, -0.25, 0.25" />
              <NumField id="o-ah" label="Odds AH tuan rumah" value={draft.odds.ahHome} onChange={(v) => set((m) => void (m.odds.ahHome = v))} />
              <NumField id="o-aa" label="Odds AH tim tamu" value={draft.odds.ahAway} onChange={(v) => set((m) => void (m.odds.ahAway = v))} />
            </div>
            <p className="muted tiny" style={{ marginTop: 8 }}>Punya odds Indo/Malay/HK? Ubah ke desimal di menu Alat.</p>
          </Panel>
        </div>

        <Panel title="Catatan & konteks" sub="berita tim, rotasi, cuaca, jadwal padat">
          <textarea id="notes" className="input" value={draft.notes} onChange={(e) => set((m) => void (m.notes = e.target.value))} placeholder="Mis.: kapten cedera sejak pekan lalu; tim tamu main Kamis di Eropa." />
        </Panel>
        </>
      )}

      <div className="panel runbar">
        <div className="row-between">
          <div className="grow" style={{ minWidth: 110 }}>
            <div className="row-between small"><span className="dim">Kelengkapan data</span><b className="num">{pct(completeness)}</b></div>
            <Meter value={completeness} color={completeness >= 0.7 ? "var(--good)" : completeness >= 0.4 ? "var(--warn)" : "var(--bad)"} />
          </div>
          <button className="btn btn-primary btn-lg" type="button" onClick={run} disabled={!canRun}>
            {editing && !editing.demo ? "Simpan & hitung ulang" : "Hitung prediksi"} <IChevron />
          </button>
        </div>
      </div>
    </div>
  );
}

const LEVELS = [{ v: 0, label: "0" }, { v: 1, label: "1" }, { v: 2, label: "2" }, { v: 3, label: "3" }];

const RATING_OPTS = [
  { v: 0, label: "?" },
  { v: 1, label: "1" },
  { v: 2, label: "2" },
  { v: 3, label: "3" },
  { v: 4, label: "4" },
  { v: 5, label: "5" },
];
const RATING_WORD = ["belum dinilai", "sangat lemah", "lemah", "rata-rata", "kuat", "sangat kuat"];

function RatingField({ side, team, onChange }: { side: Side; team: TeamInput; onChange: (t: TeamInput) => void }) {
  const r = team.rating ?? 0;
  return (
    <div className="field">
      <span className="lbl">Penilaian kekuatan Anda (1 = sangat lemah, 5 = sangat kuat)</span>
      <div className="row">
        <Seg<number> label={`Kekuatan ${side === "home" ? "tuan rumah" : "tim tamu"}`} value={r} options={RATING_OPTS} onChange={(v) => onChange({ ...team, rating: v === 0 ? null : v })} />
        <span className="small dim">{RATING_WORD[r]}</span>
      </div>
      <span className="hint">Opsional. Model mempelajari seberapa akurat penilaian Anda dari hasil nyata.</span>
    </div>
  );
}

function SavedTeamPicker({ teams, onPick }: { teams: Record<string, { name: string; team: TeamInput }>; onPick: (t: TeamInput) => void }) {
  const saved = Object.keys(teams).sort();
  if (!saved.length) return null;
  return (
    <select className="input" style={{ width: "auto", maxWidth: 200, padding: "6px 8px", fontSize: 13 }} aria-label="Muat tim tersimpan" value="" onChange={(e) => {
      const t = teams[e.target.value];
      if (t) onPick({ ...emptyTeam(), ...JSON.parse(JSON.stringify(t.team)) });
    }}>
      <option value="">Muat tim tersimpan…</option>
      {saved.map((n) => <option key={n} value={n}>{n}</option>)}
    </select>
  );
}

/** Kartu tim ringkas untuk mode cepat: hanya nama yang wajib. */
function QuickTeam({ side, team, onChange, teams }: { side: Side; team: TeamInput; onChange: (t: TeamInput) => void; teams: Record<string, { name: string; team: TeamInput }> }) {
  const upd = (patch: Partial<TeamInput>) => onChange({ ...team, ...patch });
  return (
    <section className="panel" style={{ borderTop: `3px solid ${side === "home" ? "var(--home)" : "var(--away)"}` }}>
      <div className="panel-head">
        <span className={`chip ${side}`}>{side === "home" ? "Tuan rumah" : "Tim tamu"}</span>
        <SavedTeamPicker teams={teams} onPick={onChange} />
      </div>
      <div className="stack">
        <TextField id={`q-${side}-name`} label="Nama tim (wajib)" value={team.name} onChange={(v) => upd({ name: v })} placeholder={side === "home" ? "mis. Persib" : "mis. Persija"} />
        <div className="field-grid">
          <NumField id={`q-${side}-pos`} label="Posisi klasemen" value={team.position} onChange={(v) => upd({ position: v })} />
          <NumField id={`q-${side}-fgf`} label="Gol 5 laga terakhir" value={team.formGF} onChange={(v) => upd({ formGF: v })} hint="total dicetak" />
          <NumField id={`q-${side}-fga`} label="Kebobolan 5 laga" value={team.formGA} onChange={(v) => upd({ formGA: v })} hint="total" />
        </div>
        <div className="field">
          <label htmlFor={`q-${side}-form`}>Form terakhir (terbaru di kiri)</label>
          <div className="row">
            <input id={`q-${side}-form`} className="input num" style={{ maxWidth: 170 }} value={team.form} placeholder="WWDLW / MMSKM" onChange={(e) => upd({ form: e.target.value.toUpperCase().replace(/[^WDLMSK]/g, "").slice(0, 10) })} />
            <FormLetters form={team.form} />
          </div>
        </div>
        <RatingField side={side} team={team} onChange={onChange} />
        <div className="row"><span className="small grow">Pemain penting absen (serang)</span><Seg label="Absen lini serang" value={team.absAttack} options={LEVELS} onChange={(v) => upd({ absAttack: v })} /></div>
        <div className="row"><span className="small grow">Pemain penting absen (bertahan)</span><Seg label="Absen lini belakang" value={team.absDefense} options={LEVELS} onChange={(v) => upd({ absDefense: v })} /></div>
      </div>
    </section>
  );
}

function TeamPanel({ side, team, neutral, onChange, teams }: { side: Side; team: TeamInput; neutral: boolean; onChange: (t: TeamInput) => void; teams: Record<string, { name: string; team: TeamInput }> }) {
  const upd = (patch: Partial<TeamInput>) => onChange({ ...team, ...patch });
  const groups = teamGroups(side).filter((g) => !(neutral && g.venue));
  const saved = Object.keys(teams).sort();
  return (
    <section className="panel" style={{ borderTop: `3px solid ${side === "home" ? "var(--home)" : "var(--away)"}` }}>
      <div className="panel-head">
        <div className="panel-title">
          <span className={`chip ${side}`}>{side === "home" ? "Tuan rumah" : "Tim tamu"}</span>
        </div>
        {saved.length > 0 && (
          <select className="input" style={{ width: "auto", maxWidth: 200, padding: "6px 8px", fontSize: 13 }} aria-label="Muat tim tersimpan" value="" onChange={(e) => {
            const t = teams[e.target.value];
            if (t) onChange({ ...emptyTeam(), ...JSON.parse(JSON.stringify(t.team)) });
          }}>
            <option value="">Muat tim tersimpan…</option>
            {saved.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        )}
      </div>
      <div className="stack">
        <TextField id={`${side}-name`} label="Nama tim" value={team.name} onChange={(v) => upd({ name: v })} placeholder={side === "home" ? "mis. Persib" : "mis. Persija"} />
        <div className="field">
          <label htmlFor={`${side}-form`}>Form terakhir (terbaru di kiri)</label>
          <div className="row">
            <input id={`${side}-form`} className="input num" style={{ maxWidth: 170 }} value={team.form} placeholder="WWDLW / MMSKM" onChange={(e) => upd({ form: e.target.value.toUpperCase().replace(/[^WDLMSK]/g, "").slice(0, 10) })} />
            <FormLetters form={team.form} />
          </div>
        </div>
        <RatingField side={side} team={team} onChange={onChange} />
        {groups.map((g, gi) => (
          <details key={g.id} className="fold" open={gi < 3}>
            <summary><b>{g.title}</b><span className="caret"><IChevron width={16} height={16} /></span></summary>
            <div className="fold-body">
              <p className="muted small" style={{ marginBottom: 10 }}>{g.desc}</p>
              <div className="field-grid">
                {g.fields.map((f) => (
                  <NumField key={f.key} id={`${side}-${f.key}`} label={f.label} unit={f.unit} hint={f.hint} value={team[f.key] as number | null} onChange={(v) => upd({ [f.key]: v } as Partial<TeamInput>)} />
                ))}
              </div>
            </div>
          </details>
        ))}
        <details className="fold">
          <summary><b>Konteks: absen, motivasi, istirahat</b><span className="caret"><IChevron width={16} height={16} /></span></summary>
          <div className="fold-body stack">
            <p className="muted small">Untuk absen yang tidak tercatat di daftar pemain. 0 = tidak ada, 3 = sangat parah.</p>
            <div className="row"><span className="small grow">Absen lini serang</span><Seg label="Absen lini serang" value={team.absAttack} options={LEVELS} onChange={(v) => upd({ absAttack: v })} /></div>
            <div className="row"><span className="small grow">Absen lini belakang</span><Seg label="Absen lini belakang" value={team.absDefense} options={LEVELS} onChange={(v) => upd({ absDefense: v })} /></div>
            <div className="row"><span className="small grow">Motivasi</span><Seg label="Motivasi" value={team.motivation} options={[{ v: -2, label: "−2" }, { v: -1, label: "−1" }, { v: 0, label: "0" }, { v: 1, label: "+1" }, { v: 2, label: "+2" }]} onChange={(v) => upd({ motivation: v })} /></div>
            <NumField id={`${side}-rest`} label="Hari istirahat sejak laga terakhir" value={team.restDays} onChange={(v) => upd({ restDays: v })} />
          </div>
        </details>
      </div>
    </section>
  );
}

function PlayersPanel({ side, team, onChange }: { side: Side; team: TeamInput; onChange: (t: TeamInput) => void }) {
  const setP = (i: number, patch: Partial<Player>) => onChange({ ...team, players: team.players.map((p, j) => (j === i ? { ...p, ...patch } : p)) });
  const numIn = (v: string) => (v.trim() === "" ? null : Number.isFinite(parseFloat(v.replace(",", "."))) ? parseFloat(v.replace(",", ".")) : null);
  return (
    <Panel title={`Pemain ${team.name || (side === "home" ? "tuan rumah" : "tim tamu")}`} sub="untuk prediksi pencetak gol" right={
      <button className="btn btn-sm" type="button" onClick={() => onChange({ ...team, players: [...team.players, emptyPlayer()] })}><IPlus /> Pemain</button>
    }>
      {team.players.length === 0 ? (
        <p className="muted small">Belum ada pemain. Tambahkan penyerang & pencetak gol utama (gol, jumlah laga, xG bila ada) serta pemain yang cedera.</p>
      ) : (
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Nama</th><th>Posisi</th><th className="r">Gol</th><th className="r">Laga</th><th className="r">xG</th><th>Pen</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {team.players.map((p, i) => (
                <tr key={p.id}>
                  <td style={{ minWidth: 120 }}><input className="input" aria-label="Nama pemain" value={p.name} onChange={(e) => setP(i, { name: e.target.value })} /></td>
                  <td>
                    <select className="input" aria-label="Posisi" value={p.pos} onChange={(e) => setP(i, { pos: e.target.value as Player["pos"] })}>
                      <option value="FWD">FWD</option><option value="MID">MID</option><option value="DEF">DEF</option><option value="GK">GK</option>
                    </select>
                  </td>
                  <td><input className="input num" style={{ width: 58 }} inputMode="decimal" aria-label="Gol" value={p.goals ?? ""} onChange={(e) => setP(i, { goals: numIn(e.target.value) })} /></td>
                  <td><input className="input num" style={{ width: 58 }} inputMode="decimal" aria-label="Laga" value={p.apps ?? ""} onChange={(e) => setP(i, { apps: numIn(e.target.value) })} /></td>
                  <td><input className="input num" style={{ width: 64 }} inputMode="decimal" aria-label="xG" value={p.xg ?? ""} onChange={(e) => setP(i, { xg: numIn(e.target.value) })} /></td>
                  <td><input type="checkbox" aria-label="Algojo penalti" checked={p.penTaker} onChange={(e) => setP(i, { penTaker: e.target.checked })} /></td>
                  <td>
                    <select className="input" aria-label="Status" value={p.status} onChange={(e) => setP(i, { status: e.target.value as Player["status"] })}>
                      <option value="fit">Fit</option><option value="doubt">Diragukan</option><option value="out">Absen</option>
                    </select>
                  </td>
                  <td><button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label={`Hapus ${p.name || "pemain"}`} onClick={() => onChange({ ...team, players: team.players.filter((_, j) => j !== i) })}><ITrash /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

function H2HPanel({ h2h, homeName, awayName, onChange }: { h2h: H2HMatch[]; homeName: string; awayName: string; onChange: (h: H2HMatch[]) => void }) {
  const [quick, setQuick] = useState("");
  return (
    <Panel title="Head-to-head" sub={`skor ditulis gol ${homeName} - gol ${awayName}`} right={
      <button className="btn btn-sm" type="button" onClick={() => onChange([...h2h, { hg: 0, ag: 0, homeAtHome: true }])}><IPlus /> Laga</button>
    }>
      <div className="row" style={{ marginBottom: 12 }}>
        <input className="input grow" style={{ maxWidth: 360 }} aria-label="Tempel skor H2H" placeholder="Tempel cepat: 2-1, 1-1, 0-2" value={quick} onChange={(e) => setQuick(e.target.value)} />
        <button className="btn btn-sm" type="button" onClick={() => {
          const ms = [...quick.matchAll(/(\d+)\s*[-:–]\s*(\d+)/g)].map((m) => ({ hg: +m[1], ag: +m[2], homeAtHome: true }));
          if (ms.length) {
            onChange([...h2h, ...ms]);
            setQuick("");
          }
        }}>Tambah</button>
      </div>
      {h2h.length === 0 ? <p className="muted small">Belum ada data H2H. H2H diberi bobot kecil, karena komposisi skuad biasanya sudah berubah.</p> : (
        <div className="stack-sm">
          {h2h.map((m, i) => (
            <div key={i} className="row">
              <span className="num muted small" style={{ width: 22 }}>{i + 1}.</span>
              <input className="input num" style={{ width: 60 }} aria-label={`Gol ${homeName}`} inputMode="numeric" value={m.hg} onChange={(e) => onChange(h2h.map((x, j) => (j === i ? { ...x, hg: Math.max(0, parseInt(e.target.value) || 0) } : x)))} />
              <span>-</span>
              <input className="input num" style={{ width: 60 }} aria-label={`Gol ${awayName}`} inputMode="numeric" value={m.ag} onChange={(e) => onChange(h2h.map((x, j) => (j === i ? { ...x, ag: Math.max(0, parseInt(e.target.value) || 0) } : x)))} />
              <label className="check small"><input type="checkbox" checked={m.homeAtHome} onChange={(e) => onChange(h2h.map((x, j) => (j === i ? { ...x, homeAtHome: e.target.checked } : x)))} /> {homeName} main di kandang</label>
              <button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label="Hapus laga H2H" onClick={() => onChange(h2h.filter((_, j) => j !== i))}><ITrash /></button>
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}
