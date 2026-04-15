# Third-Party Licenses

This project includes third-party components.
When distributing APK/AAB artifacts, you must comply with each component's license or usage terms.

## Included Components (Key Runtime/Binary)

### 1) proroot
- Upstream: https://github.com/coderredlab/proroot
- License: Proprietary (free to use; redistribution of modified binaries is not permitted)
- Distribution note:
  - `libproroot.so` is documented upstream as proprietary/free-to-use.
  - `libldlinux.so` is derived from GNU C Library and remains subject to LGPL-2.1 obligations.

### 2) proot / libtalloc (legacy compatibility runtime)
- Upstream:
  - https://github.com/termux/proot
  - https://github.com/samba-team/samba/tree/master/lib/talloc
- License:
  - `proot`: GPL-2.0
  - `libtalloc`: LGPL-3.0-or-later
- Distribution note:
  - This app may redistribute the legacy Termux `proot` compatibility runtime as `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`, and `libtalloc.so`.
  - If these binaries are included in APK/AAB artifacts, keep corresponding source access and license notices available.

### 3) openclaw (bundled CLI assets)
- Upstream:
  - https://www.npmjs.com/package/openclaw
- License: MIT
- Distribution note:
  - This app redistributes OpenClaw files under `install_time_assets/src/main/assets/openclaw`.
  - Keep MIT notice and provide source access link for distributed app sources:
    - https://github.com/coderredlab/andClaw
  - Public source repository for this app:
    - https://github.com/coderredlab/andClaw

### 4) OpenClaw bundled dependencies (selected copyleft-sensitive)
- The bundled OpenClaw asset tree includes many transitive packages.
- Current bundled tree includes packages with additional obligations:
  - `@whiskeysockets/libsignal-node`
    - Upstream: https://github.com/WhiskeySockets/libsignal-node
    - License: GPL-3.0
    - Note: bundled through the WhatsApp runtime path.
  - `@img/sharp-libvips-linux-arm64`
    - Upstream: https://github.com/lovell/sharp-libvips
    - License: LGPL-3.0-or-later
    - Note: bundles libvips runtime used by sharp on linux-arm64.
  - `jszip`
    - Upstream: https://github.com/Stuk/jszip
    - License: MIT OR GPL-3.0-or-later
    - Note: OpenClaw uses JSZip for zip/archive handling paths.
- Distribution note:
  - If these packages are redistributed in release assets, keep their license notices and provide source access/offer details as required by each license.
  - If related features are not used, consider excluding those dependency paths from bundled assets to reduce compliance scope.

## Other OSS Dependencies

The app also uses dependencies declared in Gradle files (for example AndroidX, Kotlin, OkHttp, Commons Compress, ZXing, etc.).
Their licenses remain applicable under their own terms.

## Recommended Compliance Checklist

1. Include an OSS notices screen or bundled notice file in app/release docs.
2. Keep links to upstream source repositories and license texts.
3. For GPL/LGPL components included as binaries, provide corresponding source access details in release notes or repository documentation.
4. For bundled npm trees (OpenClaw), review transitive dependency licenses per release and update notices accordingly.

## Disclaimer

This file is a practical compliance note, not legal advice.
For commercial/public distribution, run a formal legal/license review before release.
