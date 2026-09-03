# 1H Corner Predictor

Prediktor sepak pojok **babak pertama**, pasar Over/Under pada tujuh garis:
**1,5 · 2,5 · 3,5 · 4,5 · 5,5 · 6,5 · 7,5**. Aturan mainnya satu kalimat: kalau
keyakinan ≥ 85% keluar **PICK**, selain itu keluar **SKIP** — dan mesin ini
dirancang supaya angka 85% itu berarti sesuatu, bukan sekadar keluaran rumus.

```bash
python -m corner1h.cli --manual example.json --scan   # sisir semua garis
python -m corner1h.cli --manual example.json --line 5.5
pip install -r requirements.txt                       # untuk UI web + baca screenshot
uvicorn corner1h.api:app --port 8000                  # lalu buka http://localhost:8000
```

---

## Baca ini dulu: temuan yang mengubah bentuk aplikasinya

Tiga hal ditemukan saat membangun dan menguji mesin ini. Ketiganya membatasi apa
yang bisa dijanjikan aplikasi mana pun di pasar ini, jadi lebih baik dinyatakan
di depan daripada disembunyikan di balik antarmuka yang meyakinkan.

**1 — Garis 4,5 sengaja diletakkan di titik paling sulit.** Rata-rata liga untuk
corner babak pertama sekitar **4,9**. Artinya garis 4,5 duduk hampir persis di
puncak sebaran, tempat probabilitasnya paling dekat ke lemparan koin. Itu bukan
kebetulan — bandar memasang garis di sana justru karena di situ informasi paling
sedikit berguna.

Dengan sebaran binomial negatif (VMR 1,25), inilah peta keyakinannya:

| Proyeksi corner 1H | P(Over 4,5) | P(Under 4,5) |
|---|---|---|
| 2,5 | 13,0% | **87,0%** |
| 3,0 | 20,2% | 79,8% |
| 4,0 | 37,0% | 63,0% |
| **4,9 (rata-rata liga)** | **52,8%** | 47,2% |
| 6,0 | 68,5% | 31,5% |
| 7,0 | 79,6% | 20,4% |
| 7,6 | **85,0%** | 15,0% |

Untuk menembus 85% di garis 4,5:

* **UNDER** butuh proyeksi ≈ **2,6 corner 1H** — rendah, tapi ada di dunia nyata
  pada pasangan tim bertempo lambat.
* **OVER** butuh proyeksi ≈ **7,7 corner 1H** — itu 57% di atas rata-rata liga.
  Pasangan tim seperti itu praktis tidak ada.

**Karena itu aplikasi ini menilai tujuh garis, bukan satu.** Ambang 85%
dipertahankan utuh; yang berubah hanya titik potong pada sebaran yang itu-itu
juga. Inilah peta lengkapnya — μ yang dibutuhkan agar sebuah garis mencapai 85%:

| Garis | P(Over) @μ=4,9 | μ untuk OVER 85% | μ untuk UNDER 85% |
|---|---|---|---|
| 1,5 | 93,8% | 3,7 | 0,7 |
| 2,5 | 83,6% | 5,1 | 1,3 |
| 3,5 | 69,0% | 6,4 | 1,9 |
| **4,5** | **52,4%** | **7,7** | **2,6** |
| 5,5 | 36,8% | 8,9 | 3,4 |
| 6,5 | 23,9% | 10,1 | 4,2 |
| 7,5 | 14,6% | 11,4 | 4,9 |

Bacanya begini: pada pertandingan bertempo rata-rata (μ ≈ 4,9), garis 4,5 tidak
akan pernah lolos, tapi **Under 7,5 sudah lewat 85% tanpa perlu apa-apa** — dan
di situlah jebakannya, yang dibahas di bawah.

**2 — Backtest sintetis: 0 PICK dari 2.400 laga.** Pada 6.000 laga yang
dibangkitkan dari sebaran mirip liga nyata, dengan kalibrator dipasang pada
3.600 laga pertama dan diukur pada 2.400 laga terakhir, model tidak menerbitkan
satu pun PICK di ambang 85%. Kalibrasinya sendiri jujur — klaim dan kenyataan
selisihnya di bawah 2 poin di semua pita. Yang tidak ada hanyalah kesempatan.

Itu bukan kegagalan mesin; itu jawabannya untuk **garis 4,5**. Dengan tujuh
garis, PICK menjadi mungkin — tetapi lihat bagian "menyisir garis" di bawah
sebelum menganggap itu kabar baik tanpa syarat.

Angka pendukung, dari 12.000 laga sintetis: keyakinan ≥ 70% terjadi pada 20% laga
(perlu ~150 laga untuk memverifikasinya), ≥ 80% pada 3,7% laga (~800 laga), dan
≥ 85% pada 1,1% laga (**~2.800 laga** untuk mengumpulkan 30 sampel di pita itu).
Itu ukuran pekerjaan pengumpulan datanya, dan itu untuk satu garis.

**3 — Repositori ini sudah pernah mengukur hal serupa.** README Skorlogi mencatat
bahwa market corner mengklaim 73,7% dan realisasinya 54,5%; klaim 82% ternyata
benar 53%. Setelah diperbaiki, corner dan kartu dikeluarkan dari halaman Pilihan
Terbaik sepenuhnya. Aplikasi ini mewarisi pelajaran itu dalam bentuk kode, bukan
catatan kaki: binomial negatif, kalibrasi Platt wajib, dan gerbang yang menutup
sendiri.

---

## Bagaimana 85% dibuat berarti

Sebuah angka keyakinan hanya berguna kalau ia bisa salah dan ketahuan. Lima
mekanisme di bawah ini bekerja ke satu arah yang sama: **menurunkan keyakinan
ketika bukti tidak layak.**

### 1. Binomial negatif, bukan Poisson

Poisson mengunci varians sama dengan rata-rata. Corner tidak begitu — satu
serangan bertubi-tubi menghasilkan rentetan corner, jadi sebarannya lebih lebar
(VMR empiris 1,15–1,40). Memakai Poisson membuat ekornya terlalu tipis, dan
model mengaku 85% padahal kenyataannya sekitar 60%. Kalau riwayat corner 1H
per-laga tersedia, VMR dihitung dari data itu; kalau tidak, dipakai default
konservatif 1,25 — dan default yang lebih lebar selalu berarti keyakinan lebih
rendah, arah yang aman.

### 2. Shrinkage menurut ukuran sampel

Rasio serangan dan pertahanan ditarik ke rata-rata liga dengan bobot `n/(n+6)`.
Tim dengan 3 laga tidak boleh menggeser proyeksi sejauh tim dengan 20 laga.

### 3. Marginalisasi ketidakpastian parameter

μ bukan angka pasti. Probabilitas dirata-ratakan pada kisi normal di sekitar μ
dengan galat baku yang dihitung dari ukuran sampel plus ketidakpastian bentuk
model. Data tipis jadi otomatis mendekati 50% lewat integrasi yang benar, bukan
lewat faktor pengurang yang dikarang.

| Sampel | Galat baku μ | Keyakinan |
|---|---|---|
| 3 laga | ±1,22 | 76,5% |
| 8 laga | ±0,72 | 91,1% |
| 20 laga | ±0,54 | 95,6% |

### 4. Kalibrasi Platt yang wajib

`p_kalibrasi = sigmoid(A · logit(p_mentah) + B)`, dipasang pada data yang
ditahan. Pada uji sintetis, A keluar di **0,58** — model mentah memang kelewat
percaya diri, dan kalibrator menariknya kembali.

**Selama `data/calibration.json` belum ada, mesin menolak setiap PICK.** Ini
gerbang yang paling sering menutup, dan disengaja: ambang 85% tanpa kalibrasi
hanyalah angka.

### 5. Keandalan sumber ikut dihitung

Angka dari OCR tidak diperlakukan sama dengan angka yang diketik manual.

| Sumber | Bobot |
|---|---|
| Manual / API | 1,00 |
| Claude vision | 0,95 |
| OCR lokal | 0,80 |
| Diturunkan dari pertandingan penuh | 0,70 |
| Asumsi default | 0,40 |

Probabilitas ditarik ke 50% sebanding dengan kuadrat bobot rata-ratanya.

---

## Tujuh gerbang keras

Setelah angka jadi, tujuh syarat harus lulus semua. Satu saja gagal → SKIP,
berapa pun keyakinannya.

| Gerbang | Menutup ketika | Alasannya |
|---|---|---|
| **Kelengkapan** | Ada medan wajib yang kosong | Mesin bertanya, tidak menebak |
| **Ukuran sampel** | Tim mana pun < 8 laga | Rata-rata dari 5 laga bukan bukti |
| **Data 1H asli** | Angka 1H hasil membelah statistik pertandingan penuh | Porsi babak berbeda antar-tim; membelahnya adalah asumsi, bukan pengukuran |
| **Kelayakan proyeksi** | μ di luar 2,0–8,5 corner | Proyeksi 15 corner 1H berarti masukannya salah, bukan pertandingannya ekstrem |
| **Sensitivitas bentuk** | Jawaban bergeser > 6% saat VMR ditebak, dan VMR belum diukur | Garis pinggir sangat bergantung pada bentuk ekor sebaran, bukan hanya rata-rata |
| **Kalibrasi terpasang** | `calibration.json` belum ada | Ambang tanpa pengukuran tidak berarti apa-apa |
| **Ambang** | Keyakinan < 85% | 84% tetap SKIP |

Gerbang kelayakan menangkap penyebab kesalahan yang paling mungkin dalam sistem
berbasis screenshot: **bukan model yang keliru, melainkan angka yang salah
masuk.** Corner pertandingan penuh (~5,5 per tim) diketik ke medan babak pertama
menghasilkan proyeksi 15 — dan tanpa gerbang ini akan lolos sebagai PICK OVER
dengan keyakinan tinggi. Ada test khusus untuk kasus itu.

---

## Menyisir garis — dan jebakan yang menyertainya

`--scan` menilai ketujuh garis sekaligus. Tapi ada satu hal yang harus dilihat
langsung, karena kalau tidak, fiturnya justru menyesatkan:

```
  garis  keyakinan  sensitivitas   vonis
    7.5      98.2%          3.5%   PICK UNDER 7.5
    6.5      95.7%          4.5%   PICK UNDER 6.5
    5.5      90.5%          4.7%   PICK UNDER 5.5
    4.5      80.9%          3.2%   SKIP MATCH
```

**Menyisir garis selalu dimenangkan garis terjauh dari μ.** Itu bukan menemukan
taruhan bagus — itu menemukan tautologi. Under 7,5 pada 98,2% memang hampir
pasti benar, dan justru karena itu bandar membayarnya 1,03. Keyakinan tinggi dan
nilai tinggi adalah dua hal berbeda, dan menyisir garis memaksimalkan yang salah
dari keduanya.

### Odds membalik pertanyaannya

Masukkan harga bandar, dan peringkatnya berubah dari "mana yang paling aman"
menjadi "mana yang harganya paling salah":

```
  garis   sisi   harga  keyakinan       EV   vonis
    3.5  under    1.95      65.4%   +27.6%   SKIP MATCH
    4.5  under    1.40      80.9%   +13.3%   SKIP MATCH
    5.5  under    1.18      90.5%    +6.8%   PICK UNDER 5.5
    7.5  under    1.03      98.2%    +1.2%   PICK UNDER 7.5
```

Perhatikan ketegangannya: nilai harapan tertinggi (+27,6%) justru **SKIP**,
karena keyakinannya cuma 65,4%. Sistem tidak berusaha menyembunyikan konflik itu
— ia menampilkannya, dan `best_pick` hanya diambil dari baris yang lolos gerbang.

Nilai harapan sendiri hanya sebaik modelnya. `EV +27,6%` berarti "menurut model
ini, harga itu murah" — bukan "Anda akan untung 27,6%". Bacalah bersama
keyakinan, jangan menggantikannya.

### Gerbang sensitivitas bentuk sebaran

Pindah ke garis pinggir membuka risiko yang tidak ada saat hanya menilai 4,5:

| Garis | P @VMR 1,05 | P @VMR 1,90 | Ayunan |
|---|---|---|---|
| 1,5 | 95,2% | 89,1% | **6,1 pt** |
| 2,5 | 86,0% | 77,2% | **8,8 pt** |
| 4,5 | 53,8% | 49,1% | 4,7 pt |
| 5,5 | 36,7% | 36,5% | 0,2 pt |

Di garis 5,5 tebakan VMR nyaris tidak berpengaruh; di garis 2,5 ia menggeser
jawabannya hampir 9 poin. Cukup untuk mengarang PICK dari udara.

Karena itu: **kalau ayunannya melebihi 6% dan VMR belum diukur dari riwayat
corner per laga, PICK ditolak** — dan aplikasi meminta riwayatnya. Mengirim
daftar seperti `5, 3, 6, 2, 4` untuk kedua tim mengubah tebakan menjadi
pengukuran, dan gerbangnya terbuka.

---

## Arsitektur

```
corner1h/
├── corner1h/
│   ├── distributions.py    Poisson & binomial negatif — murni stdlib
│   ├── models.py           tipe data; setiap angka membawa provenance-nya
│   ├── engine.py           mesin: rasio → shrinkage → μ → sebaran → gerbang
│   ├── calibration.py      Platt + tabel reliabilitas + Brier/log-loss
│   ├── explain.py          narasi yang dibangun dari angka yang dipakai
│   ├── pipeline.py         perekat: gambar/manual → data lengkap → vonis
│   ├── extract/
│   │   ├── schema.py         skema JSON; SETIAP medan angka boleh null
│   │   ├── claude_vision.py  structured outputs, claude-opus-5
│   │   ├── tesseract_ocr.py  cadangan offline
│   │   └── normalize.py      parsing angka, pencocokan nama tim
│   ├── fetch/              pengambilan otomatis + pesan human-in-the-loop
│   ├── api.py              FastAPI
│   ├── cli.py              antarmuka terminal
│   └── web/index.html      UI tanpa build step
├── scripts/backtest.py     kalibrasi + pengukuran kejujuran ambang
└── tests/                  83 test, semuanya stdlib
```

**Kenapa stack ini.** Inti mesinnya **nol dependensi** — bisa diuji, di-backtest,
dan ditempelkan ke mana saja tanpa memasang apa pun. Lapis I/O (FastAPI,
anthropic, OCR) diimpor malas, jadi kegagalan di lapis itu tidak pernah
menjatuhkan perhitungannya.

UI-nya satu berkas HTML tanpa build step, bukan Next.js. Untuk aplikasi
satu-halaman berisi satu formulir dan satu kartu hasil, toolchain npm menambah
ratusan megabita dan satu titik gagal, tanpa menambah apa pun yang terlihat
pengguna. Kontrak API-nya JSON biasa, jadi kalau nanti butuh Next.js, frontend
baru tinggal memanggil endpoint yang sama.

---

## Pembacaan screenshot

Jalur utama memakai Claude (`claude-opus-5`) dengan **structured outputs** — skema
dipaksakan di sisi server, jadi tidak ada balasan prosa yang harus di-regex.

Pertahanan utama melawan angka karangan ada di skemanya: **setiap medan angka
bertipe `["number", "null"]`.** Model yang dipaksa mengisi semua medan akan
mengarang ketika angkanya tidak terlihat, dan angka karangan yang masuk ke mesin
probabilitas keluar sebagai "keyakinan 87%". Prompt sistemnya menegaskan hal
yang sama, plus pemisahan kategori yang paling sering tertukar: 1H vs
pertandingan penuh, kandang vs tandang, musim ini vs musim lalu.

Semua gambar dikirim dalam **satu** permintaan, bukan satu per satu — kalau
tidak, kontradiksi antar-screenshot tidak akan pernah terdeteksi.

Ada juga medan `unreadable` untuk angka yang terlihat tapi ambigu, yang
diteruskan apa adanya ke pengguna.

**Cadangan:** tesseract lokal (`Provenance.OCR`, bobot 0,80). Ia tidak bisa
memisahkan kolom tim kiri dan kanan secara andal, jadi hasilnya sengaja
ditaruh semua di satu sisi dan ditandai perlu diperiksa — bukan ditebak.

---

## Pengambilan data otomatis, dan batasnya

Ini kendala nyata yang perlu diketahui sebelum berharap terlalu banyak:
**tidak ada sumber publik gratis yang menyediakan pemisahan corner babak
pertama.** football-data.co.uk memberi corner pertandingan penuh (kolom HC/AC)
tanpa kunci API; API-Football memisahkan babak hanya di paket berbayar.

Jadi pengambilan otomatis berfungsi sebagai **pengisi celah dan pemeriksa
silang**, bukan jalan pintas menuju PICK:

* angka pertandingan penuh memperbaiki estimasi μ dan mempersempit galat baku;
* tapi ditandai `DERIVED`, dan gerbang "data 1H asli" tetap menutup PICK.

Data 1H asli harus datang dari screenshot atau input manual. Kalau semua sumber
gagal, mesin menyebut persis apa yang kurang, per tim, dalam bahasa manusia —
bukan nama atribut Python.

---

## Memakainya

### Terminal

```bash
python -m corner1h.cli --manual example.json --scan          # sisir tujuh garis
python -m corner1h.cli --manual example.json --line 5.5      # satu garis saja
python -m corner1h.cli --image kartu1.png --image kartu2.png --hint "tim kiri tuan rumah"
python -m corner1h.cli --manual example.json --json          # keluaran JSON
python -m corner1h.cli --manual example.json --no-strict     # mode belajar
```

Odds dimasukkan lewat medan `odds` di berkas JSON:

```json
"odds": { "4.5": {"over": 2.90, "under": 1.40},
          "5.5": {"over": 4.60, "under": 1.18} }
```

`--no-strict` melonggarkan gerbang supaya Anda bisa melihat angka mentahnya.
Itu alat diagnosis, bukan mode bertaruh.

### Web

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...          # atau: ant auth login
uvicorn corner1h.api:app --port 8000
```

`GET /api/health` melaporkan pengekstrak mana yang siap dan apakah kalibrator
sudah terpasang. `POST /api/analyse` menerima unggahan gambar;
`POST /api/analyse-manual` menerima JSON dengan bentuk yang sama seperti
`example.json`; `POST /api/scan` menerima payload yang sama dan mengembalikan
ketujuh garis sekaligus beserta `best_pick`.

### Kalibrasi (wajib sebelum PICK pertama)

```bash
python scripts/backtest.py --simulate 6000              # uji mesinnya sendiri
python scripts/backtest.py --csv data/riwayat.csv --write
```

CSV yang dibutuhkan, satu baris per laga, urut waktu:

```
date,home,away,home_cf_1h,home_ca_1h,home_n,away_cf_1h,away_ca_1h,away_n,actual_1h_corners
```

Skrip memasang kalibrator pada 60% data pertama dan mengukurnya pada 40%
terakhir yang belum pernah dilihat, lalu mencetak tabel reliabilitas —
"model bilang X, kenyataan Y" — beserta berapa PICK yang terbit dan berapa
persen yang benar.

Mode `--simulate` **menolak** menulis koefisien: angka dari data sintetis tidak
boleh dipakai untuk pertandingan nyata.

---

## Test

```bash
python -m unittest discover -s tests -v
```

83 test, nol dependensi. Yang paling penting bukan test contoh-per-contoh
melainkan **test sifat menyeluruh** yang menyapu ratusan kombinasi masukan:

* tidak ada masukan mana pun yang menghasilkan PICK di bawah ambang;
* tanpa kalibrasi terpasang, tidak ada PICK sama sekali;
* tidak ada PICK yang berdiri di atas proyeksi yang terpotong batas;
* setiap keputusan meninggalkan jejak gerbang yang bisa dibaca;
* narasi penjelasan tidak pernah bertentangan dengan angka yang dipakai;
* ambang tetap dihormati di **ketujuh** garis, bukan hanya di 4,5;
* masukan yang tidak masuk akal menutup semua garis sekaligus, sehingga
  menyisir garis tidak bisa dipakai sebagai jalan memutar.

Dua bug nyata tertangkap oleh test ini saat pengembangan: pemeriksaan medan
wajib yang meloloskan NaN (`NaN is None` bernilai False, jadi data kosong lolos
sebagai data ada), dan normalisasi nama tim yang membuat "Manchester United" dan
"Manchester City" berbagi kunci yang sama — yang akan menggabungkan statistik dua
klub berbeda tanpa satu pun pesan galat.

---

## Yang tidak dilakukan aplikasi ini

* Tidak menjanjikan akurasi 90%. Sepak bola tidak menyediakannya, dan pasar
  corner 1H paling tidak menyediakannya.
* Tidak menerbitkan PICK sebelum kalibrasi diukur pada data nyata.
* Tidak mengarang angka ketika screenshot tidak terbaca — ia bertanya.
* Tidak memakai corner pertandingan penuh sebagai pengganti data babak pertama
  untuk keputusan PICK.
* Tidak menyamakan "paling aman" dengan "paling bernilai". Tanpa odds, sisir
  garis hanya memberi urutan keamanan, dan itu dinyatakan di layar.
* Tidak tahu soal cedera, rotasi, cuaca, atau kepentingan pertandingan. Semua itu
  memengaruhi corner babak pertama, dan tidak satu pun ada di modelnya.
