# iKeys — Native iOS-Style Android IME

**iKeys** adalah proyek aplikasi **Android Native Input Method Editor (IME)** yang dirancang untuk menghadirkan tampilan visual, animasi, akustik klik, getaran haptik, dan rasa pengetikan semirip mungkin dengan keyboard **iOS terbaru** — dibangun dari nol dengan arsitektur **pure Canvas rendering** tanpa beban Jetpack Compose/Material agar **super ringan, rendah memori, dan berlatensi sangat rendah**.

---

## Fitur Utama

### 1. Desain Visual & Tipografi Autentik iOS (Light & Dark)
- **Proporsi Grid Presisi iOS**: Baris 1 (10 tombol penuh), Baris 2 (9 tombol simetris dengan inset 0,5 tombol di kiri & kanan), Baris 3 (Shift + 7 huruf + Backspace), Baris 4 (`123`, Emoji, `space`, tombol Aksi dinamis).
- **Palet Warna iOS Light & Dark + Auto System Theme**: Shadow tipis 1dp khas keycap iOS, tombol aksi biru iOS (`#007AFF` / `#0A84FF`) yang berubah otomatis sesuai konteks input (`search`, `send`, `go`, `done`, `next`, `return`).
- **Ikon Vektor Canvas Tajam**: Ikon Shift (normal, aktif, Caps Lock bergaris bawah), Backspace pentagon, Emoji, Globe, dan Keyboard Dismiss digambar langsung sebagai vektor pada Canvas tanpa aset PNG yang pecah di layar HiDPI.
- **Integrasi Edge-to-Edge Navigation Bar**: Warna navigation bar Android otomatis menyatu dengan latar keyboard.

### 2. Animasi & Interaksi Khas iOS
- **Teardrop Key Preview Popup**: Balon pembesar huruf terhubung dengan kurva Bézier halus tepat di atas jari, sadar tepi layar (`Q`/`P` tidak terpotong), dan memiliki animasi *fade-out* halus saat jari dilepas — dirender langsung di Canvas tanpa latensi `PopupWindow`.
- **Long-Press Diakritik & Karakter Alternatif**: Tahan tombol vokal (`a`, `e`, `i`, `o`, `u`, dll.) atau simbol (`$`, `.`, `-`, `"`) untuk memunculkan strip karakter alternatif; geser jari untuk memilih dengan highlight pil biru iOS.
- **Spacebar Trackpad (Kontrol Kursor)**: Tahan tombol spasi (~310ms) → semua label tombol memudar seperti iOS → geser jari ke kiri/kanan untuk menggerakkan kursor teks dengan mulus disertai *micro-tick* haptik.
- **Multi-Touch Rollover Typing**: Pengetikan dua jempol super cepat tidak menjatuhkan huruf saat tombol berikutnya ditekan sebelum jari pertama terangkat.
- **Smart Backspace Acceleration & Swipe-to-Delete**: Ketuk hapus 1 karakter/emoji (aman untuk *surrogate pair* & ZWJ), tahan untuk akselerasi hapus per kata, atau geser ke kiri dari Backspace untuk langsung menghapus 1 kata.
- **Smart Typing**: Auto-Shift awal kalimat, double-tap Shift untuk Caps Lock, double-space menjadi titik (`. `), auto-return dari `123` ke `ABC` setelah spasi, serta layout khusus Email (`@`, `.`) dan URL (`.`, `/`).

### 3. Performa Super Ringan & Latensi Rendah
- **Zero-Allocation `onDraw()`**: Semua `Paint`, `Path`, dan `RectF` dialokasikan sekali saja.
- **Zero-Inflation Emoji Picker**: Panel emoji kategori lengkap dirender langsung di satu Canvas ringan.
- **Procedural iOS "Tock" Sound Engine**: 3 profil suara klik khas iOS (*letter*, *delete*, *modifier*) disintesis secara prosedural ke PCM WAV mikro di *background thread* dan diputar lewat `SoundPool` berlatensi rendah — tanpa file MP3 eksternal.
- **Taptic-Style Haptic Engine**: Efek getaran tajam menggunakan `VibrationEffect.EFFECT_TICK` / pulsa singkat presisi.
- **QuickType Bilingual (Indonesia + Inggris)**: Mesin Trie & koreksi typo proksimitas QWERTY sub-milidetik yang bekerja 100% lokal & belajar kata baru otomatis.
- **100% Privat**: Nol izin internet (`INTERNET` tidak diminta di `AndroidManifest.xml`).

---

## Struktur Proyek

```text
ikeys/
├── .github/workflows/build-apk.yml        # GitHub Actions CI: Unit Tests + Debug APK
├── app/
│   ├── build.gradle.kts                   # Minimal dependencies (hanya androidx.core:core-ktx)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # Deklarasi IME Service + MainActivity
│       │   ├── res/xml/method.xml         # Metadata Input Method (EN & ID subtypes)
│       │   └── java/com/ikeys/keyboard/
│       │       ├── audio/IOSSoundEngine.kt
│       │       ├── haptic/IOSHapticEngine.kt
│       │       ├── ime/
│       │       │   ├── IKeysInputMethodService.kt
│       │       │   └── TextInputController.kt
│       │       ├── layout/
│       │       │   ├── KeyModel.kt
│       │       │   └── IOSKeyboardLayout.kt
│       │       ├── prediction/SuggestionEngine.kt
│       │       ├── prefs/IKeysPreferences.kt
│       │       ├── settings/
│       │       │   ├── IOSSwitchView.kt
│       │       │   └── MainActivity.kt
│       │       └── ui/
│       │           ├── IOSEmojiPanelView.kt
│       │           ├── IOSKeyboardTheme.kt
│       │           ├── IOSKeyboardView.kt
│       │           └── IOSVectorIcons.kt
│       └── test/java/com/ikeys/keyboard/
│           ├── KeyboardLayoutEngineTest.kt
│           ├── SuggestionEngineTest.kt
│           └── TextInputControllerTest.kt
```

---

## Cara Build & Instalasi

### Build Lokal (Android Studio / CLI dengan JDK 17 + Android SDK API 34)
```bash
cd ikeys
./gradlew testDebugUnitTest assembleDebug
```
APK hasil build akan tersedia di:
`app/build/outputs/apk/debug/app-debug.apk`

### Build Otomatis via GitHub Actions
Push repositori ini ke GitHub — workflow [build-apk.yml](.github/workflows/build-apk.yml) akan otomatis menjalankan seluruh unit test dan mengunggah artefak **`ikeys-debug-apk`** yang siap diunduh dan diinstal ke perangkat Android.

### Aktivasi di Perangkat Android
1. Buka aplikasi **iKeys**.
2. Ketuk **1. Enable iKeys Keyboard** dan aktifkan *iKeys* di Pengaturan Sistem.
3. Ketuk **2. Select iKeys as Active Keyboard** dan pilih *iKeys*.
4. Langsung uji di kolom **Live Test Sandbox** pada layar utama aplikasi!
