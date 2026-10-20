# DocScan

A simple, fully offline document scanner for Android, in the spirit of Adobe Scan.

**Scan → auto-detect page → fix skew → clean, high-contrast result → PDF → share anywhere.**

## Build the APK

Easiest, one command (macOS/Linux, needs Java 17–22 and internet on the first run):

```bash
cd document_scanner_app/DocScan
chmod +x build_apk.sh
./build_apk.sh
```

It installs the Android SDK command-line tools, the SDK platform and Gradle if they are missing, builds the app,
and writes **`DocScan.apk`** next to the script.

Or with Android Studio: open this folder, let it sync, then *Build > Build Bundle(s) / APK(s) > Build APK(s)*.

Install: copy `DocScan.apk` to the phone and open it (allow "Install unknown apps"), or `adb install -r DocScan.apk`.
Requires Android 10 (API 29) or newer.

## How it works

| Step | What happens |
| --- | --- |
| Live camera | CameraX preview with a live outline of the detected page. Hold steady and it captures automatically (or tap the shutter). |
| Page detection | DocCornerNet, a 1 MB on-device model (`assets/doccorner256.tflite`, MobileNetV2, MIT, [mapo80/DocCornerNet-CoordClass](https://github.com/mapo80/DocCornerNet-CoordClass)) that outputs the four corners plus a has-document score. |
| Skew fix | Perspective warp of the four corners to a flat rectangle. |
| Cleanup | The lighting is estimated (ink erased, heavily blurred) and divided out, so shadows vanish and paper becomes white. Then a contrast curve, mild saturation boost and sharpening. |
| Filters | **Auto** (colour), **B&W** (Otsu-based high contrast), **Grayscale**, **Original**. Per page. |
| Edit | Drag the four corners, rotate, delete, add more pages, import from gallery. |
| PDF | Pages are stored as JPEG inside a PDF (small files, about 200 dpi). Saved to the app library and to `Downloads/DocScan`. |
| Share | Standard Android share sheet: WhatsApp, Gmail, Drive, anything that takes a PDF. |

| Cloud backup | Optional. Sign in with Microsoft or Google (on first start, or later from the cloud icon in the top bar), or tap *Skip*. Sign-in is kept until it expires or you sign out. You can be signed in to both; scans then go to one of them (the account you signed in to first, changeable in the cloud dialog), or to both once *Upload to both* is switched on. Each account has its own folder. With Microsoft (work/school account from any organisation), every saved PDF uploads to the folder you pick with *Change folder*; until you pick one it goes to *My OneDrive › DocScan*. With Google, it uploads to *My Drive › DocScan* or a subfolder of it. *Change folder* in the cloud dialog opens a browser: OneDrive, followed SharePoint sites (or search), their libraries and folders; with Google, the folders inside *DocScan* (the app may only see folders it created). *New folder* works in both. Offline scans wait and upload once the phone is online. The small cloud next to each scan shows ✓ backed up, ↑ waiting, or a red crossed cloud = failed (tap to see why and retry). |

Scans only leave the phone through the cloud backup or when you share them.
Renaming or deleting a scan in the app does not change the cloud copy.

Sign-in uses an Entra ID app registration (audience `AzureADMultipleOrgs`; the registration must be set to *Accounts in any organizational directory*).
Its values come from `.env` (copy `.env.example`; real environment variables override it), and the build fails if they are missing:
`APPLICATION_CLIENT_ID` is the registration's client ID, and `SIGNATURE_HASH` is the signing key's hash that its Android redirect URI is tied to.
The build generates `msal_config.json` from them. An APK signed with a different key cannot sign in until that hash is also added in Entra ID.

Google sign-in needs a Google Cloud project with the **Google Drive API** enabled, an OAuth consent screen
(scopes `drive.file` and `email`), and an **Android OAuth client** for package `ai.bonsen.docscan` with the
signing key's SHA-1 (`82:66:E1:EC:9C:8C:C7:2E:D6:53:D5:8F:16:41:51:33:F8:8B:F6:15`, the same debug keystore).
No client ID goes in the app; Google matches the package and SHA-1.

## Project layout

```
app/src/main/java/ai/bonsen/docscan/
  MainActivity.kt          navigation
  ScanViewModel.kt         pages, previews, saving
  scan/DocDetector.kt      find the page outline (TFLite corner model)
  scan/Enhancer.kt         warp + lighting flatten + filters
  scan/PdfWriter.kt        minimal JPEG-based PDF writer
  scan/Storage.kt          library, Downloads copy, share/open
  scan/LiveDetector.kt     per-frame detection + auto-capture timing
  scan/Cloud.kt            Microsoft/Google sign-in, SharePoint/Drive upload queue (WorkManager)
  ui/                      Compose screens (Home, Camera, Review, Crop, FolderPicker)
```

## Tuning

The look of the scan is controlled in `scan/Enhancer.kt`: `lut(35, 225, 1.25)` sets the black and white points and midtone
darkening; `1.35` is the colour boost; the B&W curve is derived from the Otsu threshold.

Page detection: `tools/eval_detector.py labels.json` scores the model on your own labelled photos.
`tools/export_detector.py` re-exports it from a DocCornerNet checkpoint (`python tools/export_detector.py <repo> <repo>/checkpoints/mobilenetv2_256_best out.tflite`).
