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
                                                                 Bail de logement (sans forme)
                                                                 papier + plume + poche d'encre + laine
  [planches ×3]                   [pierre taillée sculptée]      Acte de mariage (sans forme)
  [bâton]   [ ]   [bâton]         [muret de pierre taillée]        papier + plume + poche d'encre + pépite d'or
```

### Le tableau (clic droit)
- **Gazette** : naissances (avec les parents), décès (avec la cause), prises et pertes d'emploi, arrivées, départs,
  déménagements, villageois changés en zombie ou en sorcière, guérisons, renommages, contrats et baux, mariages et
  divorces, logement complet ou libéré, évolution du territoire, tableau démonté ou déplacé.
- **Habitants** : liste rangée par catégorie (tous, enfants, sans abri, sans emploi, chaque métier, niais). Un clic sur un
  villageois ouvre sa fiche : portrait animé, métier, niveau, santé, expérience, position, poste de travail, lit, date
  d'arrivée ou de naissance, famille (avec un lien vers son arbre) et conjoint.
  - **Renommer** : son nom s'affiche au-dessus de sa tête, comme avec un nametag. Laisser vide retire le nom.
  - **Localiser** : contour doré visible à travers les murs, et boussole dans la barre d'action pendant 30 s.
  - **Verrouiller** : le métier ne change plus, même si le poste de travail disparaît. Le villageois n'est **pas** lié à un
    poste précis : il peut changer de poste du même métier. Pour l'attacher à un poste, utilisez un contrat de travail.
  - **Réinitialiser** : le villageois quitte son poste, qui est libéré, perd son XP et ses échanges, puis cherche un nouveau travail.
- **Familles** : arbre généalogique de tout le village (voir « Généalogie »).
- **Besoins** : ce qui manque au village (voir « Besoins du village »).
- **Territoire** : carte interactive peinte d'après le terrain (comme une carte vanilla) : territoire teinté et cerné,
  villages voisins en bleu, bornes, mairie, habitants colorés par statut, liens vers les postes attitrés, position du joueur.
  Molette = zoom, glisser = déplacer, survol = nom, boutons + / − / recentrer / filtres / aide.
  Un **clic** sur un habitant, un lit ou un poste l'épingle : ses liens (lit, poste) restent affichés et un encart
  résume où il dort et travaille, avec un lien vers sa fiche. Re-clic ou clic dans le vide pour le relâcher.
  Le bouton **entonnoir** ouvre la légende, qui sert aussi de filtre : une case par type de point (habitants par statut,
  logés / sans abri, lits libres / occupés, postes libres / occupés, bornes, joueur). Un habitant n'est affiché que si
  son statut **et** son logement sont cochés : décocher « Logés » ne laisse voir que les sans-abri, qui ont un contour orange.
  Les filtres sont gardés tant que le jeu tourne.

### Parler aux villageois
Un clic droit sur un villageois (mains vides ou objet ordinaire) ne lance plus directement les échanges : il dit d'abord
une **réplique**, affichée dans une boîte en bas de l'écran (tête, nom, métier, texte qui s'écrit lettre par lettre).
Pendant que la réplique s'écrit, le villageois babille : le « hmm » *idle3* des villageois, répété toutes les quelques
lettres, avec sa propre hauteur de voix (plus aiguë pour un enfant), plus haut sur une question ou une exclamation,
plus bas sur « … ».
À la fin, un villageois qui a un métier fait le bruit de son travail (page tournée, enclume, seau…) ; `"jobSound": false`
dans la config le désactive.
Un **second clic droit** sur lui dans les 10 secondes ouvre les échanges. Accroupi + clic droit ouvre les échanges
directement ; une étiquette, une laisse ou un œuf d'apparition gardent leur effet normal. Les enfants, niais et
sans-emploi parlent aussi (à chaque clic).

Les répliques sont dans **`config/villageboard/dialogues.txt` sur le serveur** (en solo : dans le dossier `config` du jeu).
Il est créé avec des exemples au premier lancement et relu automatiquement à chaque modification. Quand une nouvelle
version du mod apporte d'autres exemples (`src/main/resources/villageboard/dialogues_default.txt`), le fichier est
remplacé s'il n'a pas été retouché à la main ; sinon il est gardé tel quel et la nouvelle version est écrite à côté,
dans `dialogues.txt.nouveau`.

```
[tous]
Bonjour, {joueur} !
[fermier]
Les carottes poussent bien cette année.
[marie]
{conjoint} m'attend à la maison.
```

Sections : `[tous]`, `[enfant]`, un métier (`[fermier]`, `[bibliothecaire]`… ou l'identifiant vanilla), `[sans_abri]`,
`[marie]`, `[veuf]`, `[celibataire]`, `[parent]`, `[nuit]`, `[pluie]`. Variables : `{joueur}`, `{nom}`, `{metier}`,
`{village}`, `{conjoint}` ; une réplique dont une variable n'a pas de valeur (villageois sans nom…) n'est pas choisie.
La réplique est tirée au hasard parmi toutes les sections qui concernent le villageois, sans répéter la précédente.

### Fiche au commerce
Quand les échanges d'un villageois s'ouvrent, une petite fiche s'épingle à gauche de la fenêtre de commerce, qui se
décale vers la droite pour lui faire de la place : sa tête, son nom, son métier, s'il a un lit ou s'il est sans abri,
et sa famille (conjoint ou célibataire, parents, nombre d'enfants et de frères et sœurs).

### Généalogie
Le mod tient un **état civil** : à chaque naissance, il note les deux parents. Ces entrées sont gardées après la mort ou
le départ des villageois. Dans la fiche, la ligne **Famille** compte les parents, frères et sœurs (demi-frères et
demi-sœurs compris) et enfants connus. Le lien **[arbre]** ouvre l'arbre généalogique, qui montre les grands-parents,
les parents, le villageois parmi ses frères et sœurs, puis ses enfants. Les morts (✝ et jour du décès), les partis et
ceux qui vivent dans un autre village restent visibles. Un clic sur une case recentre l'arbre ; « Voir la fiche » ouvre
celle du villageois centré s'il est encore au registre.
Les villageois arrivés adultes n'ont pas de parents connus. Les naissances d'avant la v0.8 sont reprises quand le nom
de chaque parent ne désigne qu'un seul habitant.

L'onglet **Familles** montre l'arbre de tout le village : un bloc par famille, une ligne par génération, puis les
habitants sans famille connue. Chaque case montre la tête du villageois (biome et tenue de son métier), son nom et son
métier. Les morts sont pâlis et marqués ✝, les absents ont un cadre clair. Double trait rouge = mariés, trait pâle =
veuvage, pointillés = divorcés. Molette = zoom, glisser = déplacer, « Recentrer » pour tout revoir. Un clic sur une case
ouvre la fiche du villageois, ou son arbre s'il n'est plus au registre.

### Mariages et couples
- **Mariage d'office** : deux villageois célibataires qui ont un enfant ensemble sont mariés.
- **Acte de mariage** (papier + plume + poche d'encre + pépite d'or) : clic droit sur un villageois, puis sur son futur
  conjoint (à moins de 16 blocs). Les deux doivent être adultes, célibataires et ne pas être proches parents.
- **Couple exclusif** : un villageois marié n'a d'enfants qu'avec son conjoint.
- **Proches parents** : pas d'enfant ni de mariage entre un parent et son enfant, ni entre frères et sœurs
  (demi-frères et demi-sœurs compris).
- **Fin du couple** : à la mort de l'un des deux (l'autre est veuf et peut se remarier), ou par un divorce prononcé
  depuis la fiche (« [divorcer] », deux clics). Un conjoint parti du village reste marié.
- La fiche indique le conjoint (et depuis quel jour), le conjoint décédé, ou « Célibataire ». La gazette annonce
  les mariages et les divorces.
- Lors du passage à la v0.9, les parents encore en vie et célibataires ont été mariés.

### Besoins du village
L'onglet **Besoins** liste ce qui manque au village, du plus grave au moins grave (le nombre de besoins importants
s'affiche sur l'onglet, avec une épingle rouge en cas d'urgence) :

| Besoin | Gravité |
|---|---|
| Des sans-abri et aucun lit libre | urgent |
| Aucun lit libre : naissances bloquées | conseil |
| Des sans-emploi et aucun poste libre (avec suggestions de postes à construire) | conseil |
| Aucun fermier (nourriture pour la reproduction) | conseil |
| Aucun golem de fer (à partir de 5 habitants) | conseil |
| Sans-abri ou sans-emploi alors que des lits ou postes sont libres, postes sans preneur, pas de cloche, métiers absents | info |

Un clic sur un besoin mène là où agir : la liste des sans-abri, celle des sans-emploi, ou la carte.
Les postes de travail apparaissent aussi sur la carte : libres en cyan, occupés en violet.

### Logement
- Les lits du territoire apparaissent sur la carte : verts s'ils sont libres, rouges s'ils sont occupés. Au survol d'un lit,
  on voit son occupant ; au survol d'un habitant, un trait bleu le relie à son lit. Un clic sur un lit l'épingle et
  montre son occupant, avec un lien vers sa fiche.
- L'onglet Territoire résume le logement : nombre de lits, lits libres, nombre de sans-abri.
- La catégorie **Sans abri** de l'onglet Habitants liste les villageois sans lit. Dans chaque fiche, le lit s'affiche avec un lien **[carte]**.
- La gazette annonce quand il ne reste **plus aucun lit libre** (les villageois ne peuvent alors plus avoir d'enfants),
  puis quand des lits se libèrent.
- **Lits lointains** : un villageois ne voit les lits qu'à 48 blocs, pour se trouver un lit comme pour celui de son bébé
  (sans lit, la naissance échoue : éclairs au-dessus des parents). Le mod lui fait voir les lits libres **de tout le
  territoire du village**, jusqu'à 140 blocs, à condition qu'il puisse y aller à pied : un lit inaccessible le reste.
  Un sans-abri qui n'a rien trouvé à proximité se voit proposer un lit plus loin au recensement suivant.

Les villageois **sans nom** restent anonymes : « Sans nom » dans les listes, « un villageois sans nom » dans la gazette.
Un villageois vu hors des bornes pendant 60 s (`leaveDelaySeconds`) est rayé du registre et la gazette annonce son départ.

### Contrat de travail (lier un villageois à un poste)
1. Clic droit sur un villageois avec le contrat : il est inscrit dessus.
2. Clic droit sur un poste de travail (pupitre, composteur…) à moins de 48 blocs : il y est affecté, change de métier
   si besoin (s'il n'a pas encore d'expérience), et l'occupant éventuel est délogé.
3. Le villageois garde ce poste : s'il le perd de vue, il y est réaffecté. Si le poste est détruit, le lien est rompu
   (annoncé dans la gazette). On peut aussi le libérer depuis sa fiche (« [libérer] »).

Clic droit avec un contrat vierge sur un poste : indique qui l'occupe. Accroupi + clic droit dans le vide : efface le contrat.

### Bail de logement (lier un villageois à un lit)
Même principe avec un lit : clic droit sur un villageois, puis sur un lit (tête ou pied, à moins de 48 blocs).
L'occupant éventuel est délogé et cherchera un autre lit. Le villageois garde ce lit pour de bon : s'il le perd
(un joueur dort dedans, chemin bloqué…), il y est réinstallé dans les 2 secondes. Si le lit est cassé, le lien est
rompu et la gazette l'annonce. Utilisable aussi pour les enfants et les niais. Sur la carte, les lits attitrés sont
cerclés de doré ; dans la fiche, le lit attitré a des liens [carte] et [libérer].

### Déplacer le tableau
- Seuls le fondateur et les opérateurs peuvent retirer le tableau. Le village n'est **pas** dissous : il garde ses
  bornes, ses habitants, sa gazette et l'état civil, en attente d'un nouveau tableau. La gazette l'annonce.
- Poser un tableau sur son territoire, ou à moins de 256 blocs (`maxBorneDistance`) de **toutes** ses bornes, le
  rattache au village, avec ses bornes. Sans bornes, le territoire provisoire (cercle) se recentre sur le nouveau tableau.
- Un tableau qui disparaît sans être cassé par un joueur (/setblock, autre mod) est détecté au recensement suivant.
- Pour supprimer vraiment un village : `/villageboard remove <id>`. `/villageboard list` signale les villages sans tableau.

### Droits
- Tout le monde peut consulter le tableau. Les actions se font à moins de 8 blocs du tableau.
- Gérer les villageois et les bornes est ouvert à tous par défaut. Avec `"managers": "founder"` dans `config/villageboard.json`,
  c'est réservé au fondateur et aux opérateurs.

### Commandes (opérateurs)
- `/villageboard list` : liste les villages.
- `/villageboard info <id>` : population, sans-abri, sans-emploi, lits, postes par métier, cloches, golems.
- `/villageboard remove <id>` : dissout un village.

## Installation sur ton serveur
Copie `build/libs/villageboard-<version>.jar` dans le dossier `mods/` du serveur **et** dans celui de chaque joueur
(en retirant l'ancienne version du mod).
Il faut aussi **Fabric API** des deux côtés.

Les données sont dans `<monde>/villageboard/villages.json` et `family.json` (état civil), enregistrées toutes les 60 s et à l'arrêt. La configuration est dans `config/villageboard.json`.

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
├── village/    VillageManager (registre, bornes, gazette), Genealogy + Kin (état civil, couples), Marriages,
│               Dialogues (répliques), VillagerActions, Assignments (poste et lit attitrés),
│               Facilities (lits, postes, cloches), Territory (polygone), événements, commande
├── item/       contrat de travail, bail de logement, acte de mariage
├── net/        paquets réseau (vue du tableau, frontières, actions, réplique, fiche au commerce)
└── mixin/      VillagerMixin : changements de métier (verrou + gazette), naissances, couples, ouverture du commerce
src/client/java/fr/villageboard/client/
├── BoardScreen.java      l'écran du tableau
├── TerritoryMap.java     la carte interactive du territoire
├── FamilyTree.java       arbre d'un villageois ; VillageTree.java : arbre de tout le village
├── VillagerFace.java     tête d'un villageois dessinée d'après les textures vanilla
├── DialogueBox.java      réplique en bas de l'écran ; TradeCard.java : fiche à côté du commerce
├── VillageNeeds.java     calcul des besoins du village
├── BorderDisplay.java    particules des frontières, message d'entrée/sortie
└── mixin/                décalage de la fenêtre de commerce
```

L'ancien plugin Paper est archivé dans `archive/paper-plugin/`.
