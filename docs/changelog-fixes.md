# Changelog — Bug Fixes (diagnostic-driven)

> Status tracking untuk perbaikan dari diagnostic/real-device feedback.
> Commit TA: TUNNEL HTTPS default (SandroProxy-style) + Bug 1 fix + fallback cert.

## Bug 1 — CRITICAL: Plain HTTP empty host → false "Bad Request" ✅ FIXED
- **File:** `ProxyServer.kt` → `handlePlainHttp()`
- **Akar masalah:** saat client proxy-unaware (mis. Lightning Browser custom proxy) kirim request relatif-form `GET /path` tanpa `Host` header, `host` jatuh ke `""` → URL `http:///...` → proxy ngirim 400 kosong.
- **Fix:**
  1. Coba `uri.host` dulu (absolute-form OK).
  2. Kalau null → cari header `Host` case-insensitive dari `req.headers().entries()`.
  3. Kalau tetep nggak ada → **jangan** kirim 400 kosong; kirim `respondBadRequest(ctx, reason)` dengan body teks explain (menjadi jelas beda dari error origin) + **Logcat dump** raw request (tag `ProxyServer`) biar bisa di-debug dari History.
- **Call-site lain:** `respondSimple(BAD_REQUEST)` diganti panggilan `respondBadRequest(...)` dengan reason eksplisit ("unroutable request", dst.) di `relayToOrigin()`.
- **Verifikasi:** history nampil domain asli, bukan `/bad-request`; kalau tetap `/bad-request`, logcat perlihatkan request-line + headers client.

## Bug 2 — Foreground service / notifikasi kadang tak muncul ⏳ PENDING
- Belum dieksekusi. Rencana: `POST_NOTIFICATIONS` runtime request di `MainActivity` (API 33+), indikator "Proxy: Running" in-app via bound service, try/catch `server.start()` di `startProxy()` dengan error state.

## Bug 3 — UX warning proxy system-wide ⏳ PENDING
- Belum dieksekusi. Rencana: dialog peringatan sekali saat pertama kali enable intercept / buka CA-share.

## Fitur tambahan — HTTPS TUNNEL mode (default) + custom PKCS#12
- **Motivasi:** (а) akses HTTPS lambat/gagal karena MITM require trust CA di device; (b) konsep SandroProxy "pakai sertifikat dari direktori".
- **`ProxySettings.httpsMode`** default `TUNNEL`: CONNECT di-relay blind byte-to-byte ke origin → **no certificate needed**, browsing langsung lancar, tetap tercatat di HTTP History.
- **MITM mode** tetap tersedia + prefer `proxy.p12` (`CertificateAuthority.customPkcs12Context()`): letakkan PKCS#12 file di `filesDir/proxy.p12`, dipakai untuk semua MITM connection tanpa perlu generate leaf per-host.
- **Switch UI** belum ada (mode default TUNNEL); bisa diubah kode `ProxySettings.httpsMode`.