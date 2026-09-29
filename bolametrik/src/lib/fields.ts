// Definisi kolom tim — dipakai formulir, ekstraksi AI, dan tampilan perubahan.

import type { TeamInput } from "./types";

export type TeamNumKey = {
  [K in keyof TeamInput]-?: TeamInput[K] extends number | null ? K : never;
}[keyof TeamInput];

export interface FieldDef {
  key: TeamNumKey;
  label: string;
  hint?: string;
  step?: string;
  unit?: string;
}

export interface FieldGroup {
  id: string;
  title: string;
  desc: string;
  fields: FieldDef[];
  venue?: boolean;
}

export function teamGroups(side: "home" | "away"): FieldGroup[] {
  const venueWord = side === "home" ? "KANDANG" : "TANDANG";
  return [
    {
      id: "season",
      title: "Musim ini — keseluruhan",
      desc: "Total musim berjalan di kompetisi ini (bukan musim lalu).",
      fields: [
        { key: "position", label: "Posisi klasemen" },
        { key: "played", label: "Main" },
        { key: "wins", label: "Menang" },
        { key: "draws", label: "Seri" },
        { key: "losses", label: "Kalah" },
        { key: "gf", label: "Gol dicetak", hint: "total" },
        { key: "ga", label: "Kebobolan", hint: "total" },
        { key: "cleanSheets", label: "Clean sheet", hint: "jumlah laga" },
        { key: "failedToScore", label: "Gagal cetak gol", hint: "jumlah laga" },
      ],
    },
    {
      id: "venue",
      venue: true,
      title: `Rekor ${venueWord} saja`,
      desc: side === "home" ? "Hanya laga kandang tuan rumah — data paling relevan untuk tim ini." : "Hanya laga tandang tim tamu — data paling relevan untuk tim ini.",
      fields: [
        { key: "venuePlayed", label: `Main ${venueWord.toLowerCase()}` },
        { key: "venueWins", label: "Menang" },
        { key: "venueDraws", label: "Seri" },
        { key: "venueLosses", label: "Kalah" },
        { key: "venueGF", label: "Gol dicetak", hint: "total" },
        { key: "venueGA", label: "Kebobolan", hint: "total" },
      ],
    },
    {
      id: "form",
      title: "Laga-laga terakhir",
      desc: "Isi huruf form (W/D/L atau M/S/K, terbaru di kiri) dan total gol di laga-laga tersebut.",
      fields: [
        { key: "formGF", label: "Total gol dicetak", hint: "di laga form" },
        { key: "formGA", label: "Total kebobolan", hint: "di laga form" },
      ],
    },
    {
      id: "adv",
      title: "Statistik lanjutan (per laga)",
      desc: "xG sangat berpengaruh bila tersedia. Semua angka rata-rata per laga.",
      fields: [
        { key: "xgFor", label: "xG", step: "0.01" },
        { key: "xgAgainst", label: "xGA (xG lawan)", step: "0.01" },
        { key: "shotsFor", label: "Tembakan", step: "0.1" },
        { key: "sotFor", label: "Tepat sasaran", step: "0.1" },
        { key: "shotsAgainst", label: "Tembakan lawan", step: "0.1" },
        { key: "sotAgainst", label: "Tepat sasaran lawan", step: "0.1" },
        { key: "possession", label: "Penguasaan bola", unit: "%", step: "0.1" },
        { key: "fhGFPct", label: "Gol di babak 1", unit: "%", hint: "% dari total gol dicetak" },
        { key: "fhGAPct", label: "Kebobolan di babak 1", unit: "%", hint: "% dari total kebobolan" },
      ],
    },
    {
      id: "set",
      title: "Corner, kartu & penalti (per laga)",
      desc: "Dipakai untuk pasaran corner, kartu, potensi penalti dan kartu merah.",
      fields: [
        { key: "cornersFor", label: "Corner didapat", step: "0.1" },
        { key: "cornersAgainst", label: "Corner lawan", step: "0.1" },
        { key: "yellowPg", label: "Kartu kuning", step: "0.01" },
        { key: "redPg", label: "Kartu merah", step: "0.01" },
        { key: "pensForPg", label: "Penalti didapat", step: "0.01" },
        { key: "pensAgainstPg", label: "Penalti diberikan", step: "0.01" },
      ],
    },
  ];
}

export const ALL_TEAM_FIELDS: FieldDef[] = teamGroups("home").flatMap((g) => g.fields);

export function fieldLabel(key: string, side: "home" | "away"): string {
  const g = teamGroups(side).find((gr) => gr.fields.some((f) => f.key === key));
  const f = g?.fields.find((x) => x.key === key);
  return f ? `${f.label}${g?.venue ? ` (${side === "home" ? "kandang" : "tandang"})` : ""}` : key;
}
