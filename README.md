# Lacak Gerak (tahap 1: cek jalur build)

Tujuan tahap ini: pastikan HTML yang sekarang bisa jadi APK lewat GitHub Actions.

## Cara (di Termux)
1. Buat repo kosong di github.com (nama bebas, mis. lacak-gerak).
2. pkg install git unzip
3. unzip lacak-gerak-proyek.zip -d lacak-gerak && cd lacak-gerak
4. git init && git branch -M main && git add . && git commit -m "tahap 1"
5. git remote add origin https://github.com/USERNAME/lacak-gerak.git
6. git push -u origin main   (password = Personal Access Token GitHub)
7. Di GitHub: tab Actions -> Build APK -> tunggu hijau -> unduh artifact "lacak-gerak-debug" -> ekstrak -> pasang app-debug.apk.

Kalau merah, kirim log error-nya ke Claude.

Catatan: appId (com.lacakgerak.app) permanen setelah rilis di Play Store. Ganti sebelum rilis kalau mau.
