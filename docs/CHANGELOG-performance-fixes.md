# Changelog — Implementasi Hasil Audit Performa

Referensi: `docs/proxy-performance-audit.md` (B1–B8).

## proxy core

### `proxy/InterceptStore.kt`
- **B1:** `interceptEnabled` default **false** (sebelumnya true — setiap request memblokir thread worker).
- **B2/B6:** `submit()` tidak lagi `join()` tanpa batas: menunggu maksimal `interceptTimeoutMs` (default 60 dtk), lalu **auto-forward** sehingga request yang terlupakan tidak bisa dead-lock executor.
- **B7:** history in-memory dibatasi 500 entri (evict oldest settled); Room tetap menyimpan semua.

### `proxy/ProxyServer.kt` (rewrite)
- **Bug kritis lama (baru ditemukan saat implementasi):** pipeline CONNECT lama menyisakan `ProxyFrontHandler` DI DEPAN codec baru, sehingga request hasil dekripsi TLS ditangani sebagai plain HTTP (`isHttps=false`, relay ke port 80!). Kini front handler dilepas dan tunnel pipeline tersusun benar: `[ssl, codec, aggregator, tunnel]`.
- **B5:** pembuatan leaf cert + `SslContext` server dipindah dari I/O event loop ke worker executor, dan **di-cache per host** (lihat `CertificateAuthority.serverSslContextFor`).
- **B3:** `OriginConnectionPool` — keep-alive pooling koneksi origin per `(host,port,tls)`; tidak ada lagi TCP+TLS handshake baru per subresource. Borrow gagal → retry sekali dengan koneksi baru. Satu request in-flight per koneksi pool agar pencocokan respons selalu benar.
- **B4:** response kini **streaming** chunk-per-chunk ke browser (`RelayJob`), sambil merekam salinan untuk UI hingga cap `MAX_RECORDED_BODY` (10 MB). Tidak ada lagi full-buffering 50 MB sebelum byte pertama sampai.
- **B6:** `CONNECT_TIMEOUT_MILLIS=5s`, idle timeout origin 30 dtk & klien 75 dtk (`IdleStateHandler`). Idle saat menunggu intercept tidak memutus koneksi (`awaitingUser` guard).
- Header hop-by-hop (RFC 7230) dibuang dua arah; `Transfer-Encoding` dipertahankan untuk framing chunked; close-delimited response ditutup benar; respons 1xx diteruskan.
- `interceptExecutor` dinaikkan 8 → 16 thread.

### `cert/CertificateAuthority.kt`
- Tambahan `serverSslContextFor(host)` dengan cache `SslContext`.

## UI (glassmorphism)

### Baru: `ui/theme/Glass.kt`
- Tema gelap `GlassColorScheme`, canvas gradien `GlassBackground` dengan blob blur dekoratif, modifier `glassCard()` (panel translusen + border halus). `Modifier.blur` no-op di bawah API 31.

### `ui/BurpLiteApp.kt`
- Navigasi bottom-tab ala Burp: **Traffic / Intercept / Repeater**, badge jumlah pending pada tab Intercept, overlay detail transaksi full-screen.

### `ui/TrafficListScreen.kt`
- Search bar URL, filter chip (ALL/2XX/3XX/ERR/HTTPS), kartu glass dengan badge method berwarna, ikon lock HTTPS, status berwarna, ukuran body.

### `ui/InterceptEditorScreen.kt`
- **Antrian pending** (fix B2): semua request yang di-pause tampil sebagai chip dan bisa dipilih; editor method/url/headers/body; tombol Forward/Drop per item + **Forward all/Drop all**.
- Detail transaksi bertab Request/Response dengan preview monospace (truncate 20k char).

### `ui/RepeaterScreen.kt`
- Glass styling, indikator sending, timeout HTTP di repeater.

### `ui/ProxyViewModel.kt`
- `_pending` menjadi **StateFlow<List>>** (queue), `forward/drop/forwardAll/dropAll`.
- Intercept default OFF; state `repeaterSending`; persist Room tetap sama.

## Catatan verifikasi
- Lingkungan pengembangan ini **tidak memiliki JDK/Android SDK**, sehingga belum dikompilasi. Struktur brace semua file `.kt` diverifikasi balanced dengan scanner. Build & uji manual: buka di Android Studio → `./gradlew :app:assembleDebug` → uji browsing normal (harus cepat, intercept OFF) → aktifkan Intercept → cek antrian pending.
