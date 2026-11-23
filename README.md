# Text Reader

A private, offline text-to-speech reader for Android (built for a Samsung Galaxy S25+).

- **Reads any screen:** web pages, email and other apps.
- **Floating bubble:** a round button at the screen edge with a muted-green ring while speaking.
  Tap it for the panel with big **Read**, **Play/Pause** and **Stop** buttons and a seek bar.
  The panel turns back into the bubble 5 seconds after your last touch while reading.
  Drag the bubble onto the **X** at the bottom (or tap X on the panel) to close. Closing stops reading.
  Press **Side key + Volume up** to bring the panel back.
- **Reads whole pages:** it starts speaking right away and scrolls in the background, up to 10 screens.
  Tap Read again to continue from what's on screen (screen 11).
- **Stops at Like / Share / Copy buttons:** ends reading at the button row under a post or an AI answer
  (Claude, ChatGPT, Google AI Mode, Facebook, X, Reddit), so the extras after it aren't read. Can be turned off.
- **Copy → Paste → Play:** after you copy text, the bubble pops up for 5 seconds. Tap it, tap **Paste**,
  then **Play**. Can be turned off.
- **Reads files:** PDF (including scanned pages), TXT, MD, DOCX and EPUB.
- **Natural offline voices:** Kokoro neural voices (28 English voices) via sherpa-onnx.
- **Skips clutter:** images are skipped silently. Code blocks are read as "Code here", and web addresses as "link".
- **Private:** the app has **no internet permission**. Nothing you read ever leaves the phone.

## Building (GitHub Actions)

1. Set up signing once — see **[SIGNING.md](SIGNING.md)**. The build refuses to run without the key.
2. Change `version.txt` for a new version (`major.minor.patch`, e.g. `1.1.1`).
3. Go to **Actions → Build Text Reader → Run workflow**.
4. The APK appears under **Releases** as `TextReader-<version>.apk`.
   Building the same version again replaces that release.

The first build downloads the voice model (~350 MB) and caches it, so later builds are faster.

## Setting up on the phone

1. Install the APK (allow "Install unknown apps" for your browser or Files app when asked).
2. Open Text Reader → **Open accessibility settings** → Installed apps → Text Reader → turn it on.
   If Android says it's a *restricted setting*: Settings → Apps → Text Reader → ⋮ → **Allow restricted settings**, then try again.
3. Shortcut: Settings → Accessibility → Advanced settings → **Side and Volume up buttons** → choose Text Reader.

## License

Text Reader is free software under the **GNU General Public License v3.0** (see `LICENSE`).

### Third-party components

| Component | License |
|---|---|
| [eSpeak NG](https://github.com/espeak-ng/espeak-ng) (pronunciation, inside the voice engine) | GPL-3.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (voice engine) | Apache 2.0 |
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (voice model) | Apache 2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (inside sherpa-onnx) | MIT |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) via [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Apache 2.0 |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache 2.0 |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | Apache 2.0 |
