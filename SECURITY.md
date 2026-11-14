# Politique de sécurité

## Versions prises en charge

| Version | Statut |
| :--- | :--- |
| `4.4.x` | Prise en charge (mises à jour de sécurité et correctifs) |
| `< 4.4.0` | Non maintenue — utilisez la dernière version publiée dans GitHub Releases |

## Gestion des secrets et accès administrateur

Le code source public de `MyMasha/ProjectFPS` ne contient aucune clé API ni jeton d'authentification en dur.

### 1. Secrets GitHub Actions (réservés aux administrateurs)

Les secrets de compilation officielle sont stockés exclusivement dans **Settings -> Secrets and variables -> Actions** sur GitHub. Ils ne sont **pas accessibles aux utilisateurs non-administrateurs** et ne sont jamais injectés dans les workflows déclenchés par des Pull Requests externes :

- `PURECLIENT_UPDATER_TOKEN` : jeton GitHub Fine-Grained en lecture seule (`Contents: Read`) utilisé par l'Auto-Updater intégré de `MashClient.exe` afin de disposer d'un quota API étendu (avec repli automatique sans jeton sur l'API publique GitHub Releases).
- `MASHCLIENT_MS_CLIENT_ID` : identifiant d'application OAuth2 (Device Code Flow) utilisé lors de la compilation officielle de `MashClient.exe` pour la connexion Microsoft / Xbox Live / Minecraft.

Le workflow de publication (`.github/workflows/release.yml`) est restreint au dépôt `MyMasha/ProjectFPS` sur poussée de tag `v*` ou déclenchement manuel administrateur (`workflow_dispatch`). Les vérifications publiques (`.github/workflows/ci.yml`) s'exécutent en lecture seule (`contents: read`) sans aucun secret.

### 2. Fichiers secrets locaux (ignorés par Git)

Lors d'une compilation locale par un administrateur, les fichiers suivants sont lus par `launcher/build.rs` s'ils existent, et sont strictement exclus du contrôle de version par `.gitignore` :

- `launcher/.github_token` (ou variable d'environnement `PURECLIENT_GITHUB_TOKEN`)
- `launcher/.ms_client_id` (ou variable d'environnement `MASHCLIENT_MS_CLIENT_ID`)
- `local.properties` / `gradle-local.properties` (chemins JDK locaux éventuels)

Un modèle vierge sans donnée sensible est disponible dans [`launcher/.env.example`](launcher/.env.example).

### 3. Données locales du joueur sous Windows

Sur le poste de l'utilisateur, les préférences et la session sont stockées uniquement en local dans `%APPDATA%\MashClient\` :

- `account.json` : session Microsoft / Minecraft locale du joueur (jamais transmise à un serveur tiers ; les échanges OAuth2 s'effectuent directement avec `login.microsoftonline.com`, `xboxlive.com` et `api.minecraftservices.com`).
- `launcher.json` : configuration locale du launcher (RAM, chemins Java/instance, préférences).

## Signaler une vulnérabilité

Ne publiez pas d'issue publique contenant des détails d'exploitation ou des jetons actifs. Utilisez la fonctionnalité **Report a vulnerability** de l'onglet **Security** du dépôt GitHub `MyMasha/ProjectFPS` afin de contacter directement l'administrateur (`@MyMasha`).
