// Penyimpanan: cloud (db Artifact claude.ai, sinkron antar perangkat) bila tersedia,
// selain itu IndexedDB lokal, lalu localStorage, lalu memori.

import type { AppMeta, MatchRecord, Settings } from "./types";

export type StoreKind = "cloud" | "local" | "memory";

export interface Store {
  kind: StoreKind;
  loadAll(): Promise<{ records: MatchRecord[]; meta: AppMeta | null }>;
  putRecord(r: MatchRecord): Promise<void>;
  deleteRecord(id: string): Promise<void>;
  putMeta(m: AppMeta): Promise<void>;
}

export const DEFAULT_SETTINGS: Settings = {
  apiKey: "",
  aiModel: "claude-opus-5-5",
  bankroll: 1000000,
  kellyFraction: 0.25,
};

const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

// ---------- IndexedDB ----------
function idbOpen(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("bolametrik", 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("records")) db.createObjectStore("records", { keyPath: "id" });
      if (!db.objectStoreNames.contains("meta")) db.createObjectStore("meta");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function idbReq<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

async function idbStore(): Promise<Store> {
  const db = await idbOpen();
  const tx = (name: string, mode: IDBTransactionMode) => db.transaction(name, mode).objectStore(name);
  return {
    kind: "local",
    async loadAll() {
      const records = (await idbReq(tx("records", "readonly").getAll())) as MatchRecord[];
      const meta = ((await idbReq(tx("meta", "readonly").get("meta"))) as AppMeta | undefined) ?? null;
      return { records, meta };
    },
    async putRecord(r) {
      await idbReq(tx("records", "readwrite").put(clone(r)));
    },
    async deleteRecord(id) {
      await idbReq(tx("records", "readwrite").delete(id));
    },
    async putMeta(m) {
      await idbReq(tx("meta", "readwrite").put(clone(m), "meta"));
    },
  };
}

// ---------- localStorage ----------
function lsStore(): Store {
  const KEY = "bolametrik:v1";
  const read = (): { records: MatchRecord[]; meta: AppMeta | null } => {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) return JSON.parse(raw);
    } catch {
      /* diabaikan */
    }
    return { records: [], meta: null };
  };
  const write = (v: { records: MatchRecord[]; meta: AppMeta | null }) => {
    try {
      localStorage.setItem(KEY, JSON.stringify(v));
    } catch {
      /* penuh / diblokir */
    }
  };
  // tes akses
  localStorage.setItem(KEY + ":t", "1");
  localStorage.removeItem(KEY + ":t");
  return {
    kind: "local",
    async loadAll() {
      return read();
    },
    async putRecord(r) {
      const v = read();
      v.records = [...v.records.filter((x) => x.id !== r.id), clone(r)];
      write(v);
    },
    async deleteRecord(id) {
      const v = read();
      v.records = v.records.filter((x) => x.id !== id);
      write(v);
    },
    async putMeta(m) {
      const v = read();
      v.meta = clone(m);
      write(v);
    },
  };
}

function memStore(): Store {
  let records: MatchRecord[] = [];
  let meta: AppMeta | null = null;
  return {
    kind: "memory",
    async loadAll() {
      return { records, meta };
    },
    async putRecord(r) {
      records = [...records.filter((x) => x.id !== r.id), r];
    },
    async deleteRecord(id) {
      records = records.filter((x) => x.id !== id);
    },
    async putMeta(m) {
      meta = m;
    },
  };
}

export async function openLocalStore(): Promise<Store> {
  try {
    if (typeof indexedDB !== "undefined") return await idbStore();
  } catch {
    /* lanjut ke fallback */
  }
  try {
    return lsStore();
  } catch {
    return memStore();
  }
}

// ---------- Cloud (Artifact runtime) ----------
/* eslint-disable @typescript-eslint/no-explicit-any */
type AnyDB = any;

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race([p, new Promise<null>((r) => setTimeout(() => r(null), ms))]);
}

/** API key tidak pernah disimpan ke cloud. */
function stripSecrets(m: AppMeta): AppMeta {
  return { ...m, settings: { ...m.settings, apiKey: "" } };
}

export async function openCloudStore(): Promise<Store | null> {
  const c = (window as any).claude;
  if (!c || typeof c.use !== "function") return null;
  try {
    const [db, user] = (await withTimeout(Promise.all([c.use("db"), c.use("user")]), 12000)) ?? [null, null];
    if (!db || !user || typeof user.id !== "function") return null;
    const id = await user.id();
    if (!id) return null;
    const root = (db as AnyDB).doc(`data/users/${id}/app`);
    const col = root.collection("records");
    // Satu tulisan per dokumen pada satu waktu
    const queues = new Map<string, Promise<unknown>>();
    const serial = (key: string, fn: () => Promise<unknown>) => {
      const prev = queues.get(key) ?? Promise.resolve();
      const next = prev.catch(() => undefined).then(fn);
      queues.set(key, next);
      return next.then(() => undefined);
    };
    const store: Store = {
      kind: "cloud",
      async loadAll() {
        const [snap, metaSnap] = await Promise.all([col.limit(1000).get(), root.get()]);
        const records = snap.docs.map((d: any) => clone(d.data()) as MatchRecord);
        const meta = metaSnap.exists ? ((clone(metaSnap.data()).meta as AppMeta) ?? null) : null;
        return { records, meta };
      },
      putRecord: (r) => serial(`r:${r.id}`, () => col.doc(r.id).set(clone(r))),
      deleteRecord: (rid) => serial(`r:${rid}`, () => col.doc(rid).delete()),
      putMeta: (m) => serial("meta", () => root.set({ meta: clone(stripSecrets(m)) })),
    };
    // Uji baca sekali; jika gagal, anggap tidak tersedia
    await store.loadAll();
    return store;
  } catch {
    return null;
  }
}

export function inArtifact(): boolean {
  return typeof window !== "undefined" && !!(window as any).claude;
}

// API key hanya disimpan di perangkat ini
export function loadLocalSecret(): string {
  try {
    return localStorage.getItem("bolametrik:apikey") ?? "";
  } catch {
    return "";
  }
}

export function saveLocalSecret(v: string) {
  try {
    if (v) localStorage.setItem("bolametrik:apikey", v);
    else localStorage.removeItem("bolametrik:apikey");
  } catch {
    /* diabaikan */
  }
}
