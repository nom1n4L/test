import type { SVGProps } from "react";

type P = SVGProps<SVGSVGElement>;
const base = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round", viewBox: "0 0 24 24" } as const;

export const IHome = (p: P) => (
  <svg {...base} {...p}><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5h13V10" /><path d="M10 19.5v-5h4v5" /></svg>
);
export const IPlus = (p: P) => (
  <svg {...base} {...p}><circle cx="12" cy="12" r="9" /><path d="M12 8v8M8 12h8" /></svg>
);
export const IList = (p: P) => (
  <svg {...base} {...p}><path d="M8 6h12M8 12h12M8 18h12" /><circle cx="4" cy="6" r="1" /><circle cx="4" cy="12" r="1" /><circle cx="4" cy="18" r="1" /></svg>
);
export const IBrain = (p: P) => (
  <svg {...base} {...p}><path d="M9 4.5a3 3 0 0 0-3 3v.3A3.2 3.2 0 0 0 4 11a3.2 3.2 0 0 0 1.4 2.7A3 3 0 0 0 9 18.5V4.5Z" /><path d="M15 4.5a3 3 0 0 1 3 3v.3A3.2 3.2 0 0 1 20 11a3.2 3.2 0 0 1-1.4 2.7A3 3 0 0 1 15 18.5V4.5Z" /><path d="M9 19.5h6M12 4.5v15" /></svg>
);
export const ICalc = (p: P) => (
  <svg {...base} {...p}><rect x="5" y="3" width="14" height="18" rx="2.5" /><path d="M8.5 7h7M8.5 11h1M12 11h1M15.5 11h0M8.5 14.5h1M12 14.5h1M8.5 18h1M12 18h1M15.5 14.5V18" /></svg>
);
export const IGear = (p: P) => (
  <svg {...base} {...p}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" /></svg>
);
export const IUpload = (p: P) => (
  <svg {...base} {...p}><path d="M12 15V4M7.5 8.5 12 4l4.5 4.5" /><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" /></svg>
);
export const ISpark = (p: P) => (
  <svg {...base} {...p}><path d="M12 3.5 13.8 9l5.7 1.9-5.7 1.9L12 18.5l-1.8-5.7L4.5 11 10.2 9 12 3.5Z" /><path d="M19 3v3M17.5 4.5h3" /></svg>
);
export const ITrash = (p: P) => (
  <svg {...base} {...p}><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12.5A1.5 1.5 0 0 0 8.5 21h7a1.5 1.5 0 0 0 1.5-1.5L18 7M9 7V4.5h6V7" /></svg>
);
export const IEdit = (p: P) => (
  <svg {...base} {...p}><path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z" /><path d="m13.5 6.5 4 4" /></svg>
);
export const ICopy = (p: P) => (
  <svg {...base} {...p}><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8" /></svg>
);
export const ICheck = (p: P) => (
  <svg {...base} {...p}><path d="m5 12.5 4.5 4.5L19 7.5" /></svg>
);
export const IX = (p: P) => (
  <svg {...base} {...p}><path d="M6 6l12 12M18 6 6 18" /></svg>
);
export const IPlay = (p: P) => (
  <svg {...base} {...p}><path d="M7 5v14l12-7L7 5Z" /></svg>
);
export const IStop = (p: P) => (
  <svg {...base} {...p}><rect x="6" y="6" width="12" height="12" rx="2" /></svg>
);
export const IFlag = (p: P) => (
  <svg {...base} {...p}><path d="M5 21V4M5 4h11l-2 4 2 4H5" /></svg>
);
export const IDownload = (p: P) => (
  <svg {...base} {...p}><path d="M12 4v11M7.5 10.5 12 15l4.5-4.5" /><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" /></svg>
);
export const IChevron = (p: P) => (
  <svg {...base} {...p}><path d="m9 5 7 7-7 7" /></svg>
);
export const IBack = (p: P) => (
  <svg {...base} {...p}><path d="M15 5 8 12l7 7" /></svg>
);
export const ICloud = (p: P) => (
  <svg {...base} {...p}><path d="M7 18.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.7 1.4A3.3 3.3 0 0 0 7 18.5Z" /></svg>
);

export function BrandMark(p: P) {
  // Bola + garis lapangan
  return (
    <svg viewBox="0 0 40 40" {...p}>
      <rect x="1" y="1" width="38" height="38" rx="10" fill="#0f2d21" stroke="#ffc940" strokeWidth="1.5" />
      <path d="M1 20h38M20 1v38" stroke="rgba(237,243,236,.25)" strokeWidth="1" />
      <circle cx="20" cy="20" r="9.5" fill="#edf3ec" />
      <path d="M20 14.2l4.4 3.2-1.7 5.2h-5.4l-1.7-5.2Z" fill="#06140f" />
      <path d="M20 14.2V10.6M24.4 17.4l3.3-1.3M22.7 22.6l2.1 3M17.3 22.6l-2.1 3M15.6 17.4l-3.3-1.3" stroke="#06140f" strokeWidth="1.3" />
    </svg>
  );
}
