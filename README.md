# Skorlogi

Aplikasi Android untuk memprediksi pertandingan sepak bola. Semua data diunduh
otomatis dan seluruh perhitungan berjalan di dalam HP — tanpa akun, tanpa kunci
API, tanpa server, tanpa iklan.

**[⬇ Unduh Skorlogi-1.5.apk](Skorlogi-1.5.apk)** · 3,5 MB · Android 7.0 ke atas

---

## Kalau sumber datanya diblokir

Sebagian jaringan — termasuk beberapa ISP Indonesia — mengarahkan
`football-data.co.uk` ke server blokir, karena arsip itu memuat data odds bandar.
Gejalanya: aplikasi kosong dan muncul `Failed to connect to ...:443`.

Aplikasi mengenali keadaan ini dan menawarkan **sumber cadangan** sekali tekan:
arsip openfootball di GitHub. Tanpa kunci, tanpa daftar, dan tanpa data odds —
sehingga biasanya tidak ikut terblokir. Isinya 8 liga besar (Premier League,
Championship, La Liga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga),
dan jadwalnya justru **satu musim penuh** — 361 laga ke depan, bukan seminggu.

Yang tidak ikut: market corner dan kartu. Kebetulan itu dua market yang gagal uji
kalibrasi, jadi yang hilang tidak banyak.

Untuk Liga 1 Indonesia, tetap perlu kunci API-Football gratis lewat Pengaturan.

### Mendapatkan kunci API-Football

Layanan yang sama dijual lewat dua pintu, dan kunci dari satu pintu ditolak pintu
lainnya dengan pesan yang terdengar seperti kuncinya rusak. Aplikasi ini mencoba
keduanya otomatis, jadi jalur mana pun bisa dipakai.

1. Buka `dashboard.api-football.com`, daftar gratis — email dan kata sandi, tanpa
   kartu kredit.
2. Menu **Profile** memuat kuncinya: deretan panjang huruf dan angka.
3. Tempel di Pengaturan → Tambah Liga Dunia, tekan **Cek kunci**. Sisa kuota hari
   itu langsung ditampilkan.
4. **Ambil daftar liga** (satu permintaan), cari `Indonesia`, centang Liga 1.
5. Kembali ke atas, **Perbarui Data Sekarang**.

Sekali perbarui menghabiskan sekitar 5 permintaan untuk jadwal, plus 3 per liga
baru. Riwayat hanya diambil ulang seminggu sekali, jadi kuota gratis harian cukup
untuk pemakaian pribadi.

## Isinya apa

- **Penjelasan "Kenapa Begitu"** — tiap prediksi dijelaskan dengan kalimat biasa:
  siapa lebih kuat menyerang, seberapa rapat pertahanannya, faktor kandang bernilai
  berapa gol. Semua kalimatnya dihasilkan dari angka yang dipakai model, bukan
  ringkasan terpisah, dan ada test yang memastikan teksnya tidak pernah
  bertentangan dengan angkanya.
- **Chatbot Claude** (opsional, berbayar) — dibatasi hanya boleh memakai angka
  yang dihitung aplikasi ini; dilarang mengarang statistik dari ingatannya.
- **Menu Parlay** — susun sendiri atau pakai saran, lengkap dengan peluang gabungan
  dan imbal hasil harapan yang sebenarnya.
- **Pencarian** — cari tim, liga, atau pertandingan.
- **Halaman tim** — peringkat, Elo, faktor serangan/pertahanan, pemisahan
  kandang vs tandang, laga terakhir, dan jadwal berikutnya beserta peluangnya.
- **Pilihan Terbaik harian** — satu layar berisi prediksi yang lolos saringan,
  diurutkan dari yang paling bisa dipercaya.
- **Pelacak hasil** — catat prediksi yang kamu ikuti; hasilnya diisi otomatis, dan
  akurasi nyatanya dibandingkan dengan yang dijanjikan model.
- **Liga dunia lewat API-Football** (opsional, kunci gratis) — termasuk Liga 1 dan
  Liga 2 Indonesia, dengan jadwal yang jauh lebih panjang ke depan.

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

## Market mana yang boleh dipercaya

Akurasi rata-rata menyembunyikan hal terpenting untuk sebuah daftar pilihan:
**kalau model bilang 75%, apakah benar terjadi 75%?** Ini hasil pengukurannya,
sebelum dikoreksi:

| Market | Model bilang | Kenyataan | Vonis |
|---|---|---|---|
| Double Chance | 74,7% | 74,3% | ✅ jujur |
| Hasil Akhir (unggulan) | 74,7% | 77,0% | ✅ jujur |
| Over 1.5 gol | 75,3% | 77,8% | ✅ jujur |
| Babak 1 ada gol | 74,6% | 75,1% | ✅ jujur |
| Kedua tim cetak gol | 72,2% | 64,5% | ⚠️ kelewat pede |
| **Total corner** | **73,7%** | **54,5%** | ❌ hampir lempar koin |

Corner yang mengaku 82% ternyata cuma benar 53%. Dua perbaikan dipasang:
model corner dan kartu kini memakai binomial negatif (sebarannya lebih lebar,
karena Poisson terlalu yakin), lalu semua market lewat **kalibrasi Platt** yang
dipasang dari data musim yang ditahan. Setelah itu, klaim corner di atas 70%
turun dari 241 kasus menjadi 24, dan BTTS berhenti mengklaim di atas 70%.

**Corner dan kartu tidak pernah muncul di halaman Pilihan Terbaik** — hanya
empat market yang lolos uji yang boleh masuk.

## Seberapa akurat

Diuji ulang secara *walk-forward* pada **2.709 pertandingan** dari 7 liga: model
dilatih ulang tiap pekan dan hanya boleh melihat data yang benar-benar sudah ada
saat itu, jadi tidak ada hasil yang bocor dari masa depan.

| Yang diprediksi | Akurasi |
|---|---|
| Hasil akhir (1X2) | **51,8%** |
| Over/Under 2.5 gol | **56,4%** |
| Kedua tim cetak gol | **55,8%** |
| Babak 1 ada gol | **72,5%** |

Sebagai pembanding, pada 2.709 laga yang sama:

| Pembanding | Akurasi 1X2 | Log loss |
|---|---|---|
| Tebak tuan rumah menang terus | 43,8% | 1,0737 |
| **Skorlogi** | **51,8%** | **0,9909** |
| Bandar Bet365 (margin dibuang) | 53,9% | 0,9742 |

Angka 1X2 turun sedikit dari versi sebelumnya (52,0%) karena ambang minimal
riwayat tim diturunkan sampai satu laga. Itu memasukkan pertandingan bertim baru
promosi yang sebelumnya tidak diprediksi sama sekali — sepertiga jadwal di awal
musim. Rata-ratanya jadi ikut turun, tapi prediksi tipis itu ditandai keyakinan
rendah dan tidak pernah masuk Pilihan Terbaik.

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
   dan kartu; hanya angka yang dimasukkan yang berbeda. Corner dan kartu memakai
   binomial negatif, bukan Poisson, karena sebarannya jauh lebih lebar.
5. Semua peluang dilewatkan kalibrasi Platt per keluarga market, dengan koefisien
   yang dipasang pada musim yang ditahan. Koefisiennya dipasang dan diukur pada
   rentang data yang sama, jadi keunggulannya sedikit terlalu cerah — tapi dengan
   dua parameter melawan 8.127 sampel, selisihnya kecil.

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

**Sumber cadangan tanpa kunci** — 8 liga: Premier League, Championship, La Liga,
Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga. Jadwal semusim penuh,
tanpa corner/kartu/odds.

**Gol dan odds saja** — 16 liga: Argentina, Austria, Brasil, China, Denmark,
Finlandia, Irlandia, Jepang, Meksiko, Norwegia, Polandia, Rumania, Rusia,
Swedia, Swiss, Amerika Serikat (MLS).

Liga Indonesia tidak ada di arsip terbuka ini. Untuk Liga 1 dan Liga 2, pasang
kunci API-Football gratis lewat Pengaturan → Tambah Liga Dunia.

Catatan jujur soal jalur API: data corner dan kartu tidak ikut lewat sana, karena
di API itu biayanya satu permintaan per pertandingan — mustahil muat di kuota
gratis. Market tersebut tetap datang dari arsip untuk 22 liga yang punya datanya.

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
| `CalibrationTest` | Klaim model vs kenyataan, per market per tingkat keyakinan |
| `CalibrationFitTest` | Memasang koefisien Platt yang dipakai `Calibration.kt` |
| `CornerSweepTest` | Menelusuri kenapa prediksi corner tidak bisa dipercaya |
| `OpenFootballTest` | Alur penuh lewat sumber cadangan tanpa kunci |
| `AnalysisTest` | Teks penjelasan tidak boleh bertentangan dengan angka model |
| `ParlayTest` | Aritmetika parlay, termasuk klaim 1/margin^n |

## Soal parlay

Ada menu Parlay di aplikasi, dan menu itu memimpin dengan aritmetikanya, bukan
menyembunyikannya. Diuji di `ParlayTest`: tiga leg "aman" (peluang gabungan
72,1%) dan tiga leg berisiko (17,2%) menghasilkan imbal hasil harapan yang
**persis sama**, 83,9%. Pilihan yang lebih baik menaikkan peluang menang dan
menurunkan bayaran dengan faktor yang sama; yang tersisa hanyalah margin.


Perlu dikatakan terang-terangan, karena ini pertanyaan yang paling sering muncul.

Pada 2.709 pertandingan uji, log loss model ini 0,9889 sedangkan Bet365 0,9744 —
**bandarnya lebih akurat**. Artinya, secara rata-rata, melawan harga mereka itu
rugi.

Parlay memperburuknya secara berlipat. Tiap leg mengandung margin bandar sekitar
5%, dan margin itu dikalikan, bukan dijumlahkan:

- Parlay 4 leg → margin menumpuk jadi ~1,05⁴ ≈ **rugi 21% per taruhan**, secara
  rata-rata, sebelum modelnya salah sedikit pun.
- Parlay 4 leg dengan tiap leg berpeluang 55% → peluang tembus semua = 0,55⁴ =
  **9%**.

Jadi parlay adalah cara **paling merugikan** untuk memakai keunggulan apa pun,
bahkan seandainya modelnya lebih pintar dari bandar. Mengganti sumber data tidak
mengubah aritmetika ini sedikit pun.

Aplikasi ini dibuat untuk membantu memahami satu pertandingan dan menandai harga
yang jelas jelek — bukan mesin parlay yang menang terus. Yang seperti itu tidak
ada.

## Catatan

Angka-angka di aplikasi ini adalah peluang statistik dari data historis, bukan
ramalan. Pakailah sebagai satu bahan analisis, bukan satu-satunya.
