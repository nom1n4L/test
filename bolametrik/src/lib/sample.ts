// Data kosong & contoh (tim fiktif, angka ilustrasi — bukan data asli).

import { leagueByKey } from "./leagues";
import type { MatchInput, OddsInput, Player, TeamInput } from "./types";

export function uid() {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}

export function emptyTeam(name = ""): TeamInput {
  return {
    name,
    position: null,
    played: null,
    wins: null,
    draws: null,
    losses: null,
    gf: null,
    ga: null,
    venuePlayed: null,
    venueWins: null,
    venueDraws: null,
    venueLosses: null,
    venueGF: null,
    venueGA: null,
    form: "",
    formGF: null,
    formGA: null,
    xgFor: null,
    xgAgainst: null,
    shotsFor: null,
    sotFor: null,
    shotsAgainst: null,
    sotAgainst: null,
    possession: null,
    cleanSheets: null,
    failedToScore: null,
    fhGFPct: null,
    fhGAPct: null,
    cornersFor: null,
    cornersAgainst: null,
    yellowPg: null,
    redPg: null,
    pensForPg: null,
    pensAgainstPg: null,
    absAttack: 0,
    absDefense: 0,
    motivation: 0,
    restDays: null,
    rating: null,
    players: [],
  };
}

export function emptyOdds(): OddsInput {
  return { home: null, draw: null, away: null, over25: null, under25: null, bttsYes: null, bttsNo: null, ahLine: null, ahHome: null, ahAway: null };
}

export function emptyPlayer(pos: Player["pos"] = "FWD"): Player {
  return { id: uid(), name: "", pos, goals: null, assists: null, apps: null, minutes: null, xg: null, penTaker: false, status: "fit" };
}

function nowLocal(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 86400000);
  d.setMinutes(0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(19)}:${pad(0)}`;
}

export function emptyMatch(leagueKey = "epl"): MatchInput {
  const lg = leagueByKey(leagueKey);
  return {
    leagueKey: lg.key,
    leagueName: lg.name,
    homeAvg: lg.homeAvg,
    awayAvg: lg.awayAvg,
    kickoff: nowLocal(1),
    competition: "league",
    importance: "normal",
    derby: false,
    neutral: false,
    home: emptyTeam(""),
    away: emptyTeam(""),
    h2h: [],
    referee: { name: "", yellowPg: null, redPg: null, pensPg: null },
    odds: emptyOdds(),
    notes: "",
  };
}

const P = (name: string, pos: Player["pos"], goals: number, apps: number, extra: Partial<Player> = {}): Player => ({
  id: uid(),
  name,
  pos,
  goals,
  assists: extra.assists ?? null,
  apps,
  minutes: extra.minutes ?? null,
  xg: extra.xg ?? null,
  penTaker: extra.penTaker ?? false,
  status: extra.status ?? "fit",
});

/** Laga contoh dengan tim FIKTIF untuk memperlihatkan cara kerja aplikasi. */
export function demoMatch(): MatchInput {
  const m = emptyMatch("idn");
  m.kickoff = nowLocal(2);
  m.home = {
    ...emptyTeam("Garuda FC"),
    position: 3, played: 20, wins: 11, draws: 5, losses: 4, gf: 34, ga: 19,
    venuePlayed: 10, venueWins: 7, venueDraws: 2, venueLosses: 1, venueGF: 21, venueGA: 8,
    form: "WWDWL", formGF: 10, formGA: 5,
    xgFor: 1.62, xgAgainst: 1.02, shotsFor: 13.4, sotFor: 5.1, shotsAgainst: 9.8, sotAgainst: 3.4, possession: 55,
    cleanSheets: 7, failedToScore: 3, fhGFPct: 41, fhGAPct: 44,
    cornersFor: 5.8, cornersAgainst: 4.1, yellowPg: 2.1, redPg: 0.1, pensForPg: 0.25, pensAgainstPg: 0.15,
    players: [
      P("R. Pratama", "FWD", 11, 19, { xg: 9.8, penTaker: true, minutes: 1580 }),
      P("A. Siregar", "FWD", 6, 18, { xg: 5.1, minutes: 1310 }),
      P("D. Wibowo", "MID", 5, 20, { xg: 3.9, minutes: 1720 }),
      P("F. Nugroho", "MID", 3, 16, { xg: 2.2, minutes: 1100, status: "doubt" }),
      P("Y. Hakim", "DEF", 2, 20, { xg: 1.4, minutes: 1800 }),
    ],
  };
  m.away = {
    ...emptyTeam("Rajawali United"),
    position: 7, played: 20, wins: 8, draws: 6, losses: 6, gf: 27, ga: 24,
    venuePlayed: 10, venueWins: 3, venueDraws: 3, venueLosses: 4, venueGF: 11, venueGA: 14,
    form: "LWDWW", formGF: 8, formGA: 6,
    xgFor: 1.31, xgAgainst: 1.28, shotsFor: 11.2, sotFor: 4.0, shotsAgainst: 11.9, sotAgainst: 4.3, possession: 48,
    cleanSheets: 4, failedToScore: 5, fhGFPct: 38, fhGAPct: 47,
    cornersFor: 4.6, cornersAgainst: 5.3, yellowPg: 2.6, redPg: 0.15, pensForPg: 0.2, pensAgainstPg: 0.3,
    absDefense: 1,
    players: [
      P("M. Lestaluhu", "FWD", 9, 20, { xg: 7.4, penTaker: true, minutes: 1650 }),
      P("K. Santoso", "MID", 5, 19, { xg: 3.6, minutes: 1500 }),
      P("B. Rumbiak", "FWD", 4, 14, { xg: 4.3, minutes: 820 }),
      P("T. Halim", "DEF", 2, 20, { xg: 1.1, minutes: 1790, status: "out" }),
    ],
  };
  m.h2h = [
    { hg: 2, ag: 1, homeAtHome: true },
    { hg: 1, ag: 1, homeAtHome: false },
    { hg: 0, ag: 1, homeAtHome: false },
    { hg: 3, ag: 2, homeAtHome: true },
  ];
  m.referee = { name: "Wasit contoh", yellowPg: 4.8, redPg: 0.25, pensPg: 0.38 };
  m.odds = { home: 1.95, draw: 3.4, away: 3.9, over25: 1.9, under25: 1.9, bttsYes: 1.8, bttsNo: 1.95, ahLine: -0.5, ahHome: 1.95, ahAway: 1.9 };
  m.notes = "Contoh dengan tim fiktif — ganti dengan data pertandingan Anda.";
  return m;
}
