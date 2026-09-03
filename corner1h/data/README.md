# data/

`calibration.json` ditulis ke sini oleh `scripts/backtest.py --write`.

Berkas itu sengaja masuk `.gitignore`: koefisien kalibrasi hanya berlaku untuk
liga dan rentang musim tempat ia dipasang. Mewariskan koefisien orang lain
lewat git justru mengembalikan masalah yang mau dihindari — angka keyakinan yang
tidak berdasar.

Selama berkas ini belum ada, mesin menolak menerbitkan PICK sama sekali.
