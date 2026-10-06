# Village Board

Mod Fabric (Minecraft 26.2) pour gérer un village peuplé de nombreux villageois, depuis un **tableau de mairie**.
Le mod s'installe **sur le serveur et sur chaque client**.

## En jeu

### Fonder un village
1. Fabriquez un **Tableau de la mairie** et posez-le. Le village est fondé, et les villageois déjà présents sont recensés.
   Pour lui donner un nom, renommez le tableau dans une enclume avant de le poser. Sinon, il s'appellera « Village de &lt;joueur&gt; ».
2. Posez des **Bornes du village** autour du village. Chaque borne se rattache à la mairie la plus proche (à 256 blocs maximum)
   et devient un coin du territoire. Avec moins de 3 bornes, le territoire est un cercle provisoire de 48 blocs autour du tableau.
3. Pour voir les limites, tenez une borne ou un tableau en main : des particules les dessinent au sol. On peut aussi les afficher
   en permanence depuis l'onglet Territoire. Un message s'affiche quand on entre ou sort d'un village.

```
Tableau de la mairie        Borne du village (×4)            Contrat de travail (sans forme)
  [papier][cloche][papier]        [teinture jaune]                 papier + plume + poche d'encre
  [planches ×3]                   [pierre taillée sculptée]
  [bâton]   [ ]   [bâton]         [muret de pierre taillée]
```

### Le tableau (clic droit)
- **Gazette** : naissances (avec les parents), décès (avec la cause), prises et pertes d'emploi, arrivées,
  déménagements, villageois changés en zombie ou en sorcière, guérisons, renommages, évolution du territoire.
- **Habitants** : liste rangée par catégorie (tous, enfants, sans emploi, chaque métier, niais). Un clic sur un villageois ouvre sa fiche :
  portrait animé, métier, niveau, santé, expérience, position, poste de travail, lit, date d'arrivée ou de naissance.
  - **Renommer** : son nom s'affiche au-dessus de sa tête, comme avec un nametag. Laisser vide retire le nom.
  - **Localiser** : contour doré visible à travers les murs, et boussole dans la barre d'action pendant 30 s.
  - **Verrouiller** : le métier ne change plus, même si le poste de travail disparaît. Le villageois n'est **pas** lié à un
    poste précis : il peut changer de poste du même métier. Pour l'attacher à un poste, utilisez un contrat de travail.
  - **Réinitialiser** : le villageois quitte son poste, qui est libéré, perd son XP et ses échanges, puis cherche un nouveau travail.
- **Territoire** : carte interactive peinte d'après le terrain (comme une carte vanilla) : territoire teinté et cerné,
  villages voisins en bleu, bornes, mairie, habitants colorés par statut, liens vers les postes attitrés, position du joueur.
  Molette = zoom, glisser = déplacer, survol = nom, clic sur un habitant = sa fiche, boutons + / − / recentrer,
  « ? » = légende.

### Logement
- Les lits du territoire apparaissent sur la carte : verts s'ils sont libres, rouges s'ils sont occupés. Au survol d'un lit,
  on voit son occupant ; au survol d'un habitant, un trait bleu le relie à son lit. Un clic sur un lit ouvre la fiche de son occupant.
- L'onglet Territoire résume le logement : nombre de lits, lits libres, nombre de sans-abri.
- La catégorie **Sans abri** de l'onglet Habitants liste les villageois sans lit. Dans chaque fiche, le lit s'affiche avec un lien **[carte]**.
- La gazette annonce quand il ne reste **plus aucun lit libre** (les villageois ne peuvent alors plus avoir d'enfants),
  puis quand des lits se libèrent.

Les villageois **sans nom** restent anonymes : « Sans nom » dans les listes, « un villageois sans nom » dans la gazette.
Un villageois vu hors des bornes pendant 60 s (`leaveDelaySeconds`) est rayé du registre et la gazette annonce son départ.

### Contrat de travail (lier un villageois à un poste)
1. Clic droit sur un villageois avec le contrat : il est inscrit dessus.
2. Clic droit sur un poste de travail (pupitre, composteur…) à moins de 48 blocs : il y est affecté, change de métier
   si besoin (s'il n'a pas encore d'expérience), et l'occupant éventuel est délogé.
3. Le villageois garde ce poste : s'il le perd de vue, il y est réaffecté. Si le poste est détruit, le lien est rompu
   (annoncé dans la gazette). On peut aussi le libérer depuis sa fiche (« [libérer] »).

Clic droit avec un contrat vierge sur un poste : indique qui l'occupe. Accroupi + clic droit dans le vide : efface le contrat.

### Droits
- Tout le monde peut consulter le tableau. Les actions se font à moins de 8 blocs du tableau.
- Seuls le fondateur et les opérateurs peuvent retirer le tableau, ce qui dissout le village.
- Gérer les villageois et les bornes est ouvert à tous par défaut. Avec `"managers": "founder"` dans `config/villageboard.json`,
  c'est réservé au fondateur et aux opérateurs.

### Commandes (opérateurs)
- `/villageboard list` : liste les villages.
- `/villageboard remove <id>` : dissout un village.

## Installation sur ton serveur
Copie `build/libs/villageboard-<version>.jar` dans le dossier `mods/` du serveur **et** dans celui de chaque joueur
(en retirant l'ancienne version du mod).
Il faut aussi **Fabric API** des deux côtés.

Les données sont dans `<monde>/villageboard/villages.json` (enregistrées toutes les 60 s et à l'arrêt). La configuration est dans `config/villageboard.json`.

## Développement (tout passe par Docker)

```powershell
docker compose run --rm build                     # compile → build/libs/
$env:MC_OPS="TonPseudo"; docker compose up -d minecraft   # serveur Fabric 26.2 de test
docker compose restart minecraft                  # recharge le mod après une recompilation
docker exec -it villageboard-fabric rcon-cli      # console du serveur
```

Les textures sont générées par `tools/GenTextures.java` :
`docker run --rm -v "${PWD}:/p" -w /p gradle:9-jdk25 java tools/GenTextures.java src/main/resources/assets/villageboard/textures/block`

### Organisation
```
src/main/java/fr/villageboard/        commun (serveur + client)
├── block/      tableau de la mairie, borne
├── village/    VillageManager (registre, bornes, gazette), VillagerActions, WorkAssignments (postes attitrés), Housing (lits),
│               Territory (polygone), événements, commande
├── item/       contrat de travail
├── net/        paquets réseau (vue du tableau, frontières, actions)
└── mixin/      VillagerMixin : changements de métier (verrou + gazette), naissances
src/client/java/fr/villageboard/client/
├── BoardScreen.java      l'écran du tableau
├── TerritoryMap.java     la carte interactive du territoire
└── BorderDisplay.java    particules des frontières, message d'entrée/sortie
```

L'ancien plugin Paper est archivé dans `archive/paper-plugin/`.
