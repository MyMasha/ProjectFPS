# Third-Party Licenses & Notices

**PureFPS / MashClient** is licensed under the **GNU General Public License v3.0 (GPL-3.0)** (see [`LICENSE`](LICENSE)). This file lists third-party open-source software, binaries, and shader snippets bundled with or linked into PureFPS / MashClient, which retain their respective licenses below.

> **Minecraft Legal Notice:** NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Not affiliated with Hypixel Inc.

---

## 1. Bundled Vulkan Beta Translation Layer (`dist/mashclient-vulkan-zink-win64.zip`)

- **Archive:** `dist/mashclient-vulkan-zink-win64.zip`
- **SHA-256:** `d3f71e2ef11c032a6d7b507ee2f6bab6ca3fb20543944af70982fc24b92604cb`
- **Contents:** `opengl32.dll` (17,277,440 bytes, PE32+ x86-64)
- **Provenance:** Mesa3D **26.3.0** Gallium Zink OpenGL-over-Vulkan driver built for Windows x64 with Microsoft Visual C++ (`MSVC 14.51`, `/MT` static CRT; imports only native Windows system DLLs — no MinGW/GPL runtime DLLs). Upstream Mesa3D source: <https://gitlab.freedesktop.org/mesa/mesa>.

This binary redistribution incorporates **Mesa3D** (MIT License) and **Berkeley SoftFloat Release 3e** (BSD 3-Clause License), whose required notices are reproduced in full below.

### 1.1. Mesa3D — MIT License

```text
Copyright (C) 1999-2007  Brian Paul   All Rights Reserved.
Copyright (C) 2008-2026  Mesa3D Contributors

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

### 1.2. Berkeley SoftFloat Release 3e — BSD 3-Clause License

```text
Copyright 2011, 2012, 2013, 2014, 2015, 2016, 2017, 2018 The Regents of
the University of California.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice,
    this list of conditions, and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions, and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 3. Neither the name of the University nor the names of its contributors may
    be used to endorse or promote products derived from this software without
    specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS", AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, ARE
DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## 2. GLSL Shader Snippets

Used in `src/main/java/dev/purefps/core/BlackHoleEmergenceRenderer.java` and `launcher/src/ui.html`:

### 2.1. "Hash Without Sine" (`hash33`) — David Hoskins (MIT License)

```text
Copyright (c) 2014 David Hoskins

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 2.2. ACES Filmic Tone Mapping Curve Fit — Krzysztof Narkowicz

Rational polynomial approximation of the ACES filmic tone-mapping curve published by Krzysztof Narkowicz (2016, public domain / educational reference).

---

## 3. Java Mod Dependencies (`build.gradle.kts`)

- **SpongePowered Mixin** (`org.spongepowered:mixin:0.7.11-SNAPSHOT`) — **MIT License** (shaded into `purefps-*.jar` to enable runtime bytecode transformations on Forge 1.8.9).
- **Minecraft Forge 1.8.9** (`11.15.1.2318`) — **LGPL-2.1** (compile-time API & user runtime; not redistributed in this repository).
- **LWJGL 2.9.4** — **BSD 3-Clause License** (fetched at runtime from official Mojang mirrors).
- **Java Native Access (JNA)** — **Apache-2.0 / LGPL-2.1** (provided by the Minecraft 1.8.9 classpath).
- **JUnit 4** — **EPL-1.0** (test scope only; not redistributed).
- **Root CA Certificates (`src/main/resources/assets/purefps/certs/modern_roots.crt`)** — 4 public X.509 root CA certificates (ISRG Root X1, DigiCert Global Root G2, Baltimore CyberTrust Root, GlobalSign Root CA) used solely to supplement legacy Java 8 truststores for HTTPS skin/session requests.

---

## 4. Windows Launcher Dependencies (`launcher/Cargo.lock`)

`MashClient.exe` is statically linked against Rust crates pinned in `launcher/Cargo.lock` (304 resolved packages; **0 GPL/LGPL crates**). Major direct & transitive dependencies include:

| Component / Crate | License | Role |
| :--- | :--- | :--- |
| `tao`, `wry`, `tray-icon` (Tauri) | Apache-2.0 / MIT | Native Win32 windowing, WebView2 hosting, system tray |
| `webview2-com` & **Microsoft Edge WebView2 SDK** | MIT / Microsoft WebView2 SDK License | Windows WebView2 runtime bindings (`WebView2Loader`) |
| `ureq`, `rustls`, `webpki-roots` | MIT / Apache-2.0 / ISC / CDLA-Permissive-2.0 | Pure-Rust HTTPS client & TLS 1.2/1.3 verification |
| `serde`, `serde_json` | MIT / Apache-2.0 | Configuration and IPC JSON serialization |
| `sha1`, `sha2`, `hex`, `zip`, `flate2` | MIT / Apache-2.0 | Cryptographic integrity checks & archive extraction |
| `windows-sys`, `winres`, `rfd` | MIT / Apache-2.0 | Win32 API bindings, icon resources, native file dialogs |
| `option-ext` (`0.2.0`), `cssparser` (`0.38.0`), `selectors` (`0.41.0`) | **MPL-2.0** | Transitive crates (unmodified upstream crates from crates.io; source available on <https://crates.io>) |
| Unicode tables (`unicode-*`, `icu_*`, `idna`) | **Unicode-3.0** / MIT / Apache-2.0 | Internationalized domain and text handling |

---

## 5. Runtime Components Downloaded Directly on User Machines (Not Redistributed Here)

At runtime, `MashClient.exe` downloads official runtime components directly from their upstream distributors (each verified by pinned SHA-1 or fail-closed SHA-256 checksums):

- **Minecraft 1.8.9 Client & Assets** — Mojang Studios / Microsoft (subject to the Minecraft EULA; requires a valid Minecraft: Java Edition account).
- **Minecraft Forge 1.8.9 & Libraries** — Official Forge / Maven Central mirrors.
- **MultiMC `log4j-core-2.0-beta9-fixed.jar`** — Apache-2.0 (patched Log4j2 removing `JndiLookup.class` to mitigate CVE-2021-44228).
- **Eclipse Temurin JRE 8 x64** — GPL-2.0 with Classpath Exception (optional one-click download from Adoptium / GitHub Releases, verified via official `.sha256.txt`).
