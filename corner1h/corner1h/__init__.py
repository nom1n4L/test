"""corner1h — mesin prediksi pasar sepak pojok babak pertama (1H) Over/Under 4.5.

Paket ini sengaja dibagi dua lapis:

* **Inti (murni stdlib)** — ``distributions``, ``models``, ``engine``,
  ``calibration``, ``confidence``, ``explain``. Tidak butuh satu pun dependensi
  eksternal, sehingga bisa diuji, di-backtest, dan ditempelkan ke mana saja.
* **Lapis I/O (dependensi opsional)** — ``extract`` (Claude vision / OCR),
  ``fetch`` (pengambilan data otomatis), ``api`` (FastAPI). Semuanya diimpor
  malas supaya inti tetap bisa jalan tanpa paket tambahan apa pun.
"""

__version__ = "0.1.0"

from .models import (  # noqa: F401
    DataQuality,
    Field,
    MatchInput,
    Provenance,
    TeamStats,
    Verdict,
)

__all__ = [
    "DataQuality",
    "Field",
    "MatchInput",
    "Provenance",
    "TeamStats",
    "Verdict",
    "__version__",
]
