import { useState, type ReactNode } from "react";
import type { ConfLabel } from "../lib/insights";
import { parseForm } from "../lib/model";

export const pct = (p: number | null | undefined, d = 0) => (p === null || p === undefined || !Number.isFinite(p) ? "—" : `${(p * 100).toFixed(d)}%`);
export const odds2 = (o: number) => (Number.isFinite(o) && o < 100 ? o.toFixed(2) : "—");

export function confClass(c: ConfLabel | null | undefined): string {
  switch (c) {
    case "SANGAT TINGGI":
      return "good";
    case "TINGGI":
      return "okay";
    case "SEDANG":
      return "warn";
    case "RENDAH":
    case "SANGAT RENDAH":
      return "bad";
    default:
      return "";
  }
}

export function ConfChip({ c }: { c: ConfLabel | null | undefined }) {
  if (!c) return null;
  return <span className={`chip ${confClass(c)}`}>{c}</span>;
}

export function Panel({ title, sub, right, children, className = "" }: { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`panel ${className}`}>
      {(title || right) && (
        <div className="panel-head">
          <div className="panel-title">
            {title && <h3>{title}</h3>}
            {sub && <span className="muted small">{sub}</span>}
          </div>
          {right}
        </div>
      )}
      {children}
    </section>
  );
}

export function NumField({ id, label, value, onChange, hint, step = "any", unit }: { id: string; label: string; value: number | null; onChange: (v: number | null) => void; hint?: string; step?: string; unit?: string }) {
  const [text, setText] = useState<string | null>(null);
  const shown = text ?? (value === null || value === undefined ? "" : String(value));
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {unit ? ` (${unit})` : ""}
      </label>
      <input
        id={id}
        className="input num"
        inputMode="decimal"
        type="text"
        value={shown}
        step={step}
        placeholder="—"
        onChange={(e) => {
          const t = e.target.value.replace(",", ".");
          setText(t);
          if (t.trim() === "") onChange(null);
          else {
            const x = parseFloat(t);
            if (Number.isFinite(x)) onChange(x);
          }
        }}
        onBlur={() => setText(null)}
      />
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

export function TextField({ id, label, value, onChange, placeholder, hint }: { id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string; hint?: string }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} className="input" value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

export function Seg<T extends string | number>({ value, options, onChange, label }: { value: T; options: { v: T; label: string }[]; onChange: (v: T) => void; label?: string }) {
  return (
    <div className="seg" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button key={String(o.v)} type="button" role="radio" aria-checked={value === o.v} className={value === o.v ? "on" : ""} onClick={() => onChange(o.v)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function FormLetters({ form }: { form: string }) {
  const f = parseForm(form);
  if (!f.n) return <span className="muted small">—</span>;
  return (
    <span className="form-letters" aria-label={`Form ${f.letters}`}>
      {f.letters.split("").map((c, i) => (
        <span key={i} className={`fl ${c}`}>
          {c === "W" ? "M" : c === "D" ? "S" : "K"}
        </span>
      ))}
    </span>
  );
}

export function ProbBar({ home, draw, away, homeName, awayName }: { home: number; draw: number; away: number; homeName: string; awayName: string }) {
  const seg = (p: number) => ({ flexBasis: `${Math.max(p * 100, 0.5)}%`, flexGrow: 0, flexShrink: 1 });
  return (
    <div>
      <div className="pbar" role="img" aria-label={`${homeName} ${pct(home)}, seri ${pct(draw)}, ${awayName} ${pct(away)}`}>
        <div className="h" style={seg(home)} title={`${homeName} ${pct(home, 1)}`}>{home > 0.09 ? pct(home) : ""}</div>
        <div className="d" style={seg(draw)} title={`Seri ${pct(draw, 1)}`}>{draw > 0.09 ? pct(draw) : ""}</div>
        <div className="a" style={seg(away)} title={`${awayName} ${pct(away, 1)}`}>{away > 0.09 ? pct(away) : ""}</div>
      </div>
      <div className="pbar-legend">
        <span className="key"><i style={{ background: "var(--home)" }} />{homeName} <b className="num">{pct(home, 1)}</b></span>
        <span className="key"><i style={{ background: "var(--draw)" }} />Seri <b className="num">{pct(draw, 1)}</b></span>
        <span className="key"><i style={{ background: "var(--away)" }} />{awayName} <b className="num">{pct(away, 1)}</b></span>
      </div>
    </div>
  );
}

export function Meter({ value, color }: { value: number; color?: string }) {
  return (
    <div className="meter" role="meter" aria-valuemin={0} aria-valuemax={1} aria-valuenow={value}>
      <div style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%`, background: color }} />
    </div>
  );
}

export function Tabs<T extends string>({ value, tabs, onChange }: { value: T; tabs: { v: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.v} role="tab" aria-selected={value === t.v} className={value === t.v ? "on" : ""} onClick={() => onChange(t.v)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function Modal({ children, onClose, label }: { children: ReactNode; onClose: () => void; label: string }) {
  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={label} onClick={(e) => e.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function fmtDate(s: string) {
  if (!s) return "—";
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  return d.toLocaleString("id-ID", { weekday: "short", day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}
