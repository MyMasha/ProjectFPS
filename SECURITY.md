# Politique de sécurité

## Versions prises en charge

| Version | Statut |
| :--- | :--- |
| Dernière release (`1.x`) | Prise en charge (mises à jour de sécurité et correctifs) |
| Versions antérieures | Non maintenues — utilisez la dernière version publiée dans GitHub Releases |

## Gestion des secrets et intégrité des builds

Le code source public de `MyMasha/ProjectFPS` et les binaires officiels (`MashClient.exe`) ne contiennent **aucun jeton d'accès privé (PAT) ni secret API embarqué**.

### 1. Secrets GitHub Actions (réservés aux administrateurs)

Les paramètres de compilation officielle sont stockés exclusivement dans **Settings -> Secrets and variables -> Actions** sur GitHub. Ils ne sont **jamais injectés** dans les workflows déclenchés par des Pull Requests externes :

- `MASHCLIENT_MS_CLIENT_ID` : identifiant public d'application OAuth2 (Device Code Flow, sans client secret) utilisé lors de la compilation officielle de `MashClient.exe` pour la connexion Microsoft / Xbox Live / Minecraft.
- Les mises à jour automatiques interrogent l'API publique GitHub Releases de `MyMasha/ProjectFPS` et imposent une vérification cryptographique **SHA-256 stricte (*fail-closed*)** via `SHA256SUMS.txt` : toute absence de fichier de hachage ou discordance annule immédiatement l'installation.
- Les 53 bibliothèques et natifs Minecraft / Forge / LWJGL téléchargés au runtime sont épinglés par **SHA-1** avec validation explicite des hôtes HTTPS et de chaque saut de redirection (sans redirection vers des domaines tiers non autorisés). Les runtimes **Eclipse Temurin JRE 8** et **Mesa3D 26.3.0 Zink** sont également vérifiés par **SHA-256 fail-closed**.

Le workflow de publication (`.github/workflows/release.yml`) est restreint au dépôt `MyMasha/ProjectFPS` sur poussée de tag `v*` ou déclenchement manuel administrateur (`workflow_dispatch`), avec validation stricte SemVer, refus d'écrasement d'une release déjà publiée, timeouts de jobs, `persist-credentials: false`, remappage des chemins de compilation Rust et actions GitHub épinglées par SHA de commit complet. Les vérifications publiques (`.github/workflows/ci.yml`) s'exécutent en lecture seule (`contents: read`) sans aucun secret.

### 2. Fichiers locaux de développement (ignorés par Git)

Lors d'une compilation locale par un administrateur, les fichiers suivants sont strictement exclus du contrôle de version par `.gitignore` :

- `launcher/.ms_client_id` (ou variable d'environnement `MASHCLIENT_MS_CLIENT_ID`)
- `local.properties` / `gradle-local.properties` (chemins JDK locaux éventuels)
- `accounts.json`, `.session_token_*.tmp`, `*.token`, `*.secret`, certificats/clés privées (`*.pem`, `*.key`, `*.pfx`, `*.p12`, `*.jks`, `*.keystore`, `*.crt` hors certificats racines publics épinglés).

Un modèle vierge sans donnée sensible est disponible dans [`launcher/.env.example`](launcher/.env.example).

### 3. Données locales du joueur et défenses intégrées

Sur le poste de l'utilisateur, les préférences et sessions sont stockées uniquement en local dans `%APPDATA%\.PureFPS\` :

- `accounts.json` : jetons de session Microsoft / Minecraft chiffrés au repos via **Windows DPAPI** (`CryptProtectData` lié à la session utilisateur courante, ou permissions `0600` sous Unix). Les échanges OAuth2 s'effectuent directement en HTTPS avec `login.microsoftonline.com`, `xboxlive.com` et `api.minecraftservices.com`.
- **Passage sécurisé du jeton au processus jeu** : le jeton d'accès Minecraft n'est jamais passé en clair sur la ligne de commande (`--accessToken`). Il est transmis via un fichier éphémère à nom aléatoire (`.session_token_*.tmp`, créé de manière exclusive) validé et écrasé/supprimé immédiatement au démarrage par [`EphemeralSessionBridge`](src/main/java/dev/purefps/core/EphemeralSessionBridge.java).
- **Protection Log4Shell (CVE-2021-44228)** : téléchargement épinglé par SHA-1 des archives patchées MultiMC (`log4j-core-2.0-beta9-fixed.jar`, dont `JndiLookup.class` est supprimé) complété par le drapeau JVM `-Dlog4j2.formatMsgNoLookups=true`.
- **Isolation WebView2** : politique CSP stricte sans requête réseau externe (`default-src 'none'`), blocage de navigation/popups/téléchargements, verrouillage des arguments navigateur en build Release et validation d'origine sur le pont IPC.
- **Anonymisation des diagnostics** : les rapports copiés ou exportés via *Copy Full Diagnostic* masquent automatiquement les noms d'utilisateur dans les chemins (`C:\Users\<USER>\`), les jetons (`--accessToken`, `Bearer`, `github_pat_`) et la section `Environment Variables:` des journaux JVM `hs_err_pid*.log`.
- **Transparence Vulkan Bêta** : l'instance optionnelle `1.8.9 PureFPS Vulkan (BÊTA)` utilise un fichier DotLocal (`javaw.exe.local`) et une redirection d'imports PE64/IAT en mémoire (`VulkanBackendHelper.java`) exclusivement pour rediriger `opengl32.dll` vers la DLL **Mesa3D 26.3.0 Zink** vérifiée dans le dossier de natifs LWJGL. Les bibliothèques système Windows (`dwmapi.dll`, `shcore.dll`) sont chargées exclusivement depuis `System32`.

## Signaler une vulnérabilité

Ne publiez jamais d'issue publique contenant des détails d'exploitation, des jetons actifs ou des données personnelles.

- Si l'onglet **Security -> Report a vulnerability** (*Private Vulnerability Reporting*) est activé sur `MyMasha/ProjectFPS`, utilisez-le en priorité pour ouvrir un avis de sécurité privé.
- À défaut, contactez directement le mainteneur (`@MyMasha`) sans divulguer publiquement de preuve de concept avant la publication d'un correctif.
