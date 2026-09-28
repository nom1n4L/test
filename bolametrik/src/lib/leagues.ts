// Preset liga: rata-rata jangka panjang (perkiraan) yang dipakai sebagai "prior".
// Semua angka bisa diubah di formulir; model juga mempelajari faktor per-liga
// dari hasil pertandingan yang Anda input.

export interface LeaguePreset {
  key: string;
  name: string;
  homeAvg: number; // gol tuan rumah per laga
  awayAvg: number; // gol tim tamu per laga
  corners: number; // total corner per laga
  cards: number; // total kartu kuning per laga
  pens: number; // penalti per laga
  reds: number; // kartu merah per laga
}

export const LEAGUES: LeaguePreset[] = [
  { key: "epl", name: "Premier League (Inggris)", homeAvg: 1.6, awayAvg: 1.32, corners: 10.2, cards: 3.9, pens: 0.3, reds: 0.1 },
  { key: "laliga", name: "La Liga (Spanyol)", homeAvg: 1.45, awayAvg: 1.12, corners: 9.4, cards: 4.9, pens: 0.33, reds: 0.2 },
  { key: "seriea", name: "Serie A (Italia)", homeAvg: 1.45, awayAvg: 1.2, corners: 9.6, cards: 4.2, pens: 0.34, reds: 0.15 },
  { key: "bundesliga", name: "Bundesliga (Jerman)", homeAvg: 1.72, awayAvg: 1.4, corners: 9.5, cards: 3.9, pens: 0.28, reds: 0.1 },
  { key: "ligue1", name: "Ligue 1 (Prancis)", homeAvg: 1.52, awayAvg: 1.22, corners: 9.3, cards: 3.9, pens: 0.33, reds: 0.18 },
  { key: "eredivisie", name: "Eredivisie (Belanda)", homeAvg: 1.8, awayAvg: 1.38, corners: 9.8, cards: 3.6, pens: 0.3, reds: 0.12 },
  { key: "primeira", name: "Liga Portugal", homeAvg: 1.45, awayAvg: 1.1, corners: 9.6, cards: 4.8, pens: 0.33, reds: 0.2 },
  { key: "superlig", name: "Süper Lig (Turki)", homeAvg: 1.6, awayAvg: 1.3, corners: 9.8, cards: 4.6, pens: 0.35, reds: 0.2 },
  { key: "championship", name: "Championship (Inggris)", homeAvg: 1.42, awayAvg: 1.12, corners: 10.3, cards: 4.1, pens: 0.26, reds: 0.12 },
  { key: "ucl", name: "Liga Champions UEFA", homeAvg: 1.7, awayAvg: 1.35, corners: 9.8, cards: 3.8, pens: 0.3, reds: 0.1 },
  { key: "uel", name: "Liga Europa UEFA", homeAvg: 1.6, awayAvg: 1.3, corners: 9.8, cards: 4.0, pens: 0.3, reds: 0.12 },
  { key: "idn", name: "Liga 1 / Super League Indonesia", homeAvg: 1.6, awayAvg: 1.15, corners: 9.4, cards: 4.4, pens: 0.4, reds: 0.22 },
  { key: "mls", name: "MLS (AS)", homeAvg: 1.72, awayAvg: 1.35, corners: 9.6, cards: 3.9, pens: 0.3, reds: 0.12 },
  { key: "saudi", name: "Saudi Pro League", homeAvg: 1.68, awayAvg: 1.38, corners: 9.6, cards: 4.1, pens: 0.36, reds: 0.14 },
  { key: "j1", name: "J1 League (Jepang)", homeAvg: 1.4, awayAvg: 1.1, corners: 9.6, cards: 2.4, pens: 0.22, reds: 0.06 },
  { key: "kleague", name: "K League 1 (Korea)", homeAvg: 1.38, awayAvg: 1.08, corners: 9.4, cards: 3.4, pens: 0.25, reds: 0.1 },
  { key: "brasil", name: "Brasileirão (Brasil)", homeAvg: 1.42, awayAvg: 0.98, corners: 10.2, cards: 5.0, pens: 0.35, reds: 0.22 },
  { key: "argentina", name: "Liga Profesional (Argentina)", homeAvg: 1.22, awayAvg: 0.86, corners: 9.4, cards: 5.2, pens: 0.33, reds: 0.28 },
  { key: "intl", name: "Pertandingan Internasional", homeAvg: 1.45, awayAvg: 1.05, corners: 9.2, cards: 3.8, pens: 0.28, reds: 0.1 },
  { key: "other", name: "Liga lain / umum", homeAvg: 1.48, awayAvg: 1.16, corners: 9.7, cards: 4.2, pens: 0.3, reds: 0.14 },
];

export function leagueByKey(key: string): LeaguePreset {
  return LEAGUES.find((l) => l.key === key) ?? LEAGUES[LEAGUES.length - 1];
}
