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
   - Placez `purefps-4.4.2.jar` dans votre dossier `.minecraft/mods/`.

---

## Fonctionnalités principales

### Moteur et performances (`1.8.9`)
- Pipeline OpenGL optimisé compatible *NVIDIA Threaded Optimization* (suppression du `SharedDrawable` bloquant, VBO/VAO matériels, copie FBO DMA, ordonnanceur de chunks différé sans verrou `SmartChunkUpdateScheduler`).
- Déchargement intérieur des îles (*Island Core Culling*), culling frustum rapide sur les entités, coffres, panneaux et *Item Frames* compilés.
- Déchargement du rendu monde sous menu ouvert, mode plein écran sans bordure (*Borderless Windowed*) et limiteur FPS automatique hors focus.

### Modules et configuration en jeu (`RSHIFT`)
- **200+ modules** répartis en 6 catégories : *HUD*, *PvP*, *Visuel*, *Hypixel*, *Monde* et *FPS* (incluant `TNT Timer` 3D interpolé BedWars/Vanilla, *Armor Status*, *Toggle Sprint*, *Custom Crosshair*, *Time Changer*, *Zoom*, etc.).
- **Recherche bilingue FR / EN** dans le menu `RSHIFT` et dans le launcher.
- **Sous-paramètres au clic droit** sur chaque module : curseur personnalisé avec aperçu, FOV dynamique, zoom progressif à la molette, position du bras/objet (*Viewmodel*), intensité HurtCam, couleurs et échelles.
- **Éditeur HUD magnétique** : déplacement direct des widgets à l'écran avec lignes guides d'alignement.

### Launcher Windows (`MashClient.exe`)
- Instance unique sous Windows : relancer `MashClient.exe` réactive la fenêtre existante (même réduite dans la zone de notification).
- Réduction automatique dans la zone de notification système (*System Tray*, `0 %` d'activité WebView2/GPU) dès l'ouverture de Minecraft.
- Effets sonores d'interface et son d'ambiance du trou noir désactivables en un clic.
- Connexion **Microsoft OAuth2** officielle (*Device Code Flow*) ou mode hors-ligne.
- **Auto-Updater** connecté à GitHub Releases pour mettre à jour `purefps-*.jar` et `MashClient.exe`.

---

## Structure du projet

```text
.
├── src/main/java/dev/purefps/   # Code source du mod Forge 1.8.9 (Mixins, rendu OpenGL, HUD, modules)
├── src/test/java/dev/purefps/   # Tests unitaires Java (JUnit 4)
├── launcher/                    # Launcher Windows natif en Rust + interface WebView2
│   ├── src/                     # Code Rust (auth, config, runner, tray, updater, ui.html)
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

Le fichier final obfusqué est généré dans `build/libs/purefps-4.4.2.jar`.

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

Aucune clé API secrète n'est stockée dans le dépôt Git :

- **Auto-Updater (`PURECLIENT_UPDATER_TOKEN`)** et **OAuth2 Microsoft (`MASHCLIENT_MS_CLIENT_ID`)** sont configurés uniquement dans **GitHub Actions Secrets** (*Settings -> Secrets and variables -> Actions*), accessibles exclusivement aux administrateurs du dépôt lors de la génération des releases officielles.
- Une compilation open source réalisée sans ces secrets reste pleinement fonctionnelle : l'Auto-Updater utilise automatiquement l'API publique GitHub Releases sans jeton, et un identifiant d'application Azure OAuth2 personnel peut être fourni via `MASHCLIENT_MS_CLIENT_ID` (voir [`launcher/.env.example`](launcher/.env.example) et [`SECURITY.md`](SECURITY.md)).
