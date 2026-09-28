// Menyalin ikon & splash BolaMetrik ke proyek Android hasil `npx cap add android`.
import { cpSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const src = new URL("../resources/android/res/", import.meta.url);
const dst = new URL("../android/app/src/main/res/", import.meta.url);
if (!existsSync(dst)) {
  console.error("Folder android/ belum ada. Jalankan dulu: npx cap add android");
  process.exit(1);
}
cpSync(src, dst, { recursive: true });

// Warna status bar gelap mengikuti tema aplikasi
const colors = new URL("values/colors.xml", dst);
const xml = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="colorPrimary">#06140F</color>
    <color name="colorPrimaryDark">#06140F</color>
    <color name="colorAccent">#FFC940</color>
</resources>
`;
if (!existsSync(colors) || !readFileSync(colors, "utf8").includes("#06140F")) writeFileSync(colors, xml);
console.log("Ikon, splash & warna Android diperbarui.");
