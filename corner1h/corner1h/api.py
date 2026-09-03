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
from .engine import CornerEngine, EngineConfig
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


def _config(
    threshold: Optional[float] = None,
    strict: bool = True,
    line: Optional[float] = None,
) -> EngineConfig:
    cfg = EngineConfig(calibrator=Calibrator.load(CALIBRATION_PATH))
    if threshold is not None:
        cfg.threshold = float(threshold)
    if line is not None:
        cfg.line = float(line)
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
    line: float = Form(default=4.5),
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
            config=_config(threshold, strict, line),
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
        config=_config(
            payload.get("threshold"),
            bool(payload.get("strict", True)),
            payload.get("line"),
        ),
    )
    result.extraction_notes.extend(notes)
    return JSONResponse(result.to_dict())


@app.post("/api/scan")
def scan(payload: Dict[str, Any]) -> JSONResponse:
    """Nilai setiap garis yang ditawarkan untuk satu pertandingan.

    Menerima payload yang sama dengan ``/api/analyse-manual``. Kalau ``odds``
    disertakan, hasilnya diurutkan berdasarkan nilai harapan; kalau tidak,
    berdasarkan keyakinan — dan pemanggilnya perlu tahu bahwa urutan tanpa odds
    selalu dimenangkan garis terjauh, yang bayarannya paling kecil.
    """
    try:
        match, notes = build_match_input(payload, Provenance.MANUAL)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"payload tidak valid: {exc}") from exc

    cfg = _config(payload.get("threshold"), bool(payload.get("strict", True)))
    verdicts = CornerEngine(cfg).scan_lines(match)
    picks = [v for v in verdicts if v.decision.value.startswith("PICK")]
    return JSONResponse(
        {
            "match": verdicts[0].match if verdicts else "",
            "expected_corners_1h": round(verdicts[0].expected_corners_1h, 3) if verdicts else None,
            "vmr": round(verdicts[0].vmr, 3) if verdicts else None,
            "vmr_source": verdicts[0].weights.get("vmr_source") if verdicts else None,
            "ranked_by": "expected_value" if any(v.expected_value is not None for v in verdicts)
                         else "confidence",
            "lines": [v.to_dict() for v in verdicts],
            "best_pick": picks[0].to_dict() if picks else None,
            "notes": notes,
        }
    )


@app.get("/")
def index() -> FileResponse:
    return FileResponse(os.path.join(WEB_DIR, "index.html"))
