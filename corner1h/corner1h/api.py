"""Backend FastAPI.

Lapis tipis di atas ``pipeline.analyse``. Semua logika ada di inti; file ini
hanya mengurus HTTP, unggahan berkas, dan menyajikan UI.

Jalankan:

    uvicorn corner1h.api:app --reload --port 8000
"""

from __future__ import annotations

import os
import shutil
import tempfile
from typing import Any, Dict, List, Optional

try:
    from fastapi import FastAPI, File, Form, HTTPException, UploadFile
    from fastapi.responses import FileResponse, JSONResponse
except ImportError as exc:  # pragma: no cover - bergantung lingkungan
    raise SystemExit(
        "FastAPI belum terpasang. Jalankan: pip install -r requirements.txt"
    ) from exc

from .calibration import Calibrator
from .engine import EngineConfig
from .extract import build_match_input
from .models import Provenance
from .pipeline import analyse

WEB_DIR = os.path.join(os.path.dirname(__file__), "web")
CALIBRATION_PATH = os.environ.get(
    "CORNER1H_CALIBRATION",
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "calibration.json"),
)
#: Batas ukuran unggahan per berkas (8 MB) — screenshot statistik jauh di bawah ini.
MAX_UPLOAD_BYTES = 8 * 1024 * 1024

app = FastAPI(
    title="1H Corner Predictor",
    version="0.1.0",
    description="Prediksi sepak pojok babak pertama, khusus pasar Over/Under 4.5.",
)


def _config(threshold: Optional[float] = None, strict: bool = True) -> EngineConfig:
    cfg = EngineConfig(calibrator=Calibrator.load(CALIBRATION_PATH))
    if threshold is not None:
        cfg.threshold = float(threshold)
    if not strict:
        # Mode belajar: gerbang keras dilonggarkan supaya pengguna bisa melihat
        # angka mentahnya. Tidak pernah jadi default.
        cfg.require_fitted_calibration = False
        cfg.require_native_1h = False
        cfg.min_matches_for_pick = 3
    return cfg


@app.get("/api/health")
def health() -> Dict[str, Any]:
    from .extract import available_extractors

    cal = Calibrator.load(CALIBRATION_PATH)
    return {
        "status": "ok",
        "extractors": available_extractors(),
        "calibration": cal.to_dict(),
        "calibration_path": CALIBRATION_PATH,
        "warning": (
            None
            if cal.fitted
            else "Kalibrator belum dipasang: mesin akan menolak semua PICK sampai "
            "scripts/backtest.py dijalankan pada data historis."
        ),
    }


@app.post("/api/analyse")
async def analyse_endpoint(
    images: List[UploadFile] = File(default=[]),
    hint: Optional[str] = Form(default=None),
    autofetch: bool = Form(default=True),
    threshold: Optional[float] = Form(default=None),
    strict: bool = Form(default=True),
) -> JSONResponse:
    """Analisis dari unggahan screenshot."""
    tmpdir = tempfile.mkdtemp(prefix="corner1h-")
    paths: List[str] = []
    try:
        for upload in images:
            if not upload.filename:
                continue
            data = await upload.read()
            if len(data) > MAX_UPLOAD_BYTES:
                raise HTTPException(
                    status_code=413,
                    detail=f"{upload.filename} melebihi batas {MAX_UPLOAD_BYTES // (1024 * 1024)} MB",
                )
            dest = os.path.join(tmpdir, os.path.basename(upload.filename))
            with open(dest, "wb") as fh:
                fh.write(data)
            paths.append(dest)

        if not paths:
            raise HTTPException(status_code=400, detail="tidak ada gambar yang diunggah")

        result = analyse(
            images=paths,
            hint=hint,
            autofetch=autofetch,
            config=_config(threshold, strict),
        )
        return JSONResponse(result.to_dict())
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


@app.post("/api/analyse-manual")
def analyse_manual(payload: Dict[str, Any]) -> JSONResponse:
    """Analisis dari angka yang diketik manual.

    Bentuk ``payload`` sama dengan skema ekstraksi, sehingga UI bisa memakai
    satu bentuk data untuk kedua jalur — dan pengguna bisa menempel hasil
    ekstraksi, mengoreksi satu angka, lalu mengirim ulang.
    """
    try:
        match, notes = build_match_input(payload, Provenance.MANUAL)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"payload tidak valid: {exc}") from exc

    result = analyse(
        manual=match,
        autofetch=bool(payload.get("autofetch", True)),
        config=_config(payload.get("threshold"), bool(payload.get("strict", True))),
    )
    result.extraction_notes.extend(notes)
    return JSONResponse(result.to_dict())


@app.get("/")
def index() -> FileResponse:
    return FileResponse(os.path.join(WEB_DIR, "index.html"))
