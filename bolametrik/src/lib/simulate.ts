// Simulasi pertandingan menit-per-menit (Monte Carlo) untuk visualisasi.

export interface SimEvent {
  minute: number; // urutan waktu (termasuk tambahan waktu)
  clock: string; // tampilan menit, mis. 45+2'

  type: "goal" | "yellow" | "red" | "corner" | "pen" | "ht" | "ft" | "chance";
  team: "H" | "A" | null;
  text: string;
  score: [number, number];
}

function mulberry32(seed: number) {
  return function () {
    let t = (seed += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface SimParams {
  lh1: number; // ekspektasi gol babak 1
  la1: number;
  lh2: number;
  la2: number;
  cornersH: number;
  cornersA: number;
  cardsH: number;
  cardsA: number;
  redLambda: number;
  penLambda: number;
  penHomeShare: number;
  homeName: string;
  awayName: string;
  scorersH: { name: string; lambda: number }[];
  scorersA: { name: string; lambda: number }[];
}

function pickScorer(list: { name: string; lambda: number }[], rnd: () => number, fallback: string) {
  const tot = list.reduce((s, x) => s + x.lambda, 0);
  // Sisa peluang untuk pemain lain yang tidak didata
  const other = Math.max(0.25, 1 - tot) * 0.6;
  let r = rnd() * (tot + other);
  for (const x of list) {
    r -= x.lambda;
    if (r <= 0) return x.name;
  }
  return fallback;
}

export function simulateMatch(p: SimParams, seed = Date.now()): SimEvent[] {
  const rnd = mulberry32(seed);
  const ev: SimEvent[] = [];
  const score: [number, number] = [0, 0];
  const stoppage1 = 1 + Math.floor(rnd() * 4);
  const stoppage2 = 3 + Math.floor(rnd() * 5);
  let seq = 0;
  let half = 1;
  const clockOf = (m: number) => (half === 1 ? (m <= 45 ? `${m}'` : `45+${m - 45}'`) : m <= 90 ? `${m}'` : `90+${m - 90}'`);
  const push = (e: Omit<SimEvent, "score" | "clock">) => ev.push({ ...e, clock: e.type === "ht" ? "HT" : e.type === "ft" ? "FT" : clockOf(e.minute), minute: seq++, score: [score[0], score[1]] });
  const perMin = (total: number, minutes: number) => total / minutes;
  const halves: [number, number, number, number, number][] = [
    [1, 45 + stoppage1, p.lh1, p.la1, 0.46],
    [46, 90 + stoppage2, p.lh2, p.la2, 0.54],
  ];
  for (const [start, end, lh, la, share] of halves) {
    const len = end - start + 1;
    for (let m = start; m <= end; m++) {
      // Intensitas sedikit naik di akhir babak
      const late = 1 + 0.25 * ((m - start) / len);
      const gh = perMin(lh, len) * late, ga = perMin(la, len) * late;
      const pen = perMin(p.penLambda * share, len);
      const r = rnd();
      if (r < pen) {
        const team = rnd() < p.penHomeShare ? "H" : "A";
        const scored = rnd() < 0.77;
        if (scored) {
          if (team === "H") score[0]++;
          else score[1]++;
          const who = pickScorer(team === "H" ? p.scorersH : p.scorersA, rnd, team === "H" ? p.homeName : p.awayName);
          push({ minute: m, type: "pen", team, text: `PENALTI! ${who} menaklukkan kiper (${team === "H" ? p.homeName : p.awayName})` });
        } else push({ minute: m, type: "pen", team, text: `Penalti untuk ${team === "H" ? p.homeName : p.awayName} — GAGAL!` });
        continue;
      }
      const g = rnd();
      if (g < gh) {
        score[0]++;
        push({ minute: m, type: "goal", team: "H", text: `GOL! ${pickScorer(p.scorersH, rnd, p.homeName)} untuk ${p.homeName}` });
      } else if (g < gh + ga) {
        score[1]++;
        push({ minute: m, type: "goal", team: "A", text: `GOL! ${pickScorer(p.scorersA, rnd, p.awayName)} untuk ${p.awayName}` });
      } else if (g < gh + ga + (gh + ga) * 1.6) {
        const team = rnd() < gh / (gh + ga) ? "H" : "A";
        push({ minute: m, type: "chance", team, text: `Peluang emas ${team === "H" ? p.homeName : p.awayName} — melebar tipis!` });
      }
      const c = rnd();
      if (c < perMin(p.cornersH * share, len)) push({ minute: m, type: "corner", team: "H", text: `Corner untuk ${p.homeName}` });
      else if (c < perMin((p.cornersH + p.cornersA) * share, len)) push({ minute: m, type: "corner", team: "A", text: `Corner untuk ${p.awayName}` });
      const k = rnd();
      const ys = share === 0.46 ? 0.4 : 0.6;
      if (k < perMin(p.cardsH * ys, len)) push({ minute: m, type: "yellow", team: "H", text: `Kartu kuning untuk pemain ${p.homeName}` });
      else if (k < perMin((p.cardsH + p.cardsA) * ys, len)) push({ minute: m, type: "yellow", team: "A", text: `Kartu kuning untuk pemain ${p.awayName}` });
      if (rnd() < perMin(p.redLambda * share, len)) {
        const team = rnd() < 0.5 ? "H" : "A";
        push({ minute: m, type: "red", team, text: `KARTU MERAH! ${team === "H" ? p.homeName : p.awayName} bermain dengan 10 orang` });
      }
    }
    if (start === 1) {
      push({ minute: 45, type: "ht", team: null, text: `Turun minum: ${p.homeName} ${score[0]}-${score[1]} ${p.awayName}` });
      half = 2;
    }
  }
  push({ minute: 90, type: "ft", team: null, text: `Peluit akhir: ${p.homeName} ${score[0]}-${score[1]} ${p.awayName}` });
  return ev;
}
