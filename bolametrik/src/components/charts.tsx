import { useState } from "react";
import { pct } from "./ui";

/** Heatmap matriks skor: satu warna berurutan (kuning lampu stadion), gelap → terang. */
export function ScoreHeatmap({ m, size = 6, homeName, awayName, mark }: { m: number[][]; size?: number; homeName: string; awayName: string; mark?: [number, number] }) {
  let max = 0;
  for (let i = 0; i < size; i++) for (let j = 0; j < size; j++) max = Math.max(max, m[i][j]);
  const cols = `28px repeat(${size}, minmax(0, 1fr))`;
  return (
    <div>
      <div className="row-between small" style={{ marginBottom: 6 }}>
        <span className="muted">Baris: gol {homeName}</span>
        <span className="muted">Kolom: gol {awayName}</span>
      </div>
      <div className="heat" style={{ gridTemplateColumns: cols }} role="table" aria-label="Peluang tiap skor">
        <div />
        {Array.from({ length: size }, (_, j) => (
          <div key={`h${j}`} className="hdr">{j}</div>
        ))}
        {Array.from({ length: size }, (_, i) => (
          <FragmentRow key={i} i={i} size={size} m={m} max={max} mark={mark} homeName={homeName} awayName={awayName} />
        ))}
      </div>
    </div>
  );
}

function FragmentRow({ i, size, m, max, mark, homeName, awayName }: { i: number; size: number; m: number[][]; max: number; mark?: [number, number]; homeName: string; awayName: string }) {
  return (
    <>
      <div className="hdr">{i}</div>
      {Array.from({ length: size }, (_, j) => {
        const p = m[i][j];
        const t = max > 0 ? p / max : 0;
        const bg = `rgba(255, 201, 64, ${0.06 + 0.86 * t})`;
        const dark = t > 0.55;
        const isMark = mark && mark[0] === i && mark[1] === j;
        return (
          <div key={j} className={`cell${isMark ? " top" : ""}`} style={{ background: bg, color: dark ? "#1d1500" : "var(--chalk)" }} title={`${homeName} ${i} - ${j} ${awayName}: ${pct(p, 1)}`}>
            {p >= 0.01 ? Math.round(p * 100) : ""}
          </div>
        );
      })}
    </>
  );
}

/** Batang vertikal untuk distribusi (mis. total gol). */
export function DistBars({ data, labels, color = "var(--flood)", highlight }: { data: number[]; labels: string[]; color?: string; highlight?: number }) {
  const max = Math.max(...data, 0.0001);
  const H = 120;
  return (
    <div style={{ display: "grid", gridTemplateColumns: `repeat(${data.length}, minmax(0, 1fr))`, gap: 6, alignItems: "end" }}>
      {data.map((v, i) => (
        <div key={i} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }} title={`${labels[i]}: ${pct(v, 1)}`}>
          <span className="num tiny dim">{pct(v)}</span>
          <div style={{ width: "100%", maxWidth: 44, height: Math.max(2, (v / max) * H), background: color, opacity: highlight === undefined || highlight === i ? 1 : 0.55, borderRadius: "4px 4px 0 0" }} />
          <span className="num small">{labels[i]}</span>
        </div>
      ))}
    </div>
  );
}

/** Batang horizontal berlabel. */
export function HBars({ rows, max, color = "var(--flood)", format = (v: number) => v.toFixed(2) }: { rows: { label: string; value: number; note?: string; color?: string }[]; max?: number; color?: string; format?: (v: number) => string }) {
  const mx = max ?? Math.max(...rows.map((r) => r.value), 0.0001);
  return (
    <div className="stack-sm">
      {rows.map((r) => (
        <div key={r.label} style={{ display: "grid", gridTemplateColumns: "minmax(0, 190px) minmax(0, 1fr) 64px", gap: 10, alignItems: "center" }}>
          <span className="small" style={{ overflowWrap: "anywhere" }}>{r.label}</span>
          <div style={{ height: 10, background: "var(--turf-3)", borderRadius: 99 }} title={r.note ?? `${r.label}: ${format(r.value)}`}>
            <div style={{ width: `${Math.min(1, r.value / mx) * 100}%`, height: "100%", background: r.color ?? color, borderRadius: 99 }} />
          </div>
          <span className="num small" style={{ textAlign: "right" }}>{format(r.value)}</span>
        </div>
      ))}
    </div>
  );
}

/** Garis tren (mis. Brier bergulir) dengan crosshair + tooltip. */
export function LineChart({ series, height = 180, yMax, yLabel }: { series: { name: string; color: string; values: number[]; dashed?: boolean }[]; height?: number; yMax?: number; yLabel?: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const n = Math.max(...series.map((s) => s.values.length), 0);
  if (n < 2) return <p className="muted small">Butuh minimal 2 laga selesai untuk grafik tren.</p>;
  const W = 600, H = height, padL = 36, padR = 12, padT = 10, padB = 24;
  const all = series.flatMap((s) => s.values);
  const top = yMax ?? Math.max(...all) * 1.1;
  const x = (i: number) => padL + (i / (n - 1)) * (W - padL - padR);
  const y = (v: number) => padT + (1 - v / top) * (H - padT - padB);
  const ticks = [0, top / 2, top];
  return (
    <div style={{ position: "relative" }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={yLabel ?? "Grafik tren"} onMouseLeave={() => setHover(null)}
        onMouseMove={(e) => {
          const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          const px = ((e.clientX - r.left) / r.width) * W;
          const i = Math.round(((px - padL) / (W - padL - padR)) * (n - 1));
          setHover(Math.max(0, Math.min(n - 1, i)));
        }}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={padL} x2={W - padR} y1={y(t)} y2={y(t)} stroke="rgba(237,243,236,.08)" />
            <text x={padL - 6} y={y(t) + 4} textAnchor="end" fontSize="10" fill="var(--chalk-3)">{t.toFixed(2)}</text>
          </g>
        ))}
        <text x={padL} y={H - 6} fontSize="10" fill="var(--chalk-3)">laga ke-1</text>
        <text x={W - padR} y={H - 6} fontSize="10" fill="var(--chalk-3)" textAnchor="end">laga ke-{n}</text>
        {series.map((s) => (
          <polyline key={s.name} fill="none" stroke={s.color} strokeWidth="2" strokeDasharray={s.dashed ? "5 4" : undefined} points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(" ")} />
        ))}
        {series.map((s) => s.values.length > 0 && <circle key={`${s.name}-end`} cx={x(s.values.length - 1)} cy={y(s.values[s.values.length - 1])} r="4" fill={s.color} stroke="var(--turf)" strokeWidth="2" />)}
        {hover !== null && (
          <g>
            <line x1={x(hover)} x2={x(hover)} y1={padT} y2={H - padB} stroke="rgba(237,243,236,.35)" />
            {series.map((s) => s.values[hover] !== undefined && <circle key={s.name} cx={x(hover)} cy={y(s.values[hover])} r="4" fill={s.color} stroke="var(--turf)" strokeWidth="2" />)}
          </g>
        )}
      </svg>
      {hover !== null && (
        <div className="small" style={{ position: "absolute", top: 4, left: `${Math.min(70, (x(hover) / W) * 100)}%`, background: "var(--turf-3)", border: "1px solid var(--line-2)", borderRadius: 8, padding: "6px 9px", pointerEvents: "none" }}>
          <div className="muted tiny">Laga ke-{hover + 1}</div>
          {series.map((s) => (
            <div key={s.name} className="key"><i style={{ background: s.color }} />{s.name}: <b className="num">{s.values[hover]?.toFixed(3) ?? "—"}</b></div>
          ))}
        </div>
      )}
      <div className="row small" style={{ marginTop: 6 }}>
        {series.map((s) => (
          <span key={s.name} className="key"><i style={{ background: s.color }} />{s.name}</span>
        ))}
      </div>
    </div>
  );
}

/** Kalibrasi: peluang prediksi vs frekuensi nyata per kelompok. */
export function CalibChart({ bins }: { bins: { bin: string; pred: number; actual: number; n: number }[] }) {
  const W = 320, H = 240, pad = 34;
  const s = (v: number) => pad + v * (W - pad * 2);
  const sy = (v: number) => H - pad - v * (H - pad * 2);
  const pts = bins.filter((b) => b.n > 0);
  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxWidth: 420 }} role="img" aria-label="Grafik kalibrasi">
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line x1={s(0)} x2={s(1)} y1={sy(t)} y2={sy(t)} stroke="rgba(237,243,236,.08)" />
            <text x={pad - 6} y={sy(t) + 4} fontSize="10" textAnchor="end" fill="var(--chalk-3)">{t * 100}%</text>
            <text x={s(t)} y={H - pad + 16} fontSize="10" textAnchor="middle" fill="var(--chalk-3)">{t * 100}%</text>
          </g>
        ))}
        <line x1={s(0)} y1={sy(0)} x2={s(1)} y2={sy(1)} stroke="var(--chalk-3)" strokeDasharray="4 4" />
        <polyline fill="none" stroke="var(--flood)" strokeWidth="2" points={pts.map((b) => `${s(b.pred)},${sy(b.actual)}`).join(" ")} />
        {pts.map((b) => (
          <circle key={b.bin} cx={s(b.pred)} cy={sy(b.actual)} r={Math.min(9, 4 + Math.sqrt(b.n))} fill="var(--flood)" stroke="var(--turf)" strokeWidth="2">
            <title>{`Prediksi ${b.bin}: rata-rata ${pct(b.pred)} → terjadi ${pct(b.actual)} (n=${b.n})`}</title>
          </circle>
        ))}
        <text x={W / 2} y={H - 4} fontSize="10.5" textAnchor="middle" fill="var(--chalk-2)">Peluang yang diprediksi</text>
      </svg>
      <p className="muted small">Titik di garis putus-putus = model terkalibrasi (peluang 60% terjadi ±60% dari waktu).</p>
    </div>
  );
}

/** Pengukur keyakinan setengah lingkaran. */
export function Gauge({ value, label }: { value: number; label: string }) {
  const v = Math.max(0, Math.min(10, value)) / 10;
  const R = 52, cx = 64, cy = 62;
  const ang = Math.PI * (1 - v);
  const x = cx + R * Math.cos(ang), y = cy - R * Math.sin(ang);
  const color = value >= 6.5 ? "var(--good)" : value >= 5 ? "var(--warn)" : "var(--bad)";
  return (
    <svg viewBox="0 0 128 76" width="150" role="img" aria-label={`Keyakinan ${value} dari 10, ${label}`}>
      <path d={`M ${cx - R} ${cy} A ${R} ${R} 0 0 1 ${cx + R} ${cy}`} fill="none" stroke="var(--turf-3)" strokeWidth="10" strokeLinecap="round" />
      <path d={`M ${cx - R} ${cy} A ${R} ${R} 0 0 1 ${x} ${y}`} fill="none" stroke={color} strokeWidth="10" strokeLinecap="round" />
      <text x={cx} y={cy - 6} textAnchor="middle" fontSize="26" fontWeight="900" fill="var(--chalk)" style={{ fontFamily: "var(--display)" }}>{value.toFixed(1)}</text>
      <text x={cx} y={cy + 11} textAnchor="middle" fontSize="9" fill="var(--chalk-3)">DARI 10</text>
    </svg>
  );
}
