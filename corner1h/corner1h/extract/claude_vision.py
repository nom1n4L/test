"""Ekstraksi screenshot memakai kemampuan penglihatan Claude.

Dipakai lewat Anthropic Python SDK dengan *structured outputs*
(``output_config.format``), bukan dengan meminta "balas JSON saja" di prompt.
Bedanya penting: structured outputs dipaksakan di sisi server, jadi tidak ada
kasus balasan berisi prosa yang harus di-regex.

Model default: ``claude-opus-5``. Fallback sisi server diaktifkan supaya
penolakan classifier tidak membuat pipeline mati begitu saja.
"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence

from .schema import EXTRACTION_SCHEMA, SYSTEM_PROMPT

__all__ = ["ClaudeVisionExtractor", "VisionUnavailable"]

DEFAULT_MODEL = "claude-opus-5"
#: Format gambar yang diterima Messages API.
_SUPPORTED = {"image/png", "image/jpeg", "image/gif", "image/webp"}


class VisionUnavailable(RuntimeError):
    """SDK tidak terpasang atau kredensial tidak tersedia."""


def _encode(path: str) -> Dict[str, Any]:
    media_type, _ = mimetypes.guess_type(path)
    if media_type == "image/jpg":
        media_type = "image/jpeg"
    if media_type not in _SUPPORTED:
        raise ValueError(
            f"format gambar tidak didukung untuk {path!r}: {media_type or 'tidak dikenali'}. "
            f"Gunakan salah satu dari {sorted(_SUPPORTED)}."
        )
    with open(path, "rb") as fh:
        data = base64.standard_b64encode(fh.read()).decode("utf-8")
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": media_type, "data": data},
    }


@dataclass
class ClaudeVisionExtractor:
    """Baca satu set screenshot menjadi satu dict terstruktur.

    Semua gambar dikirim dalam SATU permintaan, bukan satu per satu. Itu
    disengaja: preferensi pengguna menuntut screenshot diproses secara kolektif
    dan saling diperiksa silang. Memanggil model per gambar akan menghilangkan
    kemampuan mendeteksi kontradiksi antar-screenshot.
    """

    model: str = DEFAULT_MODEL
    max_tokens: int = 16000
    api_key: Optional[str] = None
    #: Aktifkan fallback sisi server (disarankan untuk Opus 5).
    server_side_fallback: bool = True

    def _client(self):
        try:
            import anthropic
        except ImportError as exc:  # pragma: no cover - bergantung lingkungan
            raise VisionUnavailable(
                "paket 'anthropic' belum terpasang. Jalankan: pip install anthropic"
            ) from exc
        try:
            return anthropic.Anthropic(api_key=self.api_key) if self.api_key else anthropic.Anthropic()
        except Exception as exc:  # pragma: no cover - bergantung lingkungan
            raise VisionUnavailable(f"gagal membuat klien Anthropic: {exc}") from exc

    @property
    def available(self) -> bool:
        """Cek murah: apakah SDK ada dan ada kredensial yang bisa dipakai."""
        try:
            import anthropic  # noqa: F401
        except ImportError:
            return False
        if os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"):
            return True
        # `ant auth login` menyimpan profil yang dibaca SDK otomatis.
        return os.path.isdir(os.path.expanduser("~/.config/anthropic"))

    def extract(
        self,
        image_paths: Sequence[str],
        *,
        hint: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Kembalikan dict sesuai ``EXTRACTION_SCHEMA``.

        ``hint`` adalah konteks tambahan dari pengguna, misalnya "tim kiri adalah
        tuan rumah" atau "kartu kedua statistik musim lalu".
        """
        if not image_paths:
            raise ValueError("butuh minimal satu gambar")

        client = self._client()
        content: List[Dict[str, Any]] = []
        for idx, path in enumerate(image_paths, start=1):
            content.append({"type": "text", "text": f"Screenshot {idx}: {os.path.basename(path)}"})
            content.append(_encode(path))

        instruction = (
            "Baca semua screenshot di atas sebagai SATU pertandingan. Periksa silang antar-gambar. "
            "Isi null untuk apa pun yang tidak terlihat jelas. Jangan mengarang."
        )
        if hint:
            instruction += f"\n\nKonteks dari pengguna: {hint}"
        content.append({"type": "text", "text": instruction})

        kwargs: Dict[str, Any] = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "system": SYSTEM_PROMPT,
            "messages": [{"role": "user", "content": content}],
            "thinking": {"type": "adaptive"},
            "output_config": {
                "format": {
                    "type": "json_schema",
                    "schema": EXTRACTION_SCHEMA,
                }
            },
        }
        if self.server_side_fallback:
            kwargs["betas"] = ["server-side-fallback-2026-07-01"]
            kwargs["fallbacks"] = "default"
            response = client.beta.messages.create(**kwargs)
        else:
            response = client.messages.create(**kwargs)

        if getattr(response, "stop_reason", None) == "refusal":
            details = getattr(response, "stop_details", None)
            category = getattr(details, "category", None) if details else None
            raise VisionUnavailable(
                f"permintaan ditolak classifier (kategori: {category}). "
                "Coba screenshot lain atau masukkan angkanya manual."
            )

        return self._parse(response)

    @staticmethod
    def _parse(response: Any) -> Dict[str, Any]:
        """Ambil JSON dari balasan.

        Dengan structured outputs, isinya sudah dijamin JSON valid — tapi kita
        tetap memeriksa, karena balasan bisa terpotong kalau ``max_tokens``
        terlampaui.
        """
        parsed = getattr(response, "parsed_output", None)
        if isinstance(parsed, dict):
            return parsed

        chunks: List[str] = []
        for block in getattr(response, "content", []) or []:
            if getattr(block, "type", None) == "text":
                chunks.append(block.text)
        text = "".join(chunks).strip()
        if not text:
            raise ValueError(
                f"balasan kosong (stop_reason={getattr(response, 'stop_reason', None)!r}). "
                "Kalau stop_reason='max_tokens', naikkan max_tokens."
            )
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"balasan bukan JSON valid: {text[:400]}") from exc
