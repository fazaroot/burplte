# Audit Performa Proxy — burplte

> **Status:** Audit saja — tidak ada perubahan kode.
> **Tanggal:** 2026-08-22
> **Scope:** Seluruh jalur data proxy (`ProxyServer`, `InterceptStore`, `CertificateAuthority`, `ProxyForegroundService`, `ProxyViewModel`) serta konfigurasi (`AndroidManifest.xml`, `build.gradle.kts`).
> **Gejala yang dilaporkan:** Setelah Android dikonfigurasi memakai proxy burplte, website menjadi sangat lambat dimuat atau terasa *hang*.

---

## 1. Ringkasan Arsitektur

burplte adalah MITM proxy berbasis **Netty 4.1.111** yang berjalan sebagai foreground service di perangkat Android itu sendiri:

```
Browser/Apps di Android
        │  plain HTTP  /  CONNECT (HTTPS)
        ▼
ProxyServer (Netty, port 8080)
  bossGroup(1) ─► workerGroup(default)
   ├─ HttpServerCodec
   ├─ HttpObjectAggregator (50 MB!)
   └─ ProxyFrontHandler @ interceptExecutor(8 thread)
        │
        ▼
InterceptStore.submit()
 (BLOKIR thread bila intercept ON — default ON)
        │
        ▼
relayToOrigin():
  Bootstrap BARU per request → clientGroup(4)
  + DNS resolve blocking di event loop
  + TLS handshake baru (HTTPS)
  + HttpContentDecompressor
  + HttpObjectAggregator (50 MB!)  ← full buffering
        │ koneksi BARU per request, selalu ditutup
        ▼
Upstream origin server
```
### Alur lengkap Client → Proxy → TLS/HTTPS → Upstream → Response → Client

1. **Koneksi masuk** — `ProxyServer.start()` (`ProxyServer.kt:56-68`) menerima koneksi via `ServerBootstrap` (boss 1 thread, worker default = 2×core), pipeline awal: `HttpServerCodec` → `HttpObjectAggregator(50MB)` → `ProxyFrontHandler` yang berjalan di `interceptExecutor` (`DefaultEventExecutorGroup(8)`).
2. **Plain HTTP** — `ProxyFrontHandler.handlePlainHttp()` (`:136-140`) mem-parse URI absolut, lalu memanggil `forwardIntercepted()`.
3. **HTTPS / CONNECT** — `handleConnect()` (`:102-121`) membalas `200 Connection Established`, melepas codec lama, menempelkan `SslHandler` server dengan **leaf certificate yang di-mint on-the-fly** per host (`CertificateAuthority.certFor`), lalu menambahkan `TunnelledHttpsHandler`. Traffic TLS dari klien didekripsi, sehingga request di dalam tunnel diproses sama seperti HTTP biasa.
4. **Interception** — `forwardIntercepted()` (`:144-172`) membuat `HttpTransaction`, mempublikasikannya lewat `InterceptStore.submit()`. **Jika `interceptEnabled == true` (default!), thread pemanggil BLOCKIR di `tx.resumeSignal.join()` sampai user menekan Forward/Drop di UI** (`InterceptStore.kt:47-54`).
5. **Relay ke origin** — `relayToOrigin()` (`:175-252`) membuat `Bootstrap` **baru setiap request**, connect ke host asli (DNS resolve sinkron oleh Netty di event loop), dan untuk HTTPS melakukan **TLS client handshake baru** dengan `InsecureTrustManagerFactory`.
6. **Response** — response origin melewati `HttpContentDecompressor` + `HttpObjectAggregator(50MB)` → **seluruh body dibuffer penuh ke `ByteArray`** (`:209-210`), disalin lagi ke `Unpooled.wrappedBuffer` (`:216`), ditulis balik ke klien, lalu **koneksi origin selalu ditutup** (`originCtx.close()`, `:221`).
7. **Logging/persistence** — `InterceptStore.notifyComplete(tx)` memicu listener di `ProxyViewModel` (`ProxyViewModel.kt:43-47`) yang mengurutkan ulang SELURUH daftar transaksi dan menyimpan ke Room.

---

## 2. Jawaban atas Pertanyaan Audit

| # | Pertanyaan | Temuan |
|---|-----------|--------|
| 5 | Serial atau concurrent? | **Concurrent terbatas — maksimal 8 request "in-flight"** (ukuran `interceptExecutor`). Request ke-9+ antre tanpa batas waktu. Bila intercept ON, secara efektif **serial-manual**: setiap request menunggu tap manusia. |
| 6 | Response besar dibuffer penuh? | **Ya.** Double aggregation 50 MB + dua kali salin `ByteArray` per response. Tidak ada streaming sama sekali. |
| 7 | Interception/logging menyebabkan latency? | **Ya, sangat besar.** (a) `interceptEnabled` default `true` → blockir thread per request sampai interaksi UI. (b) `notifyComplete` → sort ulang seluruh history + persist Room pada setiap response. |
| 8 | Penanganan CONNECT? | Berfungsi (MITM dengan leaf cert dinamis), tapi: (a) `ca.certFor(host)` — generate RSA-2048 keypair + signing — **dipanggil di dalam listener `writeAndFlush`, yaitu di I/O event loop**; (b) agregator dobel di pipeline tunnel; (c) WebSocket/wss tidak didukung. |

---
## 3. Daftar Bottleneck Teridentifikasi

Dinomori berdasarkan dampak terhadap gejala "lambat/hang".

### 🔴 B1 — Interception ON secara default memblokir thread worker (ROOT CAUSE #1)

- **File/Fungsi:** `proxy/InterceptStore.kt` → `interceptEnabled` (baris 14) & `submit()` (baris 47–54); `proxy/ProxyServer.kt` → `forwardIntercepted()` baris 163; `ui/ProxyViewModel.kt` baris 30 (`_interceptEnabled = MutableStateFlow(true)`).
- **Mekanisme:** `submit()` menjalankan `tx.resumeSignal.join()` — blockir tanpa timeout hingga UI memanggil `forward()/drop()`. Setiap request yang sedang menunggu **menyita satu dari hanya 8 thread** `DefaultEventExecutorGroup(8)` (`ProxyServer.kt:48`).
- **Kenapa ini membuat web "hang":** Satu halaman web modern membuka puluhan request paralel. Browser membuka ~6 koneksi; aplikasi lain ikut lewat proxy. Begitu 8 thread habis (entah karena menunggu tap user, atau menunggu antrean), **semua request berikutnya antre selamanya** → halaman tampak mati total.

### 🔴 B2 — Bug UI single-pending: transaksi yang menunggu bisa tak terlihat → deadlock permanen (AMPLIFIER dari B1)

- **File/Fungsi:** `ui/ProxyViewModel.kt` baris 39–48.
- **Mekanisme:** `_pending.value = tx` **menimpa** transaksi yang sedang menunggu sebelumnya. Saat user men-forward satu transaksi, `_pending.value = null` — padahal mungkin masih ada transaksi lain yang thread-nya masih `join()` di `InterceptStore`. Transaksi tersebut **tidak pernah ditampilkan lagi** → thread-nya blockir permanen sampai proses dibunuh. Ini menjelaskan kasus "kadang terasa seperti hang" bahkan setelah user sempat men-forward.

### 🟠 B3 — Tidak ada connection reuse / keep-alive ke origin; koneksi + TLS handshake baru per request (ROOT CAUSE #2 untuk "sangat lambat")

- **File/Fungsi:** `ProxyServer.relayToOrigin()` baris 187 (`Bootstrap()` baru per call), baris 195–197 (build `SslContext` baru per request), baris 221 & 226 (`originCtx.close()` selalu).
- **Mekanisme:** Setiap subresource (CSS/JS/img/API) = TCP connect baru + **TLS handshake penuh baru** (untuk HTTPS) + build objek `SslContext` baru. Untuk halaman dengan 40–80 subresource, ini menambah ratusan ms hingga detik per objek, dan CPU perangkat mobile kerja keras melakukan handshake RSA/ECDHE berulang.
- **Efektivitas keep-alive klien juga rusak** karena respons origin tidak pernah di-multiplex ke koneksi origin yang hidup.

### 🟠 B4 — Full buffering response (double aggregator 50 MB + salinan ganda)

- **File/Fungsi:** `ProxyServer.kt` baris 63 & 115 (`HttpObjectAggregator(50*1024*1024)` di sisi klien), baris 204 (di sisi origin), baris 209–216 (salin `ByteArray` → `wrappedBuffer`).
- **Mekanisme:** Response tidak mulai dikirim ke klien sebelum **seluruh** body selesai diterima & didekompresi dari origin. Untuk response besar (gambar, video, bundel JS), TTFB yang dilihat browser = waktu transfer total. Di perangkat mobile, buffer heap besar → GC pressure → jank global. Risiko OOM nyata pada multi-request paralel berukuran besar (8 × hingga 50 MB).

### 🟠 B5 — Blocking work di I/O event loop: pembuatan leaf cert & DNS resolve

- **File/Fungsi:** `ProxyServer.handleConnect()` baris 107–116 — `ca.certFor(host)` dipanggil dalam **listener `ChannelFuture` yang dieksekusi di event loop I/O klien**; `CertificateAuthority.generateLeafCert()` (`CertificateAuthority.kt:92-116`) melakukan `KeyPairGenerator RSA 2048` + tanda tangan SHA256withRSA (puluhan–ratusan ms di perangkat mobile). Juga `bootstrap.connect(targetHost, targetPort)` (`ProxyServer.kt:244`) — resolusi DNS default Netty bersifat **blocking di event loop**.
- **Mekanisme:** Satu `NioEventLoop` melayani banyak channel. Blocking di situ menunda **semua koneksi lain** yang kebetulan satu loop — latency sporadis yang sulit direproduksi. Leaf cert hanya mahal pada kunjungan pertama per host (ada cache), tapi DNS blocking terjadi di **setiap** request (karena koneksi origin selalu baru, lihat B3).

### 🟡 B6 — Tidak ada timeout di mana pun

- **File/Fungsi:** `ProxyServer.kt` — tidak ada `CONNECT_TIMEOUT_MILLIS`, `ReadTimeoutHandler`, `WriteTimeoutHandler`, atau `IdleStateHandler` pada salah satu pipeline; `InterceptStore.submit()` `join()` tanpa batas waktu.
- **Mekanisme:** Koneksi origin yang menggantung (server lambat, jaringan drop) menahan koneksi klien & resource selamanya; akumulasi koneksi zombie memperparah kehabisan thread/fd. Timeout intercept tak terbatas = deadlock B2 bersifat permanen.

### 🟡 B7 — Logging/persistence & state UI dijalankan di jalur panas

- **File/Fungsi:** `ProxyViewModel.kt` baris 43–47 (`onTransactionComplete`: `_transactions.value = InterceptStore.all()` — sort O(n log n) seluruh history **pada setiap response**, dieksekusi di event loop outbound channel karena `notifyComplete` dipanggil dari `channelRead0` handler origin di `ProxyServer.kt:213`), lalu `persist()` menulis Room per transaksi.
- **Mekanisme:** Makin lama sesi, makin lambat setiap response (sort membesar); `InterceptStore.transactions` **tidak pernah di-evict** → semua body request/response menumpuk di RAM (kebocoran memori perlahan, GC makin sering).

### 🟢 B8 — Lain-lain (dampak kecil)

- **Dekompresi wajib** (`HttpContentDecompressor`, `ProxyServer.kt:203`): CPU ekstra di perangkat untuk setiap response ter-kompres; dapat diterima untuk alat inspeksi, tapi menambah latency pada body besar.
- **Agregator dobel pada tunnel HTTPS** (`handleConnect` baris 115 menambah `HttpObjectAggregator` padahal aggregator awal dari `start()` tidak dilepas): boros buffer, potensi kebingungan pipeline.
- **Header hop-by-hop / `Transfer-Encoding` / `Connection`** diteruskan apa adanya (`outbound.headers().add(resp.headers())`, baris 218) — pelanggaran protokol ringan yang bisa memicu perilaku aneh di beberapa klien.
- **Race keep-alive sisi klien (plain HTTP):** `ProxyFrontHandler` adalah handler per-channel yang bisa menerima request berikutnya di koneksi yang sama saat respons sebelumnya belum selesai; dua relay konkuren pada satu channel dapat menulis respons **tidak sesuai urutan request**.
- **WebSocket/HTTP2 tidak didukung** — situs yang bergantung padanya akan gagal/lambat (dokumentasi kode sudah mengakuinya, `ProxyServer.kt:37-39`).

---

## 4. Root Cause Paling Mungkin (peringkat)

Untuk gejala "website sangat lambat atau hang":

| Rank | Root cause | Dampak | Keyakinan |
|------|-----------|--------|-----------|
| **1** | **B1 + B2** — intercept ON default memblokir 8 thread `interceptExecutor`; UI hanya menampilkan 1 pending → sisanya dead-lock tanpa timeout | Halaman benar-benar berhenti total (*hang*) | Sangat tinggi — konsisten dengan "kadang hang" |
| **2** | **B3** — koneksi origin + TLS handshake baru untuk setiap subresource | Latensi akumulatif besar, situs "terasa berat" tapi tetap jalan | Tinggi — konsisten dengan "sangat lambat" |
| **3** | **B4** — full-buffering response 50 MB | Situs dengan asset besar lambat menampilkan konten pertama | Sedang–tinggi |
| **4** | **B5** — cert-gen & DNS blocking di event loop | Lonjakan latensi sporadis, terutama host baru | Sedang |
| **5** | **B6/B7/B8** | Memperparah dan membuat degradasi progresif | Pelengkap |

**Skenario reproduksi yang diharapkan:** dengan `interceptEnabled = true`, buka sembarang situs HTTPS → beberapa request pertama muncul di layar intercept, sisanya diam; jika user men-forward cepat, situs tetap lambat karena tiap objek menunggu giliran manusia + handshake TLS baru (B1+B3).

---

## 5. Rekomendasi Perbaikan, Risiko, dan Urutan Implementasi Aman

Urutan diatur dari **paling aman & paling berdampak** ke yang paling invasif. Lakukan bertahap, uji tiap tahap.

### Tahap 1 — Non-intercept by default + timeout intercept (aman, dampak terbesar)

| Perubahan | File | Risiko | Mitigasi risiko |
|---|---|---|---|
| Default `interceptEnabled = false` (dan persist pilihan user) | `InterceptStore.kt:14`, `ProxyViewModel.kt:30` | Rendah — mengubah perilaku UX (user harus mengaktifkan intercept manual) | Tampilkan toggle yang jelas di UI; dokumentasi |
| Ganti `join()` tanpa batas → `get(timeout, unit)` (mis. 30–60 dtk) dengan fallback auto-forward | `InterceptStore.submit()` | Rendah–sedang — auto-forward bisa mengirim request yang belum direview user | Buat timeout configurable; log kejadian auto-forward |
| Perbaiki antrian pending UI: pakai list/queue `pending`, bukan satu `_pending` yang ditimpa | `ProxyViewModel.kt:27,41` | Rendah — murni UI state | Tambah unit test sederhana pada ViewModel |

**Ekspektasi:** menghilangkan mode *hang* hampir sepenuhnya.

### Tahap 2 — Pindahkan pekerjaan blocking keluar I/O event loop (aman)

| Perubahan | File | Risiko | Mitigasi |
|---|---|---|---|
| Panggil `ca.certFor(host)` di `interceptExecutor` (atau executor khusus) sebelum `addFirst("ssl", ...)`; pre-warm & cache `SslContext` per host | `ProxyServer.handleConnect()`, `CertificateAuthority` | Rendah | Cache `SslContext` per host bersama cache leaf cert |
| Konfigurasikan resolver asinkron Netty (`DnsNameResolverBuilder`) atau resolve di executor terpisah | `relayToOrigin()` | Sedang — perilaku DNS berubah; perlu fallback | Mulai dengan `ChannelOption.CONNECT_TIMEOUT_MILLIS` (5 dtk) saja sebagai langkah minimal pertama |

### Tahap 3 — Tambahkan timeout & housekeeping (aman)

| Perubahan | File | Risiko | Mitigasi |
|---|---|---|---|
| `ReadTimeoutHandler`/`IdleStateHandler` (mis. 30 dtk) di pipeline origin & klien; tutup + kirim 504 | kedua pipeline `ProxyServer` | Rendah — koneksi lambat yang sah (streaming panjang) bisa terputus; buat nilai configurable | Nilai besar default; hanya aktif saat idle |
| Eviction/transaksi maksimum di `InterceptStore` (LRU, mis. 200 entri) + jangan sort ulang seluruh list di listener panas | `InterceptStore.all()`, `ProxyViewModel` | Sedang — history in-memory bisa lebih cepat hilang; UI perlu adaptasi | Room tetap menyimpan semua; eviction hanya untuk memori |
| Hapus aggregator duplikat di `handleConnect` | `ProxyServer.kt:115` | Sangat rendah | Verifikasi tunnel HTTPS masih jalan |

### Tahap 4 — Connection pooling / keep-alive ke origin (dampak besar, lebih invasif)

| Perubahan | File | Risiko | Mitigasi |
|---|---|---|---|
| Pool koneksi origin per `(host, port)` dengan `HttpClientCodec` + `SslContext` yang di-cache; jangan `originCtx.close()` otomatis | `relayToOrigin()` | **Sedang–tinggi** — kebenaran pooling HTTP/1.1 rumit: pencocokan respons-request, server bisa menutup (`Connection: close`), race saat koneksi mati | Pool per-channel dengan antrian request per koneksi; mulai pool kecil (mis. 4/host) + health-check sebelum reuse |
| Strip header hop-by-hop (`Connection`, `Keep-Alive`, `Proxy-*`, `TE`, `Upgrade`) pada request & response yang diteruskan | `relayToOrigin()` | Rendah | Daftar header standar RFC 7230 §6.1 |

**Ekspektasi:** menghilangkan mayoritas biaya TCP+TLS handshake → perbaikan latensi terbesar untuk browsing normal.

### Tahap 5 — Streaming response (invazif, lakukan terakhir)

| Perubahan | File | Risiko | Mitigasi |
|---|---|---|---|
| Ganti `HttpObjectAggregator` sisi origin dengan relay `HttpContent` chunk-per-chunk ke klien, sambil merekam body (cap mis. 1 MB) untuk tampilan UI | `start()`, `handleConnect()`, handler origin | **Tinggi** — interception edit-response tak mungkin tanpa buffer; Content-Length/chunked dikelola manual; kompatibilitas `HttpContentDecompressor` perlu diuji | Pertahankan mode "buffered" sebagai fallback/konfigurasi; streaming hanya ketika intercept OFF |
| Turunkan batas aggregator (mis. 2 MB) bila streaming tidak dilakukan | kedua aggregator | Rendah — response >batas gagal (413/502) | Dokumentasikan; buat configurable |

---

## 6. Kesimpulan

Gejala **"hang"** hampir pasti disebabkan kombinasi **B1+B2**: intercept default-ON memblokir 8 thread executor tanpa timeout, dan bug UI satu-pending membuat sebagian transaksi tak pernah bisa di-forward. Gejala **"sangat lambat"** didorong utamanya oleh **B3** (tanpa reuse koneksi/TLS) dan **B4** (full buffering). Perbaikan Tahap 1 saja (non-default intercept + timeout + fix antrian pending) diperkirakan menyembuhkan kasus hang dengan risiko paling kecil; Tahap 2–4 memberi perbaikan latensi besar secara bertahap.

**Audit selesai — tidak ada kode yang diubah. Menunggu instruksi Anda.**

---

## 7. Addendum — Status Implementasi (2026-08-22)

Perbaikan Tahap 1–5 telah diimplementasikan (kecuali diubah seperlunya):

| Item audit | Status | Implementasi |
|---|---|---|
| B1 Intercept default ON | ✅ | `interceptEnabled = false` |
| B2 Single-pending deadlock | ✅ | Queue `pending: StateFlow<List>` + Forward all/Drop all |
| B2/B6 join() tanpa timeout | ✅ | Auto-forward setelah `interceptTimeoutMs` (60 dtk) |
| B3 Tanpa reuse koneksi origin | ✅ | `OriginConnectionPool` keep-alive per host |
| B4 Full buffering 50 MB | ✅ | Streaming relay + rekaman capped 10 MB untuk UI |
| B5 Cert-gen & DNS blocking event loop | ✅ | Cert/SslContext di worker executor + cache per host |
| B6 Tanpa timeout | ✅ | Connect 5 dtk, idle 30/75 dtk |
| B7 Sort/persist jalur panas & memory growth | ⚠️ Sebagian | Eviction 500 entri; sort list masih ada (cukup untuk skala ini) |
| B8 Aggregator dobel CONNECT | ✅ | Pipeline tunnel disusun ulang benar |
| Bug baru ditemukan: urutan handler CONNECT salah | ✅ | Front handler dilepas; tunnel pipeline eksplisit |

Detail teknis: lihat `docs/CHANGELOG-performance-fixes.md`. UI juga di-upgrade ke glassmorphism dengan tab Traffic/Intercept/Repeater.







