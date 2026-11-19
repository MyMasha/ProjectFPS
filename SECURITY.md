# Politique de sécurité

## Versions prises en charge

| Version | Statut |
| :--- | :--- |
| `4.5.x` | Prise en charge (mises à jour de sécurité et correctifs) |
| `< 4.5.0` | Non maintenue — utilisez la dernière version publiée dans GitHub Releases |

## Gestion des secrets et intégrité des builds

Le code source public de `MyMasha/ProjectFPS` et les binaires officiels (`MashClient.exe`) ne contiennent **aucun jeton d'accès privé (PAT) ni secret API embarqué**.

### 1. Secrets GitHub Actions (réservés aux administrateurs)

Les paramètres de compilation officielle sont stockés exclusivement dans **Settings -> Secrets and variables -> Actions** sur GitHub. Ils ne sont **jamais injectés** dans les workflows déclenchés par des Pull Requests externes :

- `MASHCLIENT_MS_CLIENT_ID` : identifiant public d'application OAuth2 (Device Code Flow, sans client secret) utilisé lors de la compilation officielle de `MashClient.exe` pour la connexion Microsoft / Xbox Live / Minecraft.
- Les mises à jour automatiques interrogent l'API publique GitHub Releases de `MyMasha/ProjectFPS` et vérifient les empreintes cryptographiques **SHA-256** publiées dans `SHA256SUMS.txt`.

Le workflow de publication (`.github/workflows/release.yml`) est restreint au dépôt `MyMasha/ProjectFPS` sur poussée de tag `v*` ou déclenchement manuel administrateur (`workflow_dispatch`), avec validation stricte SemVer et actions GitHub épinglées par SHA de commit complet. Les vérifications publiques (`.github/workflows/ci.yml`) s'exécutent en lecture seule (`contents: read`) sans aucun secret.

### 2. Fichiers locaux de développement (ignorés par Git)

Lors d'une compilation locale par un administrateur, les fichiers suivants sont strictement exclus du contrôle de version par `.gitignore` :

- `launcher/.ms_client_id` (ou variable d'environnement `MASHCLIENT_MS_CLIENT_ID`)
- `local.properties` / `gradle-local.properties` (chemins JDK locaux éventuels)

Un modèle vierge sans donnée sensible est disponible dans [`launcher/.env.example`](launcher/.env.example).

### 3. Données locales du joueur et défenses intégrées

Sur le poste de l'utilisateur, les préférences et sessions sont stockées uniquement en local dans `%APPDATA%\.PureFPS\` :

- `accounts.json` : jetons de session Microsoft / Minecraft chiffrés au repos via **Windows DPAPI** (`CryptProtectData` lié à la session utilisateur courante, ou permissions `0600` sous Unix), sans aucun fichier `account.json` en clair. Les échanges OAuth2 s'effectuent directement en HTTPS avec `login.microsoftonline.com`, `xboxlive.com` et `api.minecraftservices.com`.
- `launcher.json` : configuration locale du launcher (RAM, chemins Java/instance validés, préférences).
- **Protection Log4Shell (CVE-2021-44228)** : rejet automatique de toute archive `log4j-core` contenant `JndiLookup.class` + drapeau JVM `-Dlog4j2.formatMsgNoLookups=true`.
- **Isolation WebView2** : politique CSP stricte sans requête réseau externe (`default-src 'none'`), blocage de navigation/popups/téléchargements et validation d'origine sur le pont IPC.

## Signaler une vulnérabilité

Ne publiez pas d'issue publique contenant des détails d'exploitation ou des jetons actifs. Utilisez la fonctionnalité **Report a vulnerability** de l'onglet **Security** du dépôt GitHub `MyMasha/ProjectFPS` afin de contacter directement l'administrateur (`@MyMasha`).
