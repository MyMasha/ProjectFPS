# MashClient

Client et launcher open source pour **Minecraft Forge 1.8.9**, axé sur les performances OpenGL, les fonctionnalités PvP et une interface sobre.

Le dépôt regroupe deux composants :
- **`purefps` (`src/`)** : mod client Forge `1.8.9` écrit en Java 8 avec SpongePowered Mixin.
- **`MashClient.exe` (`launcher/`)** : launcher Windows natif léger écrit en Rust (`tao` + `wry` / WebView2).

---

## Téléchargement et installation

Les binaires précompilés sont publiés dans les [**GitHub Releases**](https://github.com/MyMasha/ProjectFPS/releases/latest) :

1. **Avec le Launcher Windows (`MashClient.exe`)**
   - Téléchargez `MashClient.exe` depuis la dernière release.
   - Au lancement, le launcher détecte automatiquement votre installation **PrismLauncher** (instance Forge `1.8.9`) ou **`.minecraft`**, synchronise le mod `purefps-*.jar` dans le dossier `mods/` et vérifie les mises à jour.

2. **Mod seul (`purefps-*.jar`)**
   - Installez **Minecraft Forge 1.8.9** (`11.15.1.2318`).
   - Placez `purefps-4.5.13.jar` dans votre dossier `.minecraft/mods/`.

---

## Fonctionnalités principales

### Moteur et performances (`1.8.9`)
- Pipeline OpenGL & Vulkan haute fréquence débridé (*NVIDIA Threaded Optimization* sans `SharedDrawable`, arènes régionales `GL14.glMultiDrawArrays` `RegionLayerArena` regroupant les couches `SOLID` / `CUTOUT_MIPPED` / `CUTOUT`, copie FBO opaque matérielle, ordonnanceur de chunks différé sans verrou `SmartChunkUpdateScheduler`).
- **Instance séparée `1.8.9 PureFPS Vulkan` (`BÊTA`)** : traduction OpenGL -> Vulkan via **Mesa3D Zink** (`opengl32.dll` + isolation JRE DotLocal + swapchain opaque `Alpha = 1.0`) dans un répertoire d'instance totalement indépendant de `1.8.9 PureFPS`.
- Déchargement intérieur des îles (*Island Core Culling*), culling frustum rapide sur les entités, coffres, panneaux et *Item Frames* compilés.
- **Rechargement rapide des Resource Packs** sans coupure ni redémarrage du moteur audio OpenAL, avec **barre de recherche en temps réel** et navigation dans les **sous-dossiers** de `resourcepacks/`.
- **Captures d'écran `F2` sans micro-freeze** : encodage PNG asynchrone hors thread de rendu et actions cliquables **`[Ouvrir]`** / **`[Copier]`** directement dans le chat.
- **Reconnexion rapide** : bouton *Dernier serveur* dans le menu Multijoueur et bouton *Reconnecter* sur l'écran de déconnexion.
- Déchargement du rendu monde sous menu ouvert, mode plein écran sans bordure (*Borderless Windowed*) et limiteur FPS automatique hors focus.

### Modules et configuration (`RSHIFT` & Launcher)
- **200+ modules** répartis en 6 catégories : *HUD*, *PvP*, *Visuel*, *Hypixel*, *Monde* et *FPS* (incluant `TNT Timer` 3D interpolé BedWars/Vanilla, *Armor Status*, *Toggle Sprint*, *Custom Crosshair*, *Time Changer*, *Zoom*, etc.).
- **Recherche bilingue FR / EN** dans le menu `RSHIFT` et dans le launcher.
- **Sous-paramètres au clic droit** sur chaque module (dans le launcher comme en jeu) : curseur personnalisé, FOV dynamique, zoom progressif à la molette, position du bras/objet (*Viewmodel*), intensité HurtCam, couleurs et échelles.
- **Studio HUD visuel 16:9 dans le Launcher & en jeu** : déplacement magnétique des widgets HUD avec guides d'alignement centraux et marges sans avoir à lancer Minecraft.
- **Profils personnalisés** : sauvegarde de configurations nommées et **Import / Export `.json`** en un clic.

### Launcher Windows (`MashClient.exe`)
- **Sélecteur d'instance** dans le dock inférieur : bascule immédiate entre **`1.8.9 PureFPS`** (OpenGL) et **`1.8.9 PureFPS Vulkan`** (`BÊTA`).
- **Allocation mémoire Java jusqu'à `64 Go` (`65 536 Mo`)** avec profil G1GC et compilateur JIT HotSpot C2 réglés pour les très hauts FPS.
- **Serveurs rapides instantanés** : connexion directe au lancement vers *Hypixel*, *PikaNetwork*, *JartexNetwork*, *Minemen Club*, *BlocksMC* ou vos propres serveurs favoris.
- **Gestionnaire & Importation de Mods tiers (`.jar`)** : onglet dédié permettant d'importer vos propres mods Forge `1.8.9`, de les activer/désactiver individuellement et de désactiver en un clic un mod tiers responsable d'un crash détecté.
- **Multi-comptes Microsoft OAuth2** (*Device Code Flow*) et comptes hors-ligne avec changement de compte instantané.
- **Runtime Java 8 récent automatique** : détection classée des installations Java 8 64-bit et téléchargement/installation en un clic d'**Eclipse Temurin JRE 8 x64 (`8u400+`)**.
- Instance unique sous Windows et réduction automatique dans la zone de notification système (*System Tray*, `0 %` d'activité WebView2/GPU) dès l'ouverture de Minecraft.
- **Auto-Updater** connecté à GitHub Releases pour mettre à jour `purefps-*.jar` et `MashClient.exe`.

---

## Structure du projet

```text
.
├── src/main/java/dev/purefps/   # Code source du mod Forge 1.8.9 (Mixins, rendu OpenGL, HUD, modules)
├── src/test/java/dev/purefps/   # Tests unitaires Java (JUnit 4)
├── launcher/                    # Launcher Windows natif en Rust + interface WebView2
│   ├── src/                     # Code Rust (auth, config, dialog, mods_manager, runner, runtimes, tray, updater, ui.html)
│   ├── assets/                  # Icônes et ressources embarquées (.ico, .png)
│   └── .env.example             # Modèle des variables de compilation administrateur
├── .github/workflows/           # CI publique (ci.yml) et publication des releases (release.yml)
└── SECURITY.md                  # Politique de sécurité et documentation des secrets administrateur
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

Le fichier final obfusqué est généré dans `build/libs/purefps-4.5.13.jar`.

### 2. Launcher Windows (`MashClient.exe`)

**Prérequis** : **Windows 10/11** (WebView2 Runtime préinstallé) et **Rust stable** (`x86_64-pc-windows-msvc` avec les outils C++ Windows SDK).

```powershell
cd launcher
cargo test
cargo build --release
```

L'exécutable est généré dans `launcher/target/release/MashClient.exe`.

---

## Secrets de compilation et sécurité

Aucune clé API secrète ni jeton GitHub n'est stocké dans le dépôt Git ou utilisé par l'Auto-Updater :

- **OAuth2 Microsoft (`MASHCLIENT_MS_CLIENT_ID`)** est configuré uniquement dans **GitHub Actions Secrets** (*Settings -> Secrets and variables -> Actions*), accessible exclusivement aux administrateurs du dépôt lors de la génération des releases officielles.
- L'**Auto-Updater** utilise exclusivement l'API publique GitHub Releases sans aucun jeton et vérifie les empreintes cryptographiques `SHA256SUMS.txt`. Une compilation open source reste pleinement fonctionnelle (un identifiant d'application Azure OAuth2 personnel peut être fourni via `MASHCLIENT_MS_CLIENT_ID`, voir [`launcher/.env.example`](launcher/.env.example) et [`SECURITY.md`](SECURITY.md)).
