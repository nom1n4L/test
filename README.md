# Skorlogi

Aplikasi Android untuk memprediksi pertandingan sepak bola. Semua data diunduh
otomatis dan seluruh perhitungan berjalan di dalam HP — tanpa akun, tanpa kunci
API, tanpa server, tanpa iklan.

**[⬇ Unduh Skorlogi-1.0.apk](Skorlogi-1.0.apk)** · 1,0 MB · Android 7.0 ke atas

---

## Isinya apa

- **38 liga** dari 25 negara, disegarkan sekali tekan.
- **Puluhan market per pertandingan**, semuanya dibaca dari satu tabel peluang
  yang sama sehingga angkanya konsisten satu sama lain:
  1X2 · double chance · draw no bet · skor akhir · total gol (0.5–5.5) ·
  ganjil/genap · BTTS · gol per tim · clean sheet · handicap Asia · selisih gol ·
  babak 1 · babak 2 · babak 1/babak penuh · babak dengan gol terbanyak ·
  sepak pojok · kartu
- **Perbandingan dengan odds bandar** ketika arsipnya menyertakan harga pasar.
- **Penanda keyakinan** yang turun sendiri kalau riwayat tim tipis atau kedua
  model internal tidak sejalan.
- Berfungsi **offline** setelah sekali mengunduh.

## Seberapa akurat

Diuji ulang secara *walk-forward* pada **2.709 pertandingan** dari 7 liga: model
dilatih ulang tiap pekan dan hanya boleh melihat data yang benar-benar sudah ada
saat itu, jadi tidak ada hasil yang bocor dari masa depan.

| Yang diprediksi | Akurasi |
|---|---|
| Hasil akhir (1X2) | **52,0%** |
| Over/Under 2.5 gol | **55,9%** |
| Kedua tim cetak gol | **55,8%** |
| Babak 1 ada gol | **72,2%** |

Sebagai pembanding, pada 2.709 laga yang sama:

| Pembanding | Akurasi 1X2 | Log loss |
|---|---|---|
| Tebak tuan rumah menang terus | 43,7% | 1,0743 |
| **Skorlogi** | **52,0%** | **0,9889** |
| Bandar Bet365 (margin dibuang) | 53,9% | 0,9744 |

Jadi model ini jauh di atas tebakan asal dan sekitar 2 poin di bawah bandar —
kira-kira sebaik yang bisa dicapai model publik.

**Angka 52% itu memang sudah bagus.** Sepak bola adalah olahraga dengan gol
sedikit, jadi porsi keberuntungannya besar dan tidak bisa dihapus model apa pun.
Siapa pun yang menjanjikan akurasi 90% sedang menjual sesuatu. Aplikasi ini juga
tidak tahu soal cedera, rotasi pemain, larangan bermain, atau pergantian pelatih.

## Cara kerjanya

1. Mengunduh arsip hasil pertandingan tiga musim terakhir dari
   [football-data.co.uk](https://www.football-data.co.uk) — arsip CSV terbuka.
2. Per liga, mencari nilai serangan dan pertahanan tiap tim yang paling cocok
   dengan hasil nyata, dengan laga terbaru diberi bobot lebih besar
   ([Dixon & Coles, 1997](https://www.researchgate.net/publication/4746650)).
   Dipasang lewat *gradient ascent* (Adam) pada *log-likelihood* Poisson
   berbobot, dengan koreksi Dixon–Coles untuk skor kecil.
3. Elo dihitung terpisah, lalu dicampur 20% ke dalam peluang hasil akhir dengan
   cara memiringkan tabel skor — bukan mencampur angka 1X2 begitu saja — supaya
   semua market lain tetap konsisten dengan angka utamanya.
4. Mesin yang sama dipakai ulang untuk gol babak 1, gol babak 2, sepak pojok,
   dan kartu; hanya angka yang dimasukkan yang berbeda.

Semua parameter (laju peluruhan bobot waktu, bobot Elo, kekuatan *shrinkage*)
dipilih lewat sweep terhadap musim yang ditahan, bukan ditebak. Hasilnya:
permukaannya datar — dalam rentang yang wajar, pilihannya nyaris tidak
berpengaruh. Itu temuan yang jujur, bukan alasan.

## Liga yang tercakup

**Statistik lengkap** (termasuk corner, kartu, dan babak 1) — 22 liga:
Inggris (Premier League, Championship, League One, League Two, National League),
Skotlandia (4 divisi), Jerman (Bundesliga, 2. Bundesliga), Italia (Serie A, B),
Spanyol (La Liga, Segunda), Prancis (Ligue 1, 2), Belanda, Belgia, Portugal,
Turki, Yunani.

**Gol dan odds saja** — 16 liga: Argentina, Austria, Brasil, China, Denmark,
Finlandia, Irlandia, Jepang, Meksiko, Norwegia, Polandia, Rumania, Rusia,
Swedia, Swiss, Amerika Serikat (MLS).

Liga Indonesia belum ada karena arsip terbuka ini tidak memuatnya.

## Membangun sendiri

Butuh JDK 17+ dan Android SDK (compileSdk 34).

```bash
cd android
echo "sdk.dir=/path/ke/android-sdk" > local.properties
./gradlew assembleRelease        # hasil: app/build/outputs/apk/release/
```

Menjalankan uji akurasi (mengunduh dulu data musimnya):

```bash
mkdir -p android/backtest-data
for L in E0 SP1 D1 I1 F1 N1 EC; do for S in 2627 2526 2425; do
  curl -s -o android/backtest-data/${L}_${S}.csv \
    "https://www.football-data.co.uk/mmz4281/$S/$L.csv"
done; done
cd android && ./gradlew testDebugUnitTest
```

Tanpa direktori `backtest-data/`, uji akurasinya melewatkan dirinya sendiri
supaya build tetap jalan tanpa jaringan.

| Test | Gunanya |
|---|---|
| `BacktestTest` | Uji walk-forward, dibandingkan bandar dan tebakan asal |
| `DiagnosticTest` | Memastikan tiap sub-model mereproduksi rata-rata liganya |
| `EndToEndTest` | Unduh → parse → latih → prediksi pada jadwal nyata |
| `TuningTest` | Sweep laju peluruhan dan bobot Elo |
| `L2SweepTest` | Sweep kekuatan shrinkage |

## Catatan

Angka-angka di aplikasi ini adalah peluang statistik dari data historis, bukan
ramalan. Pakailah sebagai satu bahan analisis, bukan satu-satunya.
