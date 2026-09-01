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
