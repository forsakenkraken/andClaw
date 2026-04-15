Third-Party Licenses

This app includes third-party open-source components.
When distributing APK/AAB artifacts, you must comply with each component's license.

Included Components (Key Runtime/Binary)

1) proroot (Linux runtime engine)
- Upstream: https://github.com/coderredlab/proroot
- License: Proprietary (free to use, attribution required)
- Includes libldlinux.so derived from GNU C Library (glibc)
  - License: LGPL-2.1 (https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
  - Source: https://sourceware.org/glibc/

2) proot / libtalloc (legacy compatibility runtime)
- Upstream:
  - https://github.com/termux/proot
  - https://github.com/samba-team/samba/tree/master/lib/talloc
- License:
  - proot: GPL-2.0
  - libtalloc: LGPL-3.0-or-later
- Distribution note:
  - This app may redistribute the legacy Termux proot compatibility runtime as libproot.so, libproot-loader.so, libproot-loader32.so, and libtalloc.so.
  - If these binaries are included in APK/AAB artifacts, keep corresponding source access and license notices available.

3) openclaw (bundled CLI assets)
- Upstream:
  - https://www.npmjs.com/package/openclaw
- License: MIT
- Distribution note:
  - This app redistributes OpenClaw files under install_time_assets/src/main/assets/openclaw.
  - Keep MIT notice and provide source access link for distributed app sources.
  - Public source repository for this app: https://github.com/coderredlab/andClaw

4) Node.js (bundled runtime)
- Upstream: https://nodejs.org/
- License: MIT
- Distribution note:
  - Bundled as node-arm64.tar.gz.bin in install_time_assets.

5) Playwright Chromium headless_shell (bundled browser)
- Upstream: https://playwright.dev/
- License: Apache-2.0 (Playwright), Chromium (BSD-style)
- Distribution note:
  - Bundled as playwright-chromium-arm64.tar.gz.bin in install_time_assets.

6) OpenClaw bundled dependencies (selected copyleft-sensitive)
- The bundled OpenClaw asset tree includes many transitive packages.
- Current bundled tree includes packages with additional obligations:
  - @whiskeysockets/libsignal-node
    - Upstream: https://github.com/WhiskeySockets/libsignal-node
    - License: GPL-3.0
    - Note: bundled through the WhatsApp runtime path.
  - @img/sharp-libvips-linux-arm64
    - Upstream: https://github.com/lovell/sharp-libvips
    - License: LGPL-3.0-or-later
    - Note: bundles libvips runtime used by sharp on linux-arm64.
  - jszip
    - Upstream: https://github.com/Stuk/jszip
    - License: MIT OR GPL-3.0-or-later
    - Note: OpenClaw uses JSZip for zip/archive handling paths.
- Distribution note:
  - If these packages are redistributed in release assets, keep their license notices and provide source access/offer details as required by each license.
  - If related features are not used, consider excluding those dependency paths from bundled assets to reduce compliance scope.

Other OSS Dependencies

The app also uses dependencies declared in Gradle files (for example AndroidX, Kotlin, OkHttp, Commons Compress, ZXing, etc.).
Their licenses remain applicable under their own terms.
