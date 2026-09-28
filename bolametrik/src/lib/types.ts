// Tipe data inti BolaMetrik.

export type Pos = "GK" | "DEF" | "MID" | "FWD";
export type PlayerStatus = "fit" | "doubt" | "out";
export type Num = number | null;

export interface Player {
  id: string;
  name: string;
  pos: Pos;
  goals: Num; // gol musim ini (kompetisi yang relevan)
  assists: Num;
  apps: Num; // jumlah penampilan
  minutes: Num; // total menit bermain
  xg: Num; // total xG musim ini
  penTaker: boolean;
  status: PlayerStatus;
}

export interface TeamInput {
  name: string;
  // Musim ini — keseluruhan
  position: Num;
  played: Num;
  wins: Num;
  draws: Num;
  losses: Num;
  gf: Num;
  ga: Num;
  // Khusus venue: kandang untuk tuan rumah, tandang untuk tim tamu
  venuePlayed: Num;
  venueWins: Num;
  venueDraws: Num;
  venueLosses: Num;
  venueGF: Num;
  venueGA: Num;
  // Form terakhir (huruf terbaru di depan), W/D/L atau M/S/K
  form: string;
  formGF: Num; // total gol dicetak di laga form
  formGA: Num; // total kebobolan di laga form
  // Statistik lanjutan (rata-rata per laga)
  xgFor: Num;
  xgAgainst: Num;
  shotsFor: Num;
  sotFor: Num;
  shotsAgainst: Num;
  sotAgainst: Num;
  possession: Num; // %
  cleanSheets: Num; // jumlah dari `played`
  failedToScore: Num; // jumlah dari `played`
  fhGFPct: Num; // % gol dicetak di babak 1
  fhGAPct: Num; // % kebobolan di babak 1
  cornersFor: Num; // per laga
  cornersAgainst: Num; // per laga
  yellowPg: Num; // kartu kuning per laga
  redPg: Num; // kartu merah per laga
  pensForPg: Num; // penalti didapat per laga
  pensAgainstPg: Num; // penalti diberikan per laga
  // Konteks
  absAttack: number; // 0..3 absen lini serang di luar daftar pemain
  absDefense: number; // 0..3 absen lini belakang
  motivation: number; // -2..2
  restDays: Num;
  players: Player[];
}

export interface H2HMatch {
  hg: number; // gol tim tuan rumah SAAT INI
  ag: number; // gol tim tamu SAAT INI
  homeAtHome: boolean; // apakah tuan rumah saat ini juga bermain di kandang pada laga itu
  date?: string;
}

export interface RefereeInput {
  name: string;
  yellowPg: Num;
  redPg: Num;
  pensPg: Num;
}

export interface OddsInput {
  home: Num;
  draw: Num;
  away: Num;
  over25: Num;
  under25: Num;
  bttsYes: Num;
  bttsNo: Num;
  ahLine: Num; // garis handicap untuk tuan rumah, mis. -0.5
  ahHome: Num;
  ahAway: Num;
}

export type Competition = "league" | "cup" | "continental" | "friendly";
export type Importance = "normal" | "high" | "final";

export interface MatchInput {
  leagueKey: string;
  leagueName: string;
  homeAvg: number; // rata-rata gol tuan rumah per laga di liga
  awayAvg: number; // rata-rata gol tim tamu per laga di liga
  kickoff: string; // yyyy-mm-ddThh:mm
  competition: Competition;
  importance: Importance;
  derby: boolean;
  neutral: boolean;
  home: TeamInput;
  away: TeamInput;
  h2h: H2HMatch[];
  referee: RefereeInput;
  odds: OddsInput;
  notes: string;
}

export type SignalKey =
  | "season"
  | "venue"
  | "form"
  | "xg"
  | "shots"
  | "table"
  | "formPts"
  | "h2h"
  | "league";

export type MarketCat =
  | "1X2"
  | "DC"
  | "DNB"
  | "AH"
  | "OU"
  | "BTTS"
  | "TT"
  | "HT"
  | "CORN"
  | "CARD"
  | "CS"
  | "HTFT"
  | "SCORER";

export interface MarketStat {
  n: number;
  hits: number;
  sumP: number;
}

/** Parameter yang dipelajari dari hasil pertandingan (hasil replay semua laga yang sudah selesai). */
export interface ModelParams {
  goalScale: number;
  homeAdv: number;
  drawBoost: number;
  rho: number;
  fhShare: number;
  cornerScale: number;
  cardScale: number;
  penScale: number;
  redScale: number;
  weights: Record<SignalKey, number>;
  leagueFactor: Record<string, number>;
  market: Partial<Record<MarketCat, MarketStat>>;
  aiWeight: number;
  learned: number;
}

/** Hasil analisis AI (Claude) yang dipakai sebagai "pakar kedua". */
export interface AIOpinion {
  createdAt: string;
  text: string; // narasi markdown
  probs: {
    home: number;
    draw: number;
    away: number;
    over25: number | null;
    btts: number | null;
  } | null;
  xg: { home: number; away: number } | null;
}

export interface MatchResult {
  ftH: number;
  ftA: number;
  htH: number | null;
  htA: number | null;
  corners: number | null;
  cards: number | null; // total kartu kuning (+ merah dihitung 1)
  red: boolean | null;
  pen: boolean | null;
  scorers: string[];
  settledAt: string;
}

export interface MatchRecord {
  id: string;
  createdAt: string;
  updatedAt: string;
  input: MatchInput;
  snapshot: ModelParams; // parameter saat prediksi dibuat (prediksi = fungsi murni dari input+snapshot)
  ai: AIOpinion | null;
  result: MatchResult | null;
  sources: string[]; // nama screenshot / sumber data
  demo?: boolean;
}

export interface Settings {
  apiKey: string;
  aiModel: string;
  bankroll: number;
  kellyFraction: number;
}

export interface TeamProfile {
  name: string;
  updatedAt: string;
  team: TeamInput;
}

export interface AppMeta {
  settings: Settings;
  teams: Record<string, TeamProfile>;
}
