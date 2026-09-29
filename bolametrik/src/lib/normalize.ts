// Membuat balasan AI tahan banting: memperbaiki JSON yang terpotong dan
// menyeragamkan bentuk data yang berbeda-beda antar model (Gemini, Claude, dll).

/* eslint-disable @typescript-eslint/no-explicit-any */

import type { Extraction } from "./ai";

/**
 * Coba perbaiki JSON yang terpotong (mis. model berhenti karena batas token):
 * potong di batas nilai terakhir yang utuh lalu tutup semua kurung yang terbuka.
 */
export function repairJson(text: string): unknown | undefined {
  const start = text.search(/[[{]/);
  if (start < 0) return undefined;
  const s = text.slice(start);
  // Kumpulkan posisi potong yang aman beserta tumpukan kurung saat itu
  const cuts: { at: number; stack: string }[] = [];
  const stack: string[] = [];
  let inStr = false, esc = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === "{" || c === "[") stack.push(c === "{" ? "}" : "]");
    else if (c === "}" || c === "]") {
      stack.pop();
      cuts.push({ at: i + 1, stack: stack.slice().reverse().join("") });
      if (!stack.length) break;
    } else if (c === ",") cuts.push({ at: i, stack: stack.slice().reverse().join("") });
  }
  for (let k = cuts.length - 1; k >= 0 && k >= cuts.length - 400; k--) {
    const { at, stack: close } = cuts[k];
    try {
      return JSON.parse(s.slice(0, at) + close);
    } catch {
      /* coba posisi sebelumnya */
    }
  }
  return undefined;
}

const isObj = (x: unknown): x is Record<string, any> => !!x && typeof x === "object" && !Array.isArray(x);

export function toArray(x: unknown): any[] {
  if (Array.isArray(x)) return x;
  if (isObj(x)) return Object.values(x);
  return [];
}

function str(x: unknown): string | null {
  if (typeof x === "string") return x.trim() || null;
  if (typeof x === "number") return String(x);
  return null;
}

const HOME_KEYS = ["home", "homeTeam", "home_team", "tuanRumah", "tuan_rumah", "team1", "teamHome", "host"];
const AWAY_KEYS = ["away", "awayTeam", "away_team", "tamu", "timTamu", "tim_tamu", "team2", "teamAway", "visitor"];
const NEST_KEYS = ["stats", "statistics", "statistik", "season", "overall", "keseluruhan", "advanced", "data", "values"];

function pick(o: Record<string, any>, keys: string[]) {
  for (const k of keys) if (o[k] !== undefined && o[k] !== null) return o[k];
  return undefined;
}

function normTeam(t: unknown): Record<string, any> | undefined {
  if (!isObj(t)) return undefined;
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(t)) if (v === null || typeof v !== "object") out[k] = v;
  // Statistik yang dibungkus satu tingkat lebih dalam (mis. {stats: {...}})
  for (const nk of NEST_KEYS) {
    const inner = t[nk];
    if (isObj(inner)) for (const [k, v] of Object.entries(inner)) if ((v === null || typeof v !== "object") && out[k] === undefined) out[k] = v;
  }
  if (Array.isArray(t.form)) out.form = t.form.map(String).join("");
  const players = toArray(pick(t, ["players", "keyPlayers", "key_players", "pemain", "squad"]))
    .map((p): Record<string, any> | null => (typeof p === "string" ? { name: p } : isObj(p) ? { ...p, name: str(p.name ?? p.player ?? p.nama) } : null))
    .filter((p): p is Record<string, any> => !!p && typeof p.name === "string" && p.name.length > 0);
  out.players = players;
  return out;
}

function normH2H(x: unknown): any[] {
  return toArray(x)
    .map((m) => {
      if (typeof m === "string") {
        const mm = m.match(/(\d+)\s*[-:–]\s*(\d+)/);
        return mm ? { hg: +mm[1], ag: +mm[2], homeAtHome: true } : null;
      }
      if (!isObj(m)) return null;
      if (m.hg === undefined && typeof m.score === "string") {
        const mm = m.score.match(/(\d+)\s*[-:–]\s*(\d+)/);
        if (mm) return { ...m, hg: +mm[1], ag: +mm[2] };
      }
      return m;
    })
    .filter(Boolean);
}

function normOne(x: unknown, depth = 0): Extraction[] {
  if (Array.isArray(x)) return x.flatMap((y) => normOne(y, depth + 1));
  if (!isObj(x) || depth > 3) return [];
  let home = pick(x, HOME_KEYS);
  let away = pick(x, AWAY_KEYS);
  if (!isObj(home) && !isObj(away)) {
    // Dibungkus: {data: {...}}, {result: {...}}, {match: {...}}
    for (const k of ["data", "result", "extraction", "match", "output", "response"]) if (x[k]) return normOne(x[k], depth + 1);
    // Bentuk {teams: [tuanRumah, tamu]}
    if (Array.isArray(x.teams) && x.teams.length >= 2) {
      home = x.teams[0];
      away = x.teams[1];
    }
  }
  const notes = Array.isArray(x.notes) ? x.notes.map(String).join("\n") : str(x.notes) ?? str(x.catatan) ?? "";
  const unclear = Array.isArray(x.unclear) ? x.unclear.map(String) : str(x.unclear) ? [str(x.unclear)!] : [];
  const ref = isObj(x.referee) ? x.referee : typeof x.referee === "string" ? { name: x.referee } : {};
  return [
    {
      homeName: str(x.homeName ?? x.home_name ?? (isObj(home) ? home.name ?? home.team : typeof home === "string" ? home : null)),
      awayName: str(x.awayName ?? x.away_name ?? (isObj(away) ? away.name ?? away.team : typeof away === "string" ? away : null)),
      league: str(x.league ?? x.competition),
      kickoff: str(x.kickoff ?? x.date),
      home: normTeam(home) as Extraction["home"],
      away: normTeam(away) as Extraction["away"],
      h2h: normH2H(x.h2h ?? x.headToHead ?? x.head_to_head),
      referee: ref,
      odds: isObj(x.odds) ? x.odds : {},
      notes,
      unclear,
    },
  ];
}

/** Ubah balasan AI apa pun menjadi daftar ekstraksi berbentuk seragam. */
export function normalizeExtractions(raw: unknown): Extraction[] {
  return normOne(raw);
}
