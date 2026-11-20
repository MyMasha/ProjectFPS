# MashClient (PureFPS)

> **Avis légal Minecraft :** NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Non affilié à Hypixel Inc.

Client et launcher open source pour **Minecraft Forge 1.8.9**, axé sur les performances OpenGL & Vulkan (Bêta), les fonctionnalités PvP et une interface sobre.

Le dépôt regroupe deux composants :
- **`purefps` (`src/`)** : mod client Forge `1.8.9` écrit en Java 8 avec SpongePowered Mixin.
- **`MashClient.exe` (`launcher/`)** : launcher Windows natif léger écrit en Rust (`tao` + `wry` / WebView2).

---

## Téléchargement et installation

Les binaires précompilés sont publiés dans les [**GitHub Releases**](https://github.com/MyMasha/ProjectFPS/releases/latest) avec leurs empreintes cryptographiques `SHA256SUMS.txt` :

1. **Avec le Launcher Windows (`MashClient.exe`)**
   - Téléchargez `MashClient.exe` depuis la dernière release.
   - Le launcher crée et gère un environnement autonome isolé dans `%APPDATA%\.PureFPS\` (bibliothèques Forge `1.8.9`, mod `purefps-*.jar` synchronisé et vérifié par SHA-256, et détection/téléchargement optionnel d'Eclipse Temurin JRE 8 x64).
   - Connectez un compte **Microsoft** possédant une licence **Minecraft: Java Edition** valide.

2. **Mod seul (`purefps-*.jar`)**
   - Installez **Minecraft Forge 1.8.9** (`11.15.1.2318`).
   - Placez `purefps-<version>.jar` dans votre dossier `.minecraft/mods/`.

---

## Fonctionnalités principales

### Moteur et performances (`1.8.9`)
- **Pipeline OpenGL haute fréquence** : arènes régionales GPU indexées (`RegionLayerArena` + `GL14.glMultiDrawArrays` / EBO partagé `GL_TRIANGLES`), *NVIDIA Threaded Optimization* sans `SharedDrawable`, copie FBO opaque matérielle `GL30.glBlitFramebuffer`, batching de police Ring VBO + VAO (`FastFontBatcher`), ordonnanceur de chunks différé sans verrou (`SmartChunkUpdateScheduler`) et pools zéro-allocation.
- **Mode `1.8.9 PureFPS Vulkan (BÊTA)`** : traduction OpenGL -> Vulkan via **Mesa3D 26.3.0 Zink** (`dist/mashclient-vulkan-zink-win64.zip`, `opengl32.dll`, SHA-256 vérifié en mode *fail-closed*) avec isolation JRE Windows DotLocal (`javaw.exe.local`) et swapchain opaque (`Alpha = 1.0`).
- **Plein écran sans bordure exact (`F11`)** : résolution native exacte du moniteur pour bénéficier des performances plein écran tout en gardant un `Alt+Tab` instantané sans écran noir.
- Déchargement intérieur des îles (*Island Core Culling*), culling frustum P-vertex sur les chunks, entités, coffres, panneaux et *Item Frames* compilés en VBO.
- **Rechargement rapide des Resource Packs** sans coupure audio OpenAL, avec recherche temps réel et navigation dans les sous-dossiers.
- **Captures d'écran `F2` asynchrones** hors thread de rendu avec actions `[Ouvrir]` / `[Copier]` dans le chat.
- **Reconnexion rapide** (*Dernier serveur* et bouton *Reconnecter* sur l'écran de déconnexion) et limiteur FPS automatique hors focus.

### Modules et configuration (`RSHIFT` & Launcher)
- **200+ modules** répartis en 6 catégories : *HUD*, *PvP*, *Visuel*, *Hypixel*, *Monde* et *FPS* (`TNT Timer`, `Armor Status`, `Toggle Sprint`, `Custom Crosshair`, `Time Changer`, `Zoom`, `AutoGG` restreint à `*.hypixel.net` avec délai configurable, etc.).
- **Recherche bilingue FR / EN** dans le menu `RSHIFT` et dans le launcher.
- **Studio HUD visuel 16:9** dans le launcher et en jeu, avec profils nommés et **Import / Export `.json`**.

### Launcher Windows (`MashClient.exe`)
- **Sélecteur d'instance** dans le dock inférieur : bascule entre **`1.8.9 PureFPS`** (OpenGL) et **`1.8.9 PureFPS Vulkan (BÊTA)`**.
- **Allocation mémoire Java jusqu'à `64 Go`** avec profil G1GC et compilateur JIT HotSpot C2.
- **Serveurs rapides** : connexion directe au lancement vers *Hypixel*, *Minemen Club* ou vos propres serveurs favoris.
- **Gestionnaire & Importation de Mods tiers (`.jar`)** avec activation/désactivation individuelle et détection de crash.
- **Multi-comptes Microsoft OAuth2** (*Device Code Flow*, jetons chiffrés au repos par **Windows DPAPI**). Conformément à l'EULA Mojang, le **mode hors-ligne** est verrouillé par défaut et ne se débloque qu'après l'authentification d'au moins un compte Microsoft possédant une licence **Minecraft: Java Edition** valide.
- **Runtime Java 8 x64 automatique** : détection classée et téléchargement vérifié par SHA-256 d'**Eclipse Temurin JRE 8 x64**.
- **System Tray** (`0 %` d'activité WebView2/GPU pendant le jeu) et **Auto-Updater** sur GitHub Releases avec vérification cryptographique `SHA256SUMS.txt` stricte (*fail-closed*).

---

## Transparence technique, Antivirus & Règles Serveurs

- **Exécutable Windows non signé (SmartScreen)** : `MashClient.exe` n'est pas signé par un certificat commercial EV. Windows Defender SmartScreen peut afficher un avertissement au premier lancement. Vous pouvez vérifier l'empreinte `SHA256SUMS.txt` de chaque release ou compiler directement depuis les sources ci-dessous.
- **Mode Vulkan Bêta (Mesa3D Zink) & Screenshare PvP** : le mode Vulkan Bêta utilise une redirection DLL locale Windows (`javaw.exe.local` + `opengl32.dll` Mesa3D 26.3.0 Zink, SHA-256 `d3f71e2ef11c032a6d7b507ee2f6bab6ca3fb20543944af70982fc24b92604cb`) et un patch en mémoire de la table d'imports PE64/IAT (`VulkanBackendHelper.java`) afin de diriger LWJGL 2 vers Zink. Ce mécanisme bas niveau peut déclencher des alertes sur des outils de *screenshare* PvP stricts ; utilisez le mode **OpenGL standard** (`1.8.9 PureFPS`) si vous participez à des ligues ou serveurs imposant un contrôle screenshare.
- **Module Freelook 360° (`pvp_freelook`)** : **à utiliser à vos risques et périls** sur les serveurs qui restreignent la caméra libre 360° (comme Hypixel, qui a interdit Perspective Mod / Freelook en août 2021). Ce module est désactivable à tout moment depuis le menu `RSHIFT` ou le launcher.
- **Licences tierces** : voir [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) pour les notices complètes (Mesa3D MIT, Berkeley SoftFloat BSD-3-Clause, SpongeMixin MIT, WebView2 SDK, crates Rust et extraits GLSL).

---

## Structure du projet

```text
.
├── src/main/java/dev/purefps/   # Code source du mod Forge 1.8.9 (Mixins, rendu OpenGL/Vulkan, HUD, modules)
├── src/test/java/dev/purefps/   # Tests unitaires Java (JUnit 4)
├── launcher/                    # Launcher Windows natif en Rust + interface WebView2
│   ├── src/                     # Code Rust (auth, config, diagnostics, dialog, mods_manager, runner, runtimes, tray, updater, ui.html)
│   ├── assets/                  # Icônes et ressources embarquées (.ico, .png)
│   └── .env.example             # Modèle des variables de compilation administrateur
├── dist/                        # Bundle Mesa3D 26.3.0 Zink vérifié par SHA-256
├── .github/workflows/           # CI publique (ci.yml) et publication des releases (release.yml)
├── THIRD_PARTY_LICENSES.md      # Notices légales des dépendances et de Mesa3D / SoftFloat
└── SECURITY.md                  # Politique de sécurité et intégrité des builds
```

---

## Compiler depuis les sources

### 1. Mod Minecraft Forge 1.8.9 (`purefps-*.jar`)

**Prérequis** : **JDK 8** (cible de compilation Minecraft 1.8.9) et **JDK 17** (exécution d'Architectury Loom / Gradle).

```bash
# Linux / macOS
./gradlew test remapJar

# Windows (PowerShell)
.\gradlew.bat test remapJar
```

Si vos JDK ne sont pas dans les emplacements standards détectés automatiquement par Gradle, indiquez-les via la ligne de commande ou dans votre fichier `~/.gradle/gradle.properties` personnel (ne les ajoutez pas au `gradle.properties` du dépôt) :

```bash
./gradlew test remapJar \
  -Dorg.gradle.java.home=/chemin/vers/jdk17 \
  -Porg.gradle.java.installations.paths=/chemin/vers/jdk8,/chemin/vers/jdk17
```

Le fichier final obfusqué est généré dans `build/libs/purefps-<version>.jar`.

### 2. Launcher Windows (`MashClient.exe`)

**Prérequis** : **Windows 10/11** (WebView2 Runtime préinstallé) et **Rust stable** (`x86_64-pc-windows-msvc` avec les outils C++ Windows SDK).

```powershell
cd launcher
cargo test -- --skip test_live_microsoft_device_code_endpoint
cargo build --release
```

L'exécutable est généré dans `launcher/target/release/MashClient.exe`.

---

## Secrets de compilation et sécurité

Aucune clé API secrète ni jeton GitHub (PAT) n'est stocké dans le dépôt Git ou utilisé par l'Auto-Updater :

- **OAuth2 Microsoft (`MASHCLIENT_MS_CLIENT_ID`)** est configuré uniquement dans **GitHub Actions Secrets** (*Settings -> Secrets and variables -> Actions*) lors de la génération des releases officielles. Un contributeur compilant depuis les sources peut fournir son propre Client ID Azure AD public (voir [`launcher/.env.example`](launcher/.env.example) et [`SECURITY.md`](SECURITY.md)).
- **Intégrité cryptographique stricte (*fail-closed*)** : l'Auto-Updater, l'installeur Eclipse Temurin JRE 8 et le module Vulkan Zink refusent toute installation si l'empreinte SHA-256 est manquante ou invalide, et les 53 bibliothèques/natifs Minecraft/Forge/LWJGL sont épinglés par SHA-1 avec vérification stricte des hôtes HTTPS et redirections.

---

## Licence

Ce projet (**PureFPS / MashClient**) est distribué sous licence **GNU General Public License v3.0 (GPL-3.0)** — consultez le fichier [`LICENSE`](LICENSE) pour le texte intégral.

Les composants tiers redistribués ou liés conservent leurs licences respectives, détaillées dans [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).
