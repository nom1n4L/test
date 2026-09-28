// OCR offline-ish (Tesseract.js dari CDN) + pembacaan heuristik.
// Akurasinya terbatas; dipakai bila AI tidak tersedia. Hasil selalu ditinjau pengguna.

import type { Extraction } from "./ai";

/* eslint-disable @typescript-eslint/no-explicit-any */

const TESS_URL = "https://cdn.jsdelivr.net/npm/tesseract.js@7.0.0/dist/tesseract.min.js";

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if ((window as any).Tesseract) return resolve();
    const s = document.createElement("script");
    s.src = src;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("Gagal memuat mesin OCR (butuh internet)."));
    document.head.appendChild(s);
  });
}

export async function ocrImages(files: Blob[], onProgress?: (msg: string) => void): Promise<string[]> {
  await loadScript(TESS_URL);
  const T = (window as any).Tesseract;
  onProgress?.("Menyiapkan mesin OCR…");
  const worker = await T.createWorker("eng", 1, {
    logger: (m: any) => {
      if (m.status === "recognizing text") onProgress?.(`Membaca teks… ${Math.round((m.progress ?? 0) * 100)}%`);
    },
  });
  const texts: string[] = [];
  try {
    for (let i = 0; i < files.length; i++) {
      onProgress?.(`Membaca gambar ${i + 1} dari ${files.length}…`);
      const { data } = await worker.recognize(files[i]);
      texts.push(data.text as string);
    }
  } finally {
    await worker.terminate();
  }
  return texts;
}

interface Rule {
  re: RegExp;
  not?: RegExp;
  key: string;
  pct?: boolean;
}

// Baris "label nilaiTuanRumah nilaiTamu" seperti tampilan perbandingan statistik
const RULES: Rule[] = [
  { re: /(expected goals|\bxg\b)/i, not: /against|xga|conceded/i, key: "xgFor" },
  { re: /(xga|expected goals against|xg against)/i, key: "xgAgainst" },
  { re: /(possession|penguasaan)/i, key: "possession", pct: true },
  { re: /(shots on target|on target|tepat sasaran)/i, not: /against|conceded|lawan/i, key: "sotFor" },
  { re: /(total shots|^shots\b|tembakan)/i, not: /target|sasaran|against|conceded|lawan|blocked|off/i, key: "shotsFor" },
  { re: /(corner|sepak pojok|tendangan sudut)/i, not: /against|conceded/i, key: "cornersFor" },
  { re: /(yellow|kartu kuning)/i, key: "yellowPg" },
  { re: /(red card|kartu merah)/i, key: "redPg" },
  { re: /(goals scored|gol dicetak|goals per (game|match)|scored)/i, not: /conceded|kebobolan/i, key: "gf" },
  { re: /(goals conceded|kebobolan|conceded)/i, key: "ga" },
  { re: /(clean sheet)/i, key: "cleanSheets" },
  { re: /(matches played|played|main)\b/i, key: "played" },
];

const numRe = /-?\d+(?:[.,]\d+)?/g;

export function parseOcr(texts: string[]): { ext: Extraction; hits: string[] } {
  const ext: Extraction = { home: {}, away: {}, h2h: [], notes: "Dibaca dengan OCR — periksa ulang setiap angka.", unclear: [] };
  const hits: string[] = [];
  const forms: string[] = [];
  for (const text of texts) {
    for (const rawLine of text.split(/\n+/)) {
      const line = rawLine.trim();
      if (!line) continue;
      // Form: 4-10 huruf W/D/L atau M/S/K berurutan
      const fm = line.toUpperCase().match(/\b([WDL](?:\s*[WDL]){3,9})\b/) || line.toUpperCase().match(/\b([MSK](?:\s*[MSK]){3,9})\b/);
      if (fm) forms.push(fm[1].replace(/\s+/g, ""));
      // Skor H2H "2 - 1"
      const sc = line.match(/\b(\d{1,2})\s*[-–:]\s*(\d{1,2})\b/);
      if (sc && /h2h|head|vs|\d{4}|\d{2}\.\d{2}/i.test(line) && ext.h2h!.length < 10) {
        ext.h2h!.push({ hg: +sc[1], ag: +sc[2], homeAtHome: true });
        hits.push(`H2H? ${sc[1]}-${sc[2]} ← "${line}"`);
      }
      for (const r of RULES) {
        if (!r.re.test(line) || (r.not && r.not.test(line))) continue;
        const nums = (line.match(numRe) ?? []).map((x) => parseFloat(x.replace(",", ".")));
        if (nums.length >= 2) {
          const [a, b] = [nums[0], nums[nums.length - 1]];
          if ((ext.home as any)[r.key] == null) (ext.home as any)[r.key] = a;
          if ((ext.away as any)[r.key] == null) (ext.away as any)[r.key] = b;
          hits.push(`${r.key}: ${a} | ${b} ← "${line}"`);
        }
        break;
      }
    }
  }
  if (forms[0]) ext.home!.form = forms[0];
  if (forms[1]) ext.away!.form = forms[1];
  if (!hits.length && !forms.length) ext.unclear!.push("Tidak ada pola statistik yang dikenali; isi manual dari teks di bawah.");
  return { ext, hits };
}
