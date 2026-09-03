"""Skema JSON untuk ekstraksi screenshot.

Satu keputusan desain menentukan seluruh keandalan sistem ini: **setiap medan
angka boleh bernilai ``null``.** Model penglihatan yang dipaksa mengisi semua
medan akan mengarang angka ketika angkanya tidak terlihat — dan angka karangan
yang masuk ke mesin probabilitas akan keluar sebagai "keyakinan 87%".

Karena itu skema di bawah memakai ``["number", "null"]`` di mana-mana, dan
prompt sistem secara eksplisit memerintahkan ``null`` untuk apa pun yang tidak
terbaca jelas. Medan ``unreadable`` menampung catatan tentang angka yang terlihat
tapi ambigu, supaya bisa ditanyakan ke pengguna.
"""

from __future__ import annotations

from typing import Any, Dict

__all__ = ["EXTRACTION_SCHEMA", "SYSTEM_PROMPT"]


def _num(desc: str) -> Dict[str, Any]:
    return {"type": ["number", "null"], "description": desc}


_TEAM_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "name": {"type": ["string", "null"], "description": "Nama tim persis seperti tertulis."},
        "matches_sampled": _num("Jumlah laga yang menjadi dasar rata-rata di kartu ini."),
        "corners_for_1h": _num("Rata-rata corner BABAK PERTAMA yang dibuat tim, per laga."),
        "corners_against_1h": _num("Rata-rata corner BABAK PERTAMA yang dikebobolan tim, per laga."),
        "corners_for_1h_venue": _num(
            "Corner 1H dibuat, khusus kandang (tuan rumah) atau tandang (tamu)."
        ),
        "corners_against_1h_venue": _num(
            "Corner 1H dikebobolan, khusus kandang (tuan rumah) atau tandang (tamu)."
        ),
        "corners_for_ft": _num("Rata-rata corner PERTANDINGAN PENUH yang dibuat, per laga."),
        "corners_against_ft": _num("Rata-rata corner PERTANDINGAN PENUH yang dikebobolan, per laga."),
        "corners_for_ft_venue": _num("Corner pertandingan penuh dibuat, khusus kandang/tandang."),
        "corners_against_ft_venue": _num("Corner pertandingan penuh dikebobolan, khusus kandang/tandang."),
        "shots_ft": _num("Rata-rata total tembakan per laga."),
        "shots_on_target_ft": _num("Rata-rata tembakan tepat sasaran per laga."),
        "dangerous_attacks_ft": _num("Rata-rata serangan berbahaya per laga."),
        "possession_pct": _num("Rata-rata penguasaan bola dalam persen (0-100)."),
        "corner_1h_history": {
            "type": ["array", "null"],
            "items": {"type": "integer"},
            "description": (
                "Jumlah corner babak pertama per laga, kalau daftar laga-per-laga terlihat. "
                "Urutan terbaru lebih dulu. null kalau tidak ada daftarnya."
            ),
        },
    },
    "required": [
        "name",
        "matches_sampled",
        "corners_for_1h",
        "corners_against_1h",
        "corners_for_1h_venue",
        "corners_against_1h_venue",
        "corners_for_ft",
        "corners_against_ft",
        "corners_for_ft_venue",
        "corners_against_ft_venue",
        "shots_ft",
        "shots_on_target_ft",
        "dangerous_attacks_ft",
        "possession_pct",
        "corner_1h_history",
    ],
}

EXTRACTION_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "home": _TEAM_SCHEMA,
        "away": _TEAM_SCHEMA,
        "league": {"type": ["string", "null"]},
        "kickoff": {"type": ["string", "null"], "description": "Waktu kickoff seperti tertulis."},
        "league_corners_1h_per_team": _num("Rata-rata corner 1H per tim di liga ini, kalau terlihat."),
        "h2h_corner_1h": {
            "type": ["array", "null"],
            "items": {"type": "integer"},
            "description": "Corner babak pertama pada laga head-to-head, kalau terlihat.",
        },
        "unreadable": {
            "type": "array",
            "items": {"type": "string"},
            "description": (
                "Daftar angka yang TERLIHAT tapi tidak terbaca pasti (terpotong, buram, tumpang "
                "tindih). Tulis apa yang dicari dan kenapa ragu. Kosongkan kalau tidak ada."
            ),
        },
        "confidence": {
            "type": "number",
            "description": (
                "Keyakinan pembacaan menyeluruh, 0..1. Turunkan kalau gambar buram, label ambigu, "
                "atau Anda harus menebak medan mana yang mana."
            ),
        },
        "notes": {
            "type": ["string", "null"],
            "description": "Catatan penting: kontradiksi antar-screenshot, satuan tak lazim, dll.",
        },
    },
    "required": [
        "home",
        "away",
        "league",
        "kickoff",
        "league_corners_1h_per_team",
        "h2h_corner_1h",
        "unreadable",
        "confidence",
        "notes",
    ],
}


SYSTEM_PROMPT = """\
Anda adalah pengekstrak data statistik sepak bola. Tugas Anda HANYA membaca \
angka dari gambar dan mengembalikannya sebagai JSON. Anda tidak memprediksi \
apa pun.

ATURAN MUTLAK:

1. JANGAN PERNAH mengarang angka. Kalau sebuah nilai tidak terlihat di gambar, \
   isi dengan null. Mengisi null tidak dianggap kegagalan — itu perilaku yang benar.
2. JANGAN mengambil angka dari pengetahuan Anda tentang tim tersebut. Hanya yang \
   ada di piksel gambar.
3. Bedakan dengan tegas:
   - BABAK PERTAMA (1H / HT / babak 1) vs PERTANDINGAN PENUH (FT / total)
   - KANDANG vs TANDANG vs KESELURUHAN
   - MUSIM INI vs MUSIM LALU
   - 5 LAGA TERAKHIR vs 10 LAGA TERAKHIR vs SEMUSIM
   Kalau sebuah angka jelas milik kategori pertandingan penuh, JANGAN taruh di \
   medan 1H. Kalau ragu kategorinya, isi null dan catat di `unreadable`.
4. Angka corner harus PER LAGA (rata-rata). Kalau gambar menunjukkan TOTAL musim, \
   bagi dengan jumlah laga dan sebutkan itu di `notes`. Kalau jumlah laganya \
   tidak diketahui, isi null.
5. Tim tuan rumah adalah yang disebut lebih dulu / di sebelah kiri. Kalau tidak \
   bisa dipastikan, catat di `notes`.
6. Untuk `*_venue`: isi statistik KANDANG untuk tim tuan rumah dan statistik \
   TANDANG untuk tim tamu. Jangan tertukar.
7. Kalau beberapa gambar saling bertentangan, pakai yang paling spesifik \
   (1H > penuh, venue > keseluruhan, musim ini > musim lalu) dan tulis \
   kontradiksinya di `notes`.
8. `confidence` harus mencerminkan kualitas pembacaan sungguhan. Gambar buram, \
   label ambigu, atau tebakan soal kategori berarti nilainya di bawah 0,8.
"""
