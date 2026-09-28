import type { ReactElement, SVGProps } from "react";
import { useApp, type Route } from "./state";
import { Home } from "./pages/Home";
import { Analyze } from "./pages/Analyze";
import { Result } from "./pages/Result";
import { History } from "./pages/History";
import { Lab } from "./pages/Lab";
import { Tools } from "./pages/Tools";
import { Settings } from "./pages/Settings";
import { BrandMark, IBrain, ICalc, IGear, IHome, IList, IPlus } from "./components/icons";

type NavKey = "home" | "analyze" | "history" | "lab" | "tools" | "settings";

const NAV: { key: NavKey; label: string; short: string; icon: (p: SVGProps<SVGSVGElement>) => ReactElement; mobile: boolean }[] = [
  { key: "home", label: "Beranda", short: "Beranda", icon: IHome, mobile: true },
  { key: "analyze", label: "Analisis baru", short: "Analisis", icon: IPlus, mobile: true },
  { key: "history", label: "Riwayat", short: "Riwayat", icon: IList, mobile: true },
  { key: "lab", label: "Lab belajar", short: "Belajar", icon: IBrain, mobile: true },
  { key: "tools", label: "Alat", short: "Alat", icon: ICalc, mobile: true },
  { key: "settings", label: "Pengaturan", short: "Atur", icon: IGear, mobile: false },
];

function activeKey(r: Route): NavKey {
  if (r.page === "result") return "history";
  return r.page as NavKey;
}

export default function App() {
  const { route, go, ready, stats, toasts, storeKind } = useApp();
  const act = activeKey(route);
  return (
    <div className="shell">
      <nav className="rail" aria-label="Navigasi utama">
        <div className="brand">
          <BrandMark className="brand-mark" />
          <div>
            <div className="brand-name">Bola<span>Metrik</span></div>
            <div className="brand-sub">prediksi yang belajar</div>
          </div>
        </div>
        {NAV.map((n) => (
          <button key={n.key} type="button" className={`nav-btn${act === n.key ? " active" : ""}`} aria-current={act === n.key ? "page" : undefined} onClick={() => go({ page: n.key } as Route)}>
            <n.icon />
            {n.label}
            {n.key === "history" && stats.pending > 0 && <span className="nav-badge">{stats.pending}</span>}
          </button>
        ))}
        <div className="rail-foot">
          {storeKind === "cloud" ? "Tersinkron ke claude.ai" : "Tersimpan di perangkat ini"}
          <br />Peluang, bukan kepastian.
        </div>
      </nav>

      <main className="main">
        <div className="mobile-top">
          <div className="brand" style={{ padding: 0 }}>
            <BrandMark className="brand-mark" />
            <div className="brand-name">Bola<span>Metrik</span></div>
          </div>
          <button className="btn btn-sm btn-ghost icon-btn" type="button" aria-label="Pengaturan" onClick={() => go({ page: "settings" })}><IGear /></button>
        </div>
        {!ready ? (
          <div className="page empty"><span className="spin" /> <p style={{ marginTop: 10 }}>Memuat data…</p></div>
        ) : route.page === "home" ? (
          <Home />
        ) : route.page === "analyze" ? (
          <Analyze key={route.editId ?? "new"} editId={route.editId} />
        ) : route.page === "result" ? (
          <Result key={route.id} id={route.id} />
        ) : route.page === "history" ? (
          <History />
        ) : route.page === "lab" ? (
          <Lab />
        ) : route.page === "tools" ? (
          <Tools />
        ) : (
          <Settings />
        )}
      </main>

      <nav className="bottom-nav" aria-label="Navigasi">
        {NAV.filter((n) => n.mobile).map((n) => (
          <button key={n.key} type="button" className={act === n.key ? "active" : ""} aria-current={act === n.key ? "page" : undefined} onClick={() => go({ page: n.key } as Route)}>
            <n.icon />
            {n.short}
          </button>
        ))}
      </nav>

      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast${t.bad ? " bad" : ""}`}>{t.text}</div>
        ))}
      </div>
    </div>
  );
}
