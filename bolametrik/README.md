# BolaMetrik — prediksi sepak bola yang belajar dari hasil

Aplikasi prediksi pertandingan sepak bola berbasis data. Unggah screenshot statistik, biarkan AI (Claude) membacanya, lalu dapatkan prediksi lengkap. Setiap hasil nyata yang Anda input membuat model mengevaluasi kesalahannya dan menyesuaikan diri.

> Prediksi adalah **peluang**, bukan kepastian. Tidak ada model yang bisa menjamin hasil pertandingan.

## Isi prediksi

| Pertanyaan | Jawaban di aplikasi |
| --- | --- |
| Skor babak 1, babak 2, skor akhir | Skenario koheren (babak 1 + babak 2 = FT) + daftar skor paling mungkin tiap babak |
| Pemenang | Peluang 1X2, double chance, draw no bet |
| Pencetak gol | Peluang kapan saja, gol pertama, 2+ gol per pemain (dari gol/xG per laga, status cedera, algojo penalti) |
| Penalti & kartu merah | Peluang ada penalti / pemain dikeluarkan, beserta faktor penyebabnya (wasit, derby, disiplin tim) |
| Handicap Asia | Tabel semua garis (0, ¼, ½, ¾ …) dengan menang/menang½/refund/kalah½/kalah, garis adil model, notasi Indonesia (mis. `½-1`) |
| Over/Under | Garis 0.5–5.5 + garis Asia (2.25, 2.75 …), garis adil |
| Total gol Tim 1 / Tim 2 | Over 0.5/1.5/2.5 per tim, "ada tim cetak Over 1.5?" |
| Lainnya | BTTS, HT/FT, clean sheet, win to nil, ganjil/genap, babak tersubur, waktu gol, corner, kartu |
| Rekomendasi pasaran | Paling aman · utama · value bet (bila odds diisi, dengan Kelly) · spekulatif |
| Transparansi | Keyakinan 0–10, sinyal yang bertentangan, 5 bukti terkuat, risiko terbesar |

## Mode cepat (data minim)

Tidak perlu data lengkap. Di **Mode cepat** cukup isi nama kedua tim, lalu tambahkan sebisanya: posisi klasemen, form (W/D/L atau M/S/K), gol 5 laga terakhir, penilaian kekuatan 1–5, pemain penting yang absen, dan odds bandar. Odds 1X2 + Over/Under 2.5 paling membantu bila statistik tim tidak ada. Keyakinan otomatis turun saat data minim, dan hasil prediksi memberi tahu sumber data apa saja yang dipakai.

## Penyedia AI

AI dipakai untuk membaca screenshot dan menulis analisis naratif; prediksi statistik tetap berjalan tanpa AI. Pilih di **Pengaturan → Penyedia AI**:

| Penyedia | Biaya | Catatan |
| --- | --- | --- |
| **Google Gemini** | ada kuota gratis | Key dari aistudio.google.com/apikey. Model default `gemini-flash-latest` (bisa membaca gambar). |
| **OpenRouter** | ada model gratis (`:free`) | Tombol *Muat daftar model* menandai model gratis & yang bisa membaca gambar. |
| **Groq** | ada kuota gratis | Cepat; hanya sebagian model bisa membaca gambar. |
| OpenAI / DeepSeek / URL sendiri | berbayar / bervariasi | Semua layanan yang kompatibel dengan `/chat/completions`. |
| Claude (Anthropic) | berbayar | Di Artifact claude.ai otomatis memakai akun Claude Anda. |

API key hanya disimpan di perangkat, tidak ikut cadangan, dan dikirim langsung ke penyedia yang dipilih. Tombol *Tes koneksi* memastikan key & model benar.

## Fitur

- **Unggah screenshot** (klasemen, statistik kandang/tandang, form, xG, H2H, pemain, cedera, odds, wasit). AI (Gemini, OpenRouter, Groq, Claude, …) membaca angka yang terlihat saja, menandai yang tidak terbaca, dan Anda meninjau setiap perubahan sebelum diterapkan. Ada juga OCR dasar (Tesseract) untuk versi mandiri.
- **Analisis AI mendalam** dalam Bahasa Indonesia: fakta → interpretasi → inferensi → prediksi, termasuk sinyal yang bertentangan dan risiko. Estimasi AI digabung dengan model statistik; bobot AI dipelajari dari mana yang lebih akurat.
- **Belajar dari kesalahan** (Lab Belajar): setelah hasil diinput, semua laga selesai di-replay secara kronologis untuk memperbarui skala gol, faktor per liga, keunggulan kandang, faktor seri, porsi gol babak 1, skala corner/kartu/penalti/merah, bobot tiap sumber data (algoritma Hedge), dan keandalan tiap kategori pasaran. Ada skor Brier vs tebakan dasar, grafik kalibrasi, dan jurnal perubahan.
- **Simulasi pertandingan** menit-per-menit (gol, peluang, corner, kartu, penalti) + 1.000 simulasi untuk melihat varians.
- **Alat**: konversi odds Indo/Malay/HK/desimal/US/pecahan, hapus margin bandar, Kelly, kalkulator handicap Asia, mix parlay, Poisson cepat.
- **Riwayat & tim tersimpan**, salin ringkasan ke WhatsApp/Telegram, cadangan JSON (ekspor/impor).

## Cara kerja model

1. Dua belas sumber data menghasilkan ekspektasi gol masing-masing: gol musim ini, rekor kandang/tandang, gol di laga terakhir, xG/xGA, tembakan tepat sasaran, poin per laga, poin form, H2H, odds bandar (peluang pasar dibalik menjadi ekspektasi gol), posisi klasemen, penilaian kekuatan dari Anda, dan rata-rata liga. Sumber yang kosong dilewati; sampel kecil disusutkan ke rata-rata liga.
2. Ekspektasi digabung dengan rata-rata geometris berbobot (bobot dasar × bobot dipelajari × reliabilitas sampel).
3. Konteks (absen, pencetak gol cedera, motivasi, istirahat, derby, final) mengalikan ekspektasi.
4. Matriks skor Poisson dengan koreksi Dixon-Coles dan faktor seri → semua pasaran gol. Babak 1 dan babak 2 dimodelkan terpisah memakai porsi gol babak 1 tiap tim.
5. Corner dan kartu memakai Negative Binomial; penalti dan kartu merah memakai proses Poisson dengan faktor wasit/derby/keseimbangan tim.

Kode inti ada di `src/lib/` (`model.ts`, `markets.ts`, `extras.ts`, `insights.ts`, `recommend.ts`, `learning.ts`).

## Tiga cara memakai

1. **Artifact claude.ai** — buka tautan Artifact. AI memakai akun Claude Anda (izin diminta saat pertama dipakai) dan data tersinkron antar perangkat.
2. **APK Android** — `release/BolaMetrik.apk` (atau unduh dari tab *Actions* → *BolaMetrik — build APK* → artifact `BolaMetrik-apk`). Instal dengan mengizinkan "sumber tidak dikenal". Fitur AI butuh API key salah satu penyedia (Gemini gratis sudah cukup).
3. **Web mandiri / PWA** — host `dist/index.html` (satu file) di hosting statis mana pun; bisa dipasang ke layar utama.

## Pengembangan

```bash
npm install
npm run dev          # server pengembangan
npm test             # tes engine (vitest)
npm run typecheck
npm run build        # dist/index.html + dist-artifact/bolametrik.html
```

Build APK secara lokal (butuh JDK 21 dan Android SDK 36):

```bash
npm run build
npx cap add android      # sekali saja
npx cap sync android
node scripts/android-setup.mjs   # ikon, splash, warna, versi, kunci tanda tangan
cd android && ./gradlew assembleDebug
# hasil: android/app/build/outputs/apk/debug/app-debug.apk
```

## Catatan

- APK ditandatangani dengan kunci tetap `signing/bolametrik.keystore` (sandi `android`), jadi versi baru — baik dari build lokal maupun GitHub Actions — bisa dipasang menimpa versi lama tanpa menghapus data. Kunci ini untuk pemakaian pribadi; jika aplikasi akan disebarkan ke publik, ganti dengan kunci rilis rahasia.

- Data tersimpan di perangkat (IndexedDB) atau di cloud claude.ai saat dibuka sebagai Artifact. API key hanya disimpan di perangkat dan tidak pernah ikut cadangan.
- Angka default liga adalah perkiraan jangka panjang dan bisa diubah di formulir; model juga mempelajari faktor tiap liga dari hasil yang Anda input.
- Taruhan berisiko dan dibatasi hukum di banyak wilayah, termasuk Indonesia. Patuhi hukum setempat dan jangan bertaruh dengan uang yang tidak siap hilang.
