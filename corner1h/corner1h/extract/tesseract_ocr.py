"""Cadangan OCR lokal memakai tesseract, untuk pemakaian tanpa jaringan.

Ini sengaja diperlakukan sebagai jalur kelas dua. Tesseract membaca tabel
statistik bandar jauh lebih buruk daripada model penglihatan: label dan angka
sering tertukar kolom, dan yang lebih berbahaya, ia tidak bisa membedakan kolom
"babak 1" dari kolom "pertandingan penuh". Karena itu hasil jalur ini ditandai
``Provenance.OCR`` (keandalan 0,80) dan pada praktiknya hampir selalu berujung
SKIP — yang memang benar, karena angkanya memang kurang bisa dipercaya.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence

from .normalize import find_labelled_number, parse_number

__all__ = ["TesseractExtractor", "OcrUnavailable"]


class OcrUnavailable(RuntimeError):
    """pytesseract/Pillow atau biner tesseract tidak tersedia."""


_CORNER_1H_FOR = ("corner 1h", "1h corner", "corners 1st half", "pojok babak 1", "ht corners for")
_CORNER_1H_AGAINST = ("corner 1h lawan", "1h corners against", "corners against 1st half")
_CORNER_FT_FOR = ("corners for", "corner dibuat", "total corners")
_CORNER_FT_AGAINST = ("corners against", "corner dikebobolan")
_SHOTS = ("shots", "tembakan", "total shots")
_DANGEROUS = ("dangerous attacks", "serangan berbahaya")
_POSSESSION = ("possession", "penguasaan bola", "ball possession")
_MATCHES = ("matches", "played", "laga", "jumlah pertandingan")


@dataclass
class TesseractExtractor:
    """Ekstraksi berbasis regex di atas teks mentah tesseract."""

    lang: str = "eng+ind"
    #: Perbesar gambar sebelum OCR — angka kecil di kartu statistik jauh lebih
    #: terbaca pada 2x.
    upscale: int = 2

    @property
    def available(self) -> bool:
        try:
            import pytesseract
            from PIL import Image  # noqa: F401
        except ImportError:
            return False
        try:
            pytesseract.get_tesseract_version()
            return True
        except Exception:
            return False

    def _text(self, path: str) -> str:
        try:
            import pytesseract
            from PIL import Image, ImageOps
        except ImportError as exc:  # pragma: no cover - bergantung lingkungan
            raise OcrUnavailable(
                "butuh 'pytesseract' dan 'Pillow'. Jalankan: pip install pytesseract pillow"
            ) from exc

        img = Image.open(path)
        img = ImageOps.exif_transpose(img).convert("L")
        if self.upscale > 1:
            img = img.resize((img.width * self.upscale, img.height * self.upscale))
        img = ImageOps.autocontrast(img)
        try:
            return pytesseract.image_to_string(img, lang=self.lang)
        except Exception:
            # Paket bahasa Indonesia mungkin tidak terpasang — coba bahasa Inggris.
            return pytesseract.image_to_string(img)

    def extract(self, image_paths: Sequence[str], *, hint: Optional[str] = None) -> Dict[str, Any]:
        """Kembalikan dict berbentuk sama dengan ``EXTRACTION_SCHEMA``.

        Karena OCR tidak bisa memisahkan tim kiri dan kanan secara andal, semua
        angka yang ketemu ditaruh di ``home`` dan ``away`` dibiarkan kosong.
        Mesin akan menganggap data tidak lengkap dan meminta ke pengguna — jauh
        lebih baik daripada menebak tim mana yang mana.
        """
        lines: List[str] = []
        for path in image_paths:
            lines.extend(ln.strip() for ln in self._text(path).splitlines() if ln.strip())

        home = {
            "name": None,
            "matches_sampled": find_labelled_number(lines, *_MATCHES),
            "corners_for_1h": find_labelled_number(lines, *_CORNER_1H_FOR),
            "corners_against_1h": find_labelled_number(lines, *_CORNER_1H_AGAINST),
            "corners_for_1h_venue": None,
            "corners_against_1h_venue": None,
            "corners_for_ft": find_labelled_number(lines, *_CORNER_FT_FOR),
            "corners_against_ft": find_labelled_number(lines, *_CORNER_FT_AGAINST),
            "corners_for_ft_venue": None,
            "corners_against_ft_venue": None,
            "shots_ft": find_labelled_number(lines, *_SHOTS),
            "shots_on_target_ft": None,
            "dangerous_attacks_ft": find_labelled_number(lines, *_DANGEROUS),
            "possession_pct": find_labelled_number(lines, *_POSSESSION),
            "corner_1h_history": None,
        }
        away = {k: (None if k != "name" else None) for k in home}

        return {
            "home": home,
            "away": away,
            "league": None,
            "kickoff": None,
            "league_corners_1h_per_team": None,
            "h2h_corner_1h": None,
            "unreadable": [
                "OCR lokal tidak bisa memisahkan kolom tim tuan rumah dan tamu secara andal; "
                "semua angka ditaruh di sisi tuan rumah dan perlu diperiksa manual."
            ],
            "confidence": 0.45,
            "notes": "Diekstrak dengan tesseract (jalur cadangan). Keandalan rendah.",
            "_raw_text": "\n".join(lines),
        }
