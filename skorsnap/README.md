# Skorsnap

Kirim screenshot statistik pertandingan, dapat prediksi dan rekomendasi market.
Centang beberapa pertandingan, dapat parlay lengkap dengan peluang gabungannya.

**Alurnya:** screenshot → analisa → rekomendasi → centang → parlay.

## Cara pakai

1. **Pengaturan** → tempel kunci Gemini dari `aistudio.google.com` (gratis, cukup
   akun Google, tanpa kartu kredit)
2. **+ Tambah Pertandingan** → pilih screenshot dari galeri (boleh beberapa
   gambar untuk satu pertandingan)
3. Tekan **Analisa** → keluar prediksi, market terbaik, dan odds impasnya
4. Ulangi untuk pertandingan lain
5. **Centang** yang mau digabung → **Lihat parlay**

## Kenapa Gemini

Tier gratisnya nyata dan cukup untuk pemakaian pribadi. Karena API-nya dipanggil
langsung lewat HTTP tanpa library klien, aplikasinya juga tinggal **1,1 MB** —
sebelumnya 3,4 MB waktu masih membundel SDK.

Balasannya dikunci dengan `responseSchema`, jadi model tidak bisa mengirim bentuk
yang tidak dikenali parser. Suhunya disetel 0,15: membaca angka dari tabel bukan
tugas kreatif, dan screenshot yang sama sebaiknya memberi jawaban yang sama.

Daftar model **diambil langsung dari Google**, tidak ditebak. Nama model berubah
dari waktu ke waktu dan ketersediaannya berbeda tiap kunci, jadi nama yang
di-hardcode akan gagal dengan 404 yang tidak memberi tahu apa pun. Tekan **Cek
model yang tersedia** di Pengaturan — gratis, tidak memakai kuota.

Kalau bacaannya meleset, naikkan ke model Pro; kalau kena batas kuota, turunkan
ke Flash.

## Aturan yang tidak bisa dilanggar

Hanya angka yang **terlihat di screenshot-mu** yang dipakai. Model bahasa akan
dengan senang hati mengarang rata-rata gol yang terdengar masuk akal dari
ingatannya yang sudah basi, dan di layar angka karangan itu tidak bisa dibedakan
dari angka asli. Jadi prompt-nya melarang itu, dan meminta model menyebutkan
kembali:

- **statistik apa saja yang benar-benar dibaca** dari gambar
- **statistik apa yang dicari tapi tidak ada** di gambar

Keduanya ditampilkan di tiap pertandingan, di bagian *"Yang Dibaca dari
Gambarmu"*. Itulah yang membuat pendekatan ini lebih baik daripada menebak — dan
cara kamu memeriksanya.

## Matematika parlay dihitung aplikasi, bukan model

Model bahasa yang diminta menggabungkan enam peluang biasanya menghasilkan angka
yang terdengar masuk akal dan salah. Padahal justru angka itu yang harus tepat,
jadi aplikasi yang menghitungnya:

| Kasus | Hasil |
|---|---|
| 4 leg @ 80% | tembus semua **41%** — 1 dari 2 |
| 6 leg @ 75% | tembus semua **18%** — 1 dari 6 |

Dan margin bandar ikut dikalikan tiap leg (6,03% per leg, diukur dari 7.314 harga
1X2 Bet365). Akibatnya imbal hasil harapan parlay **tidak bergantung pada sebagus
apa pilihanmu**, hanya pada berapa banyak leg-nya:

| Leg | Rugi rata-rata |
|---|---|
| 2 | −11% |
| 4 | −21% |
| 6 | −30% |

Diuji di `CoreTest`: tiga leg "aman" (peluang gabungan 72,1%) dan tiga leg
berisiko (17,2%) menghasilkan imbal hasil harapan yang **persis sama**, 83,9%.

## Market yang dianalisis

Dua mode, dipilih sebelum menekan Analisa.

**Analisis Match** — 1X2, Double Chance, Total Gol (0.5–4.5), Total Babak 1,
Total per Tim, BTTS, "minimal satu tim cetak 2+ gol", Kombinasi Hasil + Total
(1X & Over 2.5, dst), Handicap Asia (±0.25, ±0.5, ±0.75, ±1), Handicap Eropa.

**Analisis Corner** — Total Corner FT (7.5–11.5), Corner Babak 1, Corner per Tim,
dan siapa yang unggul corner.

Hasilnya dikelompokkan per jenis dan bisa dilipat, karena empat puluh baris dalam
satu daftar tidak terbaca. Ada dua urutan: **paling aman** (peluang tertinggi) dan
**bayaran terbesar** (peluang terendah, bayaran tertinggi).

Tiap baris menampilkan peluang **dan odds impasnya**. Pasangan itulah inti value
betting: pasang kalau bandar membayar di atas angka impas, tinggalkan kalau di
bawah — sebesar apa pun persentasenya terlihat nyaman. Aplikasi tidak bisa melihat
harga bandarmu, jadi ia memberi angka pembandingnya, bukan berpura-pura menilai.

**Rekomendasi utama tetap satu.** Empat puluh market bukan empat puluh saran.

## Long capture

Screenshot panjang satu halaman penuh justru input terbaik untuk aplikasi ini,
tapi ukurannya menipu: 1080×20.000 piksel butuh **82 MB** sekali decode, melawan
jatah memori aplikasi Android yang sering cuma 128 MB. Versi awal men-decode-nya
utuh hanya untuk menggambar pratinjau 96dp — dan crash.

Sekarang tidak ada satu pun bitmap berukuran penuh yang pernah dimuat:

- **Pratinjau** di-decode dengan `inSampleSize`, jadi biayanya kilobita.
- **Pengiriman** memotong gambar jadi beberapa band lewat `BitmapRegionDecoder`,
  satu per satu langsung dari berkasnya, tanpa menyentuh sisanya.

Band-nya **beresolusi penuh dan saling tumpang tindih 120 piksel**, jadi tidak ada
baris angka yang terpotong di sambungan. Resolusinya sengaja tidak dikecilkan:
model membaca tabel ini per petak 768 piksel, jadi memperkecil justru yang akan
mengubah `1.42` yang terbaca jelas menjadi tebakan.

| Tinggi screenshot | Jadi berapa band |
|---|---|
| ≤ 2.600 | 1 (tidak dipotong) |
| 8.000 | 6 |
| 20.000 | 10 |
| 45.000 | 10 (band-nya yang ditinggikan) |

## Rapor akurasi

Tiap pertandingan bisa ditandai **Tembus** atau **Meleset** setelah selesai main.
Dari situ aplikasi menghitung satu angka yang tidak bisa didapat dari ingatan:
**akurasi nyata dibandingkan akurasi yang dijanjikan model**.

Hit rate sendirian mengundang orang menarik garis lurus dari satu rentetan bagus.
Disandingkan dengan angka yang diklaim model, ia menjawab satu-satunya pertanyaan
yang penting: apakah persentasenya bisa dipercaya apa adanya.

Rapornya juga menyebut selang kepercayaan, karena sampel kecil membuat angka
terlihat jauh lebih pasti daripada sebenarnya:

| Jumlah hasil | Ketelitian |
|---|---|
| 12 | ±22 poin |
| 120 | ±7 poin |

Sebelas dari dua belas terlihat meyakinkan, tapi akurasi sejatinya masih ada di
antara 65% dan 99% — belum memisahkan bagus dari beruntung. Di sekitar 50 hasil
angkanya baru mulai berarti.

## Yang tidak bisa dijanjikan

Prediksi yang keluar adalah **peluang**, bukan kepastian. Peluang 80% tetap
meleset 1 dari 5 kali, dan itu bukan tanda ada yang rusak — itu arti dari angka
80%.

Tidak ada versi aplikasi ini, atau aplikasi mana pun, yang membuat parlay pasti
menang.

## Test

| Test | Gunanya |
|---|---|
| `CoreTest` | Peluang parlay, rumus imbal hasil, pembacaan balasan JSON, kelengkapan skema, dan statistik rapor |

Yang diuji sengaja hanya dua: menggabungkan peluang, dan mengubah balasan model
jadi angka. Membaca gambar adalah tugas model dan tidak bisa di-unit-test;
menghitung slip adalah tugas aplikasi, dan angka parlay yang salah akan dipercaya
begitu saja.

## Membangun sendiri

```bash
cd skorsnap
echo "sdk.dir=/path/ke/android-sdk" > local.properties
./gradlew assembleRelease
```
