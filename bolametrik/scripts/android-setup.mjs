// Menyiapkan proyek Android hasil `npx cap add android`:
// ikon & splash BolaMetrik, warna tema, versi dari package.json, dan kunci
// tanda tangan tetap (signing/bolametrik.keystore) supaya APK versi baru selalu
// bisa dipasang menimpa versi lama tanpa uninstall (data tidak hilang).
import { cpSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const root = new URL("../", import.meta.url);
const res = new URL("android/app/src/main/res/", root);
if (!existsSync(res)) {
  console.error("Folder android/ belum ada. Jalankan dulu: npx cap add android");
  process.exit(1);
}
cpSync(new URL("resources/android/res/", root), res, { recursive: true });

const colors = new URL("values/colors.xml", res);
if (!existsSync(colors) || !readFileSync(colors, "utf8").includes("#06140F"))
  writeFileSync(
    colors,
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="colorPrimary">#06140F</color>
    <color name="colorPrimaryDark">#06140F</color>
    <color name="colorAccent">#FFC940</color>
</resources>
`,
  );

// Versi: 1.2.3 → versionCode 10203
const { version } = JSON.parse(readFileSync(new URL("package.json", root), "utf8"));
const [maj, min, pat] = version.split(".").map((x) => parseInt(x, 10) || 0);
const code = maj * 10000 + min * 100 + pat;
const gradlePath = new URL("android/app/build.gradle", root);
let gradle = readFileSync(gradlePath, "utf8");
gradle = gradle.replace(/versionCode \d+/, `versionCode ${code}`).replace(/versionName "[^"]*"/, `versionName "${version}"`);
if (!gradle.includes("bolametrik.keystore")) {
  gradle = gradle.replace(
    /android\s*\{/,
    `android {
    signingConfigs {
        debug {
            storeFile file("../../signing/bolametrik.keystore")
            storePassword "android"
            keyAlias "androiddebugkey"
            keyPassword "android"
        }
    }`,
  );
}
writeFileSync(gradlePath, gradle);
console.log(`Android siap: ikon, splash, warna, versi ${version} (${code}), kunci tanda tangan tetap.`);
