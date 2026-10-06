# Village Board

Mod Fabric (client + serveur) pour Minecraft **26.2** : tableau de mairie (gazette, registre des villageois par métier,
fiche avec renommer / localiser / verrouiller / réinitialiser), bornes qui délimitent le territoire, contrat de travail
qui lie un villageois à un poste. Le joueur parle français ; l'interface et les messages passent par les fichiers de langue.

## Outils : tout passe par Docker
La machine n'a que Java 8 et pas de Gradle. Ne pas lancer `./gradlew` ni `java` en local.

```sh
docker compose run --rm build                 # compile → build/libs/villageboard-<version>.jar
docker compose up -d minecraft                # serveur Fabric 26.2 de test (loader 0.19.3, comme le serveur du joueur)
docker compose restart minecraft              # recharge le mod après compilation
docker exec villageboard-fabric rcon-cli "<commande>"
```

- Sous Git Bash, préfixer `docker run` par `MSYS_NO_PATHCONV=1`. Avec `rcon-cli`, passer la commande en **une seule
  chaîne** : sinon les coordonnées négatives sont prises pour des options.
- Après un changement de version, supprimer l'ancien jar dans `build/libs/` **et** dans `server-data/mods/` (sinon les deux sont chargés).
- Textures : générées par `tools/GenTextures.java` (`java tools/GenTextures.java src/main/resources/assets/villageboard/textures/block`).

## Vérifier une API Minecraft / Fabric
Minecraft 26.x **n'est plus obfusqué** : noms Mojang, pas de mappings. Beaucoup de choses ont changé depuis 1.21, alors
vérifier les signatures avant d'écrire du code, sans se fier à sa mémoire. Les jars sont dans le volume Docker `villageboard-gradle-cache` :
`caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{common,clientonly}-deobf/26.2/*.jar` et
`caches/modules-2/.../net.fabricmc.fabric-api/*`. Lancer un conteneur `gradle:9-jdk25` qui monte ce volume, puis `javap`.

Pièges déjà rencontrés en 26.2 :
- `GuiGraphics` n'existe plus : c'est `GuiGraphicsExtractor`. `Screen.render` devient `extractRenderState`, et
  `extractBackground` est dessiné dans une couche en dessous des widgets. Les couleurs de texte et de remplissage sont en **ARGB** :
  sans alpha `0xFF`, elles sont invisibles.
- `mc.gui.screen()` / `mc.gui.setScreen(...)` ; `LocalPlayer`/`ServerPlayer.sendOverlayMessage(...)` pour la barre d'action.
- Villageois : `net.minecraft.world.entity.npc.villager.*` ; types d'entités dans `EntityTypes` (pas `EntityType.X`).
- Objets colorés : `Items.WOOL.pick(DyeColor.GREEN)`, `Items.BED.pick(...)`.
- Permissions : `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`.
- Jour de jeu : `level.getOverworldClockTime() / 24000`.
- `FriendlyByteBuf::writeBlockPos` est ambigu en référence de méthode : écrire une lambda.

## Architecture
- `src/main/java/fr/villageboard/` : code commun.
  - `village/VillageManager` : registre côté serveur. Une instance par serveur, thread serveur uniquement,
    sauvegarde JSON dans `<monde>/villageboard/villages.json`. Gère le recensement (`observe`), les déménagements et départs,
    la gazette, les bornes et les droits.
  - `village/Facilities` : équipements du territoire d'après les POI : lits (`HOME`), postes de travail (avec leur métier,
    via `Professions.forPoi`) et cloches (`MEETING`). Relus seulement dans les chunks chargés, avec un cache par chunk
    dans `Village.facilityCache` (non sauvegardé). Les golems sont comptés au recensement (`Village.golems`). L'état « lits libres / aucun » est annoncé après 2 recensements identiques.
    Minecraft n'efface la mémoire `HOME` d'un lit détruit qu'au coucher : `VillageManager.bedExists` vérifie que le lit existe encore.
  - `village/Assignments` : liaisons permanentes à un poste (contrat de travail, attache `BOUND_SITE`, mémoire `JOB_SITE`)
    ou à un lit (bail de logement, attache `BOUND_HOME`, mémoire `HOME`). `maintain()` réinstalle toutes les 2 s
    les villageois qui ont perdu leur poste ou leur lit, et rompt le lien si le bloc a disparu. `VillagerActions` : renommer, localiser, verrou, réinitialisation.
  - `village/Territory` : géométrie partagée client/serveur. Polygone des bornes trié par angle autour du barycentre ;
    cercle provisoire avec moins de 3 bornes.
  - `village/Genealogy` + `Kin` : état civil global (`<monde>/villageboard/family.json`) : parents (UUID), naissance,
    devenir (`Kin.Fate` : mort, parti, zombifié…). Une entrée par naissance et par parent, jamais effacée. Le tableau ne reçoit que
    la parenté des habitants du village sur 2 générations (`forVillage`). L'arbre est dessiné côté client par `FamilyTree`.
    Attention à l'ordre : `refresh` remet le devenir à ALIVE, donc il faut noter la mort ou le départ **après** le dernier `observe`/`refresh`.
  - Tableau retiré (cassé ou disparu, vu par `validateBoards`) : `Village.boardMissing`, le village est conservé. Un tableau
    posé sur son territoire ou près de toutes ses bornes s'y rattache (`orphanFor`, `rebind`). Seul `/villageboard remove` dissout.
  - Couples : `Kin.spouse` (+ `divorced`, `widowed`), règles dans `Genealogy.allowed` (conjoint exclusif, pas de parent/enfant
    ni de frères et sœurs). Le mixin note le villageois dont le cerveau tourne (`customServerAiStep`) et fait renvoyer `false` à
    `canBreed()` des partenaires interdits : la recherche de partenaire (`InteractWith` → `BREED_TARGET`) les ignore donc.
    Mariage d'office dans `VillageManager.onBreed`, par l'acte de mariage dans `village/Marriages` (`ContractKind.MARRIAGE`).
    `family.json` est versionné (`{"version": 2, "people": {...}}`) ; la v1 était la carte brute des entrées.
  - `mixin/VillagerMixin` : intercepte `setVillagerData`, ce qui donne les actualités de métier et bloque les changements de métier
    des villageois verrouillés ou liés (`isFrozen`, contourné par `bypassFreeze`), ainsi que `getBreedOffspring` (naissances).
  - `net/` : `OpenBoard` (vue complète du tableau), `Borders` (limites pour tous les clients), `BoardAction` (client → serveur).
- `src/client/java/fr/villageboard/client/` : `BoardScreen` (écran), `TerritoryMap` (carte, texture dynamique),
  `FamilyTree` (arbre d'un villageois), `VillageTree` (onglet Familles : arbre de tout le village, zoom et déplacement),
  `TradeCard` (fiche à côté de la fenêtre de commerce : le mixin sur `Villager.startTrading` fait envoyer `net/VillagerCard`,
  dessinée via `ScreenEvents.afterExtract` du `MerchantScreen`), `VillagerFace` (tête dessinée d'après les textures vanilla : face en (8, 8), chapeau en (40, 8), nez en (26, 2)), `BorderDisplay` (particules des frontières, messages d'entrée et de sortie), `Texts` (traductions, actualités),
  `VillageNeeds` (besoins du village, calculés côté client à partir de `BoardView`).
- Données sur l'entité (Fabric attachments) : `LOCKED`, `BOUND_SITE`, `BOUND_HOME`. `LEGACY_NAME` reste déclaré uniquement pour relire les mondes de la v0.2.
- La gazette stocke un **type + des arguments**, jamais du texte : le client compose la phrase dans sa langue (`Texts.news`).
  Un nom vide désigne un villageois sans nom (« un villageois sans nom »).
- `archive/paper-plugin/` : ancienne version en plugin Paper, abandonnée. Ne pas la modifier.

## Tests
Pas de tests automatisés. On vérifie sur le serveur de test via RCON :
- écrire un `villages.json` de départ (positions des bornes et du tableau en `BlockPos.asLong` : X sur 26 bits << 38,
  Z sur 26 bits << 12, Y sur 12 bits) ;
- poser les bornes avec `setblock` et invoquer des villageois ;
- lire les actualités dans le JSON après `docker compose stop minecraft`.

`/villageboard info <id>` résume ce que le serveur a relevé (lits, postes, cloches, golems, sans-abri, sans-emploi).
Pour simuler un verrou ou une liaison : `data merge entity ... {"fabric:attachments":{"villageboard:locked":1b}}`.
`data remove` ne fonctionne pas : mettre `0b` à la place.

L'écran, la carte et les actions faites par un joueur ne se testent qu'en jeu : le dire explicitement au joueur.

## Conventions
- Indentation par tabulations dans le Java (modèle Fabric) ; commentaires et Javadoc en français.
- Tout texte affiché passe par une clé dans `assets/villageboard/lang/fr_fr.json` **et** `en_us.json`.
- Augmenter `version` dans `gradle.properties` à chaque livraison au joueur.
