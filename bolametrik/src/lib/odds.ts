// Utilitas odds: konversi format, margin bandar, Kelly, parlay, notasi handicap.

export type OddsFormat = "decimal" | "indo" | "malay" | "hk" | "us" | "frac";

export const ODDS_FORMAT_LABEL: Record<OddsFormat, string> = {
  decimal: "Desimal (Euro)",
  indo: "Indo",
  malay: "Malay",
  hk: "Hong Kong",
  us: "Amerika (US)",
  frac: "Pecahan (UK)",
};

export function toDecimal(value: string, fmt: OddsFormat): number | null {
  const v = value.trim();
  if (!v) return null;
  if (fmt === "frac") {
    const m = v.match(/^(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)$/);
    if (!m) return null;
    const d = 1 + parseFloat(m[1]) / parseFloat(m[2]);
    return Number.isFinite(d) ? d : null;
  }
  const x = parseFloat(v.replace(",", "."));
  if (!Number.isFinite(x)) return null;
  switch (fmt) {
    case "decimal":
      return x > 1 ? x : null;
    case "hk":
      return x > 0 ? x + 1 : null;
    case "indo":
      if (x >= 1) return x + 1;
      if (x <= -1) return 1 + 1 / Math.abs(x);
      return null;
    case "malay":
      if (x > 0 && x <= 1) return x + 1;
      if (x < 0 && x >= -1) return 1 + 1 / Math.abs(x);
      return null;
    case "us":
      if (x >= 100) return 1 + x / 100;
      if (x <= -100) return 1 + 100 / Math.abs(x);
      return null;
  }
  return null;
}

function gcd(a: number, b: number): number {
  return b ? gcd(b, a % b) : a;
}

export function fromDecimal(d: number, fmt: OddsFormat): string {
  if (!Number.isFinite(d) || d <= 1) return "—";
  const b = d - 1;
  switch (fmt) {
    case "decimal":
      return d.toFixed(2);
    case "hk":
      return b.toFixed(2);
    case "indo":
      return b >= 1 ? `+${b.toFixed(2)}` : `-${(1 / b).toFixed(2)}`;
    case "malay":
      return b <= 1 ? b.toFixed(2) : `-${(1 / b).toFixed(2)}`;
    case "us":
      return b >= 1 ? `+${Math.round(b * 100)}` : `-${Math.round(100 / b)}`;
    case "frac": {
      const den = 100;
      const num = Math.round(b * den);
      const g = gcd(num, den);
      return `${num / g}/${den / g}`;
    }
  }
}

export function implied(d: number) {
  return d > 1 ? 1 / d : 0;
}

/** Hapus margin bandar secara proporsional. */
export function removeMargin(odds: number[]): number[] {
  const inv = odds.map((o) => 1 / o);
  const s = inv.reduce((a, b) => a + b, 0);
  return inv.map((x) => x / s);
}

export function overround(odds: number[]) {
  return odds.reduce((s, o) => s + 1 / o, 0) - 1;
}

/** Fraksi Kelly optimal untuk taruhan biner (menang/kalah). */
export function kelly(p: number, d: number) {
  const b = d - 1;
  if (b <= 0) return 0;
  return Math.max(0, (b * p - (1 - p)) / b);
}

export function fairOdds(p: number) {
  return p > 0 ? 1 / p : Infinity;
}

function frac(x: number): string {
  const whole = Math.floor(x + 1e-9);
  const r = x - whole;
  const half = Math.abs(r - 0.5) < 1e-6;
  if (whole === 0) return half ? "½" : "0";
  return half ? `${whole}½` : `${whole}`;
}

/** Notasi handicap gaya Indonesia, mis. -0.75 → "½-1". */
export function ahIndo(line: number): string {
  const a = Math.abs(line);
  const q = Math.round(a * 4);
  if (q % 2 === 0) return frac(a);
  return `${frac((q - 1) / 4)}-${frac((q + 1) / 4)}`;
}

export function fmtLine(line: number): string {
  if (Math.abs(line) < 1e-9) return "0";
  const s = Math.abs(line).toFixed(2).replace(/0$/, "").replace(/\.0$/, "");
  return (line > 0 ? "+" : "−") + s;
}

export function fmtTotalLine(line: number): string {
  return line.toFixed(2).replace(/0$/, "").replace(/\.0$/, "");
}
