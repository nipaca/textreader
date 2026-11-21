# Text Reader

A private, offline text-to-speech reader for Android (built for a Samsung Galaxy S25+).

- **Reads any screen:** web pages, email and other apps. A floating panel has Read, Play/Pause, a seek bar and Hide.
  Press **Side key + Volume up** to bring the panel back.
- **Reads whole pages:** it starts speaking right away and scrolls in the background, up to 10 screens.
  Tap Read again to continue from what's on screen (screen 11).
- **Reads files:** PDF (including scanned pages), TXT, MD, DOCX and EPUB.
- **Natural offline voices:** Kokoro neural voices (28 English voices) via sherpa-onnx.
- **Skips clutter:** images are skipped silently. Code blocks are read as "Code here", and web addresses as "link".
- **Private:** the app has **no internet permission**. Nothing you read ever leaves the phone.

## Building (GitHub Actions)

1. Change `version.txt` if you want a new version number (`major.minor.patch`, e.g. `1.0.1`).
2. Go to **Actions → Build Text Reader → Run workflow**.
3. When it finishes, the APK is under **Releases** as `TextReader-<version>.apk`.
   Building the same version again replaces that release.

The first build downloads the voice model (~350 MB) and caches it, so later builds are faster.

## Setting up on the phone

1. Install the APK (allow "Install unknown apps" for your browser or Files app when asked).
2. Open Text Reader → **Open accessibility settings** → Installed apps → Text Reader → turn it on.
   If Android says it's a *restricted setting*: Settings → Apps → Text Reader → ⋮ → **Allow restricted settings**, then try again.
3. Shortcut: Settings → Accessibility → Advanced settings → **Side and Volume up buttons** → choose Text Reader.

## Third-party components

- sherpa-onnx (Apache 2.0) and the Kokoro-82M voice model (Apache 2.0)
- Tesseract OCR via Tesseract4Android (Apache 2.0)
- PdfBox-Android (Apache 2.0)
