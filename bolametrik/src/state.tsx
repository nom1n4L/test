import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { computeStats, type LearnState, relearn, type Stats } from "./lib/learning";
import { DEFAULT_SETTINGS, inArtifact, loadLocalSecret, openCloudStore, openLocalStore, saveLocalSecret, type Store, type StoreKind } from "./lib/storage";
import type { AppMeta, MatchInput, MatchRecord, Settings, TeamInput } from "./lib/types";
import { demoMatch, emptyMatch, uid } from "./lib/sample";

export type Route =
  | { page: "home" }
  | { page: "analyze"; editId?: string }
  | { page: "result"; id: string }
  | { page: "history" }
  | { page: "lab" }
  | { page: "tools" }
  | { page: "settings" };

export interface Attachment {
  id: string;
  file: File;
  url: string;
}

interface Toast {
  id: string;
  text: string;
  bad?: boolean;
}

interface AppCtx {
  ready: boolean;
  storeKind: StoreKind;
  records: MatchRecord[];
  meta: AppMeta;
  learn: LearnState;
  stats: Stats;
  route: Route;
  go: (r: Route) => void;
  draft: MatchInput;
  setDraft: (m: MatchInput | ((m: MatchInput) => MatchInput)) => void;
  resetDraft: () => void;
  attachments: Attachment[];
  addFiles: (files: File[]) => void;
  removeAttachment: (id: string) => void;
  clearAttachments: () => void;
  saveRecord: (r: MatchRecord) => Promise<void>;
  deleteRecord: (id: string) => Promise<void>;
  createFromDraft: () => Promise<string>;
  updateSettings: (s: Partial<Settings>) => void;
  saveTeam: (t: TeamInput) => void;
  deleteTeam: (name: string) => void;
  importData: (records: MatchRecord[], meta?: AppMeta | null) => Promise<number>;
  wipeAll: () => Promise<void>;
  toast: (text: string, bad?: boolean) => void;
  toasts: Toast[];
  lessons: string[];
}

const Ctx = createContext<AppCtx | null>(null);

export function useApp() {
  const c = useContext(Ctx);
  if (!c) throw new Error("AppCtx");
  return c;
}

const DRAFT_KEY = "bolametrik:draft";
const ROUTE_KEY = "bolametrik:route";

function loadDraft(): MatchInput {
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (raw) return { ...emptyMatch(), ...JSON.parse(raw) };
  } catch {
    /* diabaikan */
  }
  return emptyMatch();
}

function loadRoute(): Route {
  try {
    const raw = sessionStorage.getItem(ROUTE_KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    /* diabaikan */
  }
  return { page: "home" };
}

function demoRecord(): MatchRecord {
  const now = new Date().toISOString();
  return { id: "demo", createdAt: now, updatedAt: now, input: demoMatch(), snapshot: relearn([]).params, ai: null, result: null, sources: [], demo: true };
}

export function AppProvider({ children }: { children: ReactNode }) {
  const storeRef = useRef<Store | null>(null);
  const [ready, setReady] = useState(false);
  const [storeKind, setStoreKind] = useState<StoreKind>("memory");
  const [records, setRecords] = useState<MatchRecord[]>([]);
  const [meta, setMeta] = useState<AppMeta>({ settings: { ...DEFAULT_SETTINGS, apiKey: loadLocalSecret() }, teams: {} });
  const [route, setRoute] = useState<Route>(loadRoute);
  const [draft, setDraftState] = useState<MatchInput>(loadDraft);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [toasts, setToasts] = useState<Toast[]>([]);

  const toast = useCallback((text: string, bad?: boolean) => {
    const id = uid();
    setToasts((t) => [...t, { id, text, bad }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4200);
  }, []);

  const applyLoaded = (data: { records: MatchRecord[]; meta: AppMeta | null }) => {
    setRecords(data.records);
    if (data.meta) setMeta({ settings: { ...DEFAULT_SETTINGS, ...data.meta.settings, apiKey: loadLocalSecret() }, teams: data.meta.teams ?? {} });
  };

  useEffect(() => {
    let alive = true;
    (async () => {
      const local = await openLocalStore();
      if (!alive) return;
      storeRef.current = local;
      setStoreKind(local.kind);
      try {
        applyLoaded(await local.loadAll());
      } catch {
        /* data lokal rusak: mulai kosong */
      }
      setReady(true);
      if (inArtifact()) {
        const cloud = await openCloudStore();
        if (!alive || !cloud) return;
        try {
          const data = await cloud.loadAll();
          const localData = await local.loadAll();
          // Migrasi sekali: data lokal naik ke cloud bila cloud masih kosong
          if (data.records.length === 0 && localData.records.length > 0) {
            for (const r of localData.records) await cloud.putRecord(r);
            if (localData.meta) await cloud.putMeta(localData.meta);
            data.records = localData.records;
            data.meta = data.meta ?? localData.meta;
          }
          storeRef.current = cloud;
          setStoreKind("cloud");
          applyLoaded(data);
        } catch {
          /* tetap lokal */
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const go = useCallback((r: Route) => {
    setRoute(r);
    try {
      sessionStorage.setItem(ROUTE_KEY, JSON.stringify(r));
    } catch {
      /* diabaikan */
    }
    window.scrollTo({ top: 0 });
  }, []);

  const setDraft = useCallback((m: MatchInput | ((m: MatchInput) => MatchInput)) => {
    setDraftState((prev) => {
      const next = typeof m === "function" ? (m as (x: MatchInput) => MatchInput)(prev) : m;
      try {
        localStorage.setItem(DRAFT_KEY, JSON.stringify(next));
      } catch {
        /* diabaikan */
      }
      return next;
    });
  }, []);

  const resetDraft = useCallback(() => setDraft(emptyMatch(draft.leagueKey)), [setDraft, draft.leagueKey]);

  const addFiles = useCallback((files: File[]) => {
    const imgs = files.filter((f) => f.type.startsWith("image/"));
    setAttachments((a) => [...a, ...imgs.map((file) => ({ id: uid(), file, url: URL.createObjectURL(file) }))]);
  }, []);
  const removeAttachment = useCallback((id: string) => {
    setAttachments((a) => {
      const x = a.find((y) => y.id === id);
      if (x) URL.revokeObjectURL(x.url);
      return a.filter((y) => y.id !== id);
    });
  }, []);
  const clearAttachments = useCallback(() => {
    setAttachments((a) => {
      a.forEach((x) => URL.revokeObjectURL(x.url));
      return [];
    });
  }, []);

  const persistMeta = useCallback(async (m: AppMeta) => {
    try {
      await storeRef.current?.putMeta({ ...m, settings: { ...m.settings, apiKey: "" } });
    } catch {
      /* diabaikan */
    }
  }, []);

  const saveRecord = useCallback(
    async (r: MatchRecord) => {
      const rec = { ...r, updatedAt: new Date().toISOString() };
      setRecords((rs) => [...rs.filter((x) => x.id !== rec.id), rec]);
      try {
        await storeRef.current?.putRecord(rec);
      } catch (e) {
        toast(`Gagal menyimpan: ${(e as Error)?.message ?? "penyimpanan penuh"}`, true);
      }
    },
    [toast],
  );

  const deleteRecord = useCallback(async (id: string) => {
    setRecords((rs) => rs.filter((x) => x.id !== id));
    try {
      await storeRef.current?.deleteRecord(id);
    } catch {
      /* diabaikan */
    }
  }, []);

  const learn = useMemo(() => relearn(records), [records]);
  const stats = useMemo(() => computeStats(records), [records]);

  const saveTeam = useCallback(
    (t: TeamInput) => {
      if (!t.name.trim()) return;
      setMeta((m) => {
        const next = { ...m, teams: { ...m.teams, [t.name.trim()]: { name: t.name.trim(), updatedAt: new Date().toISOString(), team: JSON.parse(JSON.stringify(t)) } } };
        void persistMeta(next);
        return next;
      });
    },
    [persistMeta],
  );

  const deleteTeam = useCallback(
    (name: string) => {
      setMeta((m) => {
        const teams = { ...m.teams };
        delete teams[name];
        const next = { ...m, teams };
        void persistMeta(next);
        return next;
      });
    },
    [persistMeta],
  );

  const createFromDraft = useCallback(async () => {
    const now = new Date().toISOString();
    const id = uid();
    const input: MatchInput = JSON.parse(JSON.stringify(draft));
    input.home.name = input.home.name.trim() || "Tuan rumah";
    input.away.name = input.away.name.trim() || "Tim tamu";
    const rec: MatchRecord = { id, createdAt: now, updatedAt: now, input, snapshot: learn.params, ai: null, result: null, sources: attachments.map((a) => a.file.name) };
    await saveRecord(rec);
    saveTeam(input.home);
    saveTeam(input.away);
    return id;
  }, [draft, learn.params, attachments, saveRecord, saveTeam]);

  const updateSettings = useCallback(
    (s: Partial<Settings>) => {
      setMeta((m) => {
        const next = { ...m, settings: { ...m.settings, ...s } };
        if (s.apiKey !== undefined) saveLocalSecret(s.apiKey);
        void persistMeta(next);
        return next;
      });
    },
    [persistMeta],
  );

  const importData = useCallback(
    async (recs: MatchRecord[], m?: AppMeta | null) => {
      let n = 0;
      for (const r of recs) {
        if (!r?.id || !r.input || !r.snapshot) continue;
        await saveRecord(r);
        n++;
      }
      if (m?.teams) {
        setMeta((cur) => {
          const next = { ...cur, teams: { ...cur.teams, ...m.teams } };
          void persistMeta(next);
          return next;
        });
      }
      return n;
    },
    [saveRecord, persistMeta],
  );

  const wipeAll = useCallback(async () => {
    for (const r of records) await storeRef.current?.deleteRecord(r.id);
    setRecords([]);
    const next = { settings: meta.settings, teams: {} };
    setMeta(next);
    await persistMeta(next);
  }, [records, meta.settings, persistMeta]);

  const allRecords = useMemo(() => {
    // Tampilkan contoh hanya bila belum ada analisis sama sekali
    if (records.length === 0) return [demoRecord()];
    return records;
  }, [records]);

  const lessons = useMemo(() => {
    // Ringkas pelajaran terbaru untuk konteks AI
    const out: string[] = [];
    const p = learn.params;
    if (p.learned >= 3) {
      out.push(`Model sudah belajar dari ${p.learned} laga. Skala gol global ${p.goalScale.toFixed(2)} (1 = netral), keunggulan kandang ${p.homeAdv.toFixed(2)}, faktor seri ${p.drawBoost.toFixed(2)}.`);
      for (const [k, v] of Object.entries(p.leagueFactor)) if (Math.abs(v - 1) > 0.04) out.push(`Liga ${k}: total gol aktual ${v > 1 ? "lebih tinggi" : "lebih rendah"} dari perkiraan (faktor ${v.toFixed(2)}).`);
      for (const [cat, s] of Object.entries(p.market)) if (s && s.n >= 4) out.push(`Pasaran ${cat}: menang ${Math.round((s.hits / s.n) * 100)}% dari ${s.n} rekomendasi (ekspektasi ${Math.round((s.sumP / s.n) * 100)}%).`);
    }
    for (const l of learn.log.slice(-5)) if (l.changes.length) out.push(`${l.label}: ${l.changes.slice(0, 3).join("; ")}`);
    return out;
  }, [learn]);

  const value: AppCtx = {
    ready,
    storeKind,
    records: allRecords,
    meta,
    learn,
    stats,
    route,
    go,
    draft,
    setDraft,
    resetDraft,
    attachments,
    addFiles,
    removeAttachment,
    clearAttachments,
    saveRecord,
    deleteRecord,
    createFromDraft,
    updateSettings,
    saveTeam,
    deleteTeam,
    importData,
    wipeAll,
    toast,
    toasts,
    lessons,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
