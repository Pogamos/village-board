# VillageBoard

Plugin Paper (Minecraft 26.2) : un **tableau de mairie** pour gérer un village peuplé de nombreux villageois.
Côté joueur, rien à installer : on se connecte avec Minecraft vanilla.

## Fonctionnalités

- **Tableau de mairie** : n'importe quel bloc (panneau, bannière…) devient le tableau d'un village. Clic droit pour l'ouvrir.
- **Actualités** (gazette sous forme de livre) : naissances (avec les parents), décès (avec la cause),
  prises et pertes d'emploi, arrivées, déménagements, transformations en zombie et guérisons, renommages.
- **Habitants rangés par catégorie** : tous, enfants, sans emploi, chaque métier, niais.
- **Fiche d'un villageois** : métier, niveau, XP, santé, position, poste de travail, lit, date d'arrivée ou de naissance.
  - **Renommer** : nom affiché au-dessus de sa tête (comme un nametag). `-` retire le nom.
  - **Localiser** : contour lumineux doré visible à travers les murs, et flèche + distance dans la barre d'action.
  - **Verrouiller le métier** : le villageois ne prend ni ne perd plus de métier, même si son poste de travail disparaît.
  - **Réinitialiser le métier** : il quitte son poste, perd son XP et ses échanges, puis cherche un nouveau travail
    (comme si on cassait et reposait son poste de travail — il peut reprendre le même).
- Les villageois sans nom reçoivent automatiquement un prénom (`auto-names` dans `config.yml`).

## Commandes

| Commande | Rôle | Permission |
|---|---|---|
| `/vb` | Ouvre le tableau du village où l'on se trouve | `villageboard.use` (tous) |
| `/vb open <id>` · `/vb list` | Ouvrir un village précis / lister les villages | `villageboard.use` |
| `/vb create <nom>` | Le bloc visé devient le tableau d'un nouveau village | `villageboard.admin` (op) |
| `/vb radius <id> <rayon>` | Change le rayon du village (96 blocs par défaut) | `villageboard.admin` |
| `/vb remove <id>` | Supprime le village et son registre | `villageboard.admin` |

Les actions de la fiche (renommer, verrouiller, réinitialiser) demandent `villageboard.manage` (tous par défaut).

## Développement (tout passe par Docker)

```powershell
# Compiler → build/libs/VillageBoard.jar
docker compose run --rm build

# Lancer le serveur de test (Paper 26.2) ; ton pseudo devient opérateur
$env:MC_OPS="TonPseudo"; docker compose up -d minecraft

# Après une recompilation : redémarrer pour recharger le plugin
docker compose restart minecraft

# Console du serveur / logs
docker exec -it villageboard-mc rcon-cli
docker logs -f villageboard-mc
```

Connexion : Minecraft **26.2** → Multijoueur → `localhost`.

Le registre est enregistré dans `server-data/plugins/VillageBoard/villages/<id>.yml` (toutes les 60 s et à l'arrêt).

## Organisation du code

```
src/main/java/fr/villageboard/
├── VillageBoardPlugin.java      point d'entrée
├── model/                       Village, VillagerRecord, actualités
├── service/
│   ├── VillageService.java      registre : recensement, actualités, verrou
│   ├── VillageStore.java        sauvegarde YAML
│   ├── VillagerActions.java     renommer, localiser, réinitialiser
│   └── Professions.java         métiers en français, icônes, catégories
├── listener/                    événements du jeu (naissance, décès, métier…) et tableau
├── gui/                         menus en coffre et gazette
└── command/                     /vb
```
