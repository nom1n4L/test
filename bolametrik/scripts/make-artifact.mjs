// Mengubah dist/index.html (dokumen lengkap) menjadi dist-artifact/bolametrik.html:
// konten halaman tanpa <!doctype>/<html>/<head>/<body>, sesuai format Artifact claude.ai.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const html = readFileSync(new URL("../dist/index.html", import.meta.url), "utf8");
// Bundel JS berisi string seperti "<style" atau "<body", jadi potong skrip modul dulu.
const sStart = html.indexOf('<script type="module"');
const sEnd = html.lastIndexOf("</script>") + "</script>".length;
if (sStart < 0 || sEnd <= sStart) throw new Error("skrip modul tidak ditemukan");
const script = html.slice(sStart, sEnd);
const rest = html.slice(0, sStart) + html.slice(sEnd);

const pick = (re) => [...rest.matchAll(re)].map((m) => m[0]);
const title = pick(/<title>[\s\S]*?<\/title>/g)[0] ?? "<title>BolaMetrik</title>";
const fonts = pick(/<link[^>]+fonts\.googleapis\.com\/css2[^>]*>/g);
const styles = pick(/<style[^>]*>[\s\S]*?<\/style>/g).map((s) => s.replace(/<style[^>]*>/, "<style>"));
const body = (rest.match(/<body[^>]*>([\s\S]*)<\/body>/) ?? [, ""])[1].trim();

const out = [title, ...fonts, ...styles, body, script].join("\n");
mkdirSync(new URL("../dist-artifact/", import.meta.url), { recursive: true });
writeFileSync(new URL("../dist-artifact/bolametrik.html", import.meta.url), out);
console.log(`dist-artifact/bolametrik.html (${(out.length / 1024).toFixed(0)} KB)`);
