"""Normalisasi angka dan nama tim dari teks mentah OCR."""

from __future__ import annotations

import re
import unicodedata
from typing import List, Optional

__all__ = ["parse_number", "normalize_team_name", "find_labelled_number", "team_key", "TEAM_ALIASES"]

#: Kesalahan OCR yang lazim pada digit.
_OCR_DIGIT_FIXES = str.maketrans({"O": "0", "o": "0", "l": "1", "I": "1", "|": "1", "S": "5", "B": "8"})


def parse_number(text: Optional[str]) -> Optional[float]:
    """Ambil satu angka dari teks, toleran terhadap koma desimal dan sampah OCR.

    Mengembalikan ``None`` alih-alih menebak ketika tidak ada angka yang masuk
    akal — sikap yang sama dengan pengekstrak vision.
    """
    if text is None:
        return None
    s = unicodedata.normalize("NFKC", str(text)).strip()
    if not s:
        return None
    s = s.translate(_OCR_DIGIT_FIXES)
    # "3,20" (gaya Indonesia) dan "3.20" sama-sama diterima; pemisah ribuan dibuang.
    s = re.sub(r"(?<=\d)[.,](?=\d{3}\b)", "", s)
    m = re.search(r"-?\d+(?:[.,]\d+)?", s)
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", "."))
    except ValueError:
        return None


def normalize_team_name(name: Optional[str]) -> str:
    """Rapikan nama tim untuk ditampilkan."""
    if not name:
        return ""
    s = unicodedata.normalize("NFKC", name).strip()
    s = re.sub(r"\s+", " ", s)
    return s


#: Imbuhan korporat yang tidak membedakan klub mana pun.
#:
#: Perhatikan apa yang TIDAK ada di sini: "united" dan "city". Membuang keduanya
#: akan membuat "Manchester United" dan "Manchester City" menjadi kunci yang
#: sama — kesalahan yang akan menggabungkan statistik dua klub berbeda tanpa
#: satu pun pesan galat. Kata yang membedakan tidak boleh dibuang; singkatan
#: ditangani lewat tabel alias di bawah.
_NOISE_TOKENS = r"\b(fc|afc|cfc|sc|ac|cf|ss|ssc|as|bk|if|fk|sk|club|calcio|cd|ud|rc)\b"

#: Singkatan lazim -> bentuk panjang. Sengaja eksplisit: menebak singkatan
#: secara algoritmis jauh lebih berbahaya daripada tabel yang bisa dibaca.
TEAM_ALIASES = {
    "man utd": "manchester united",
    "man united": "manchester united",
    "man city": "manchester city",
    "spurs": "tottenham hotspur",
    "tottenham": "tottenham hotspur",
    "wolves": "wolverhampton wanderers",
    "brighton": "brighton hove albion",
    "forest": "nottingham forest",
    "nottm forest": "nottingham forest",
    "west brom": "west bromwich albion",
    "sheff utd": "sheffield united",
    "sheff wed": "sheffield wednesday",
    "psg": "paris saint germain",
    "inter": "internazionale",
    "atletico": "atletico madrid",
    "atleti": "atletico madrid",
    "real": "real madrid",
    "barca": "barcelona",
    "bayern": "bayern munchen",
    "dortmund": "borussia dortmund",
    "bvb": "borussia dortmund",
    "persib": "persib bandung",
    "persija": "persija jakarta",
    "arema": "arema fc",
}


def team_key(name: Optional[str]) -> str:
    """Kunci pencocokan nama tim untuk menggabungkan sumber data.

    Tiga langkah: normalisasi (huruf kecil, tanpa aksen), perluasan singkatan
    lewat ``TEAM_ALIASES``, lalu pembuangan imbuhan korporat. Kata yang
    membedakan klub — "united", "city", "rovers" — sengaja dipertahankan.
    """
    s = normalize_team_name(name).lower()
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    s = re.sub(r"[^a-z0-9\s]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()

    # Alias dicoba pada bentuk penuh dulu, lalu setelah imbuhan dibuang, supaya
    # "Man Utd FC" ikut tertangkap.
    if s in TEAM_ALIASES:
        s = TEAM_ALIASES[s]
    else:
        stripped = re.sub(_NOISE_TOKENS, " ", s)
        stripped = re.sub(r"\s+", " ", stripped).strip()
        s = TEAM_ALIASES.get(stripped, stripped)

    s = re.sub(_NOISE_TOKENS, " ", s)
    return re.sub(r"[^a-z0-9]+", "", s)


def find_labelled_number(lines: List[str], *labels: str) -> Optional[float]:
    """Cari angka pada baris yang memuat salah satu label.

    Heuristik sederhana untuk keluaran tesseract: label dan nilainya hampir
    selalu berada di baris yang sama pada kartu statistik.
    """
    lowered = [(ln, ln.lower()) for ln in lines]
    for label in labels:
        needle = label.lower()
        for original, low in lowered:
            if needle in low:
                tail = original[low.index(needle) + len(needle) :]
                val = parse_number(tail)
                if val is not None:
                    return val
                val = parse_number(original)
                if val is not None:
                    return val
    return None
