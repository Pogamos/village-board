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
  - `village/WorkAssignments` : postes attitrés (contrat de travail). `VillagerActions` : renommer, localiser, verrou, réinitialisation.
  - `village/Territory` : géométrie partagée client/serveur. Polygone des bornes trié par angle autour du barycentre ;
    cercle provisoire avec moins de 3 bornes.
  - `mixin/VillagerMixin` : intercepte `setVillagerData`, ce qui donne les actualités de métier et bloque les changements de métier
    des villageois verrouillés ou liés (`isFrozen`, contourné par `bypassFreeze`), ainsi que `getBreedOffspring` (naissances).
  - `net/` : `OpenBoard` (vue complète du tableau), `Borders` (limites pour tous les clients), `BoardAction` (client → serveur).
- `src/client/java/fr/villageboard/client/` : `BoardScreen` (écran), `TerritoryMap` (carte, texture dynamique),
  `BorderDisplay` (particules des frontières, messages d'entrée et de sortie), `Texts` (traductions, actualités).
- Données sur l'entité (Fabric attachments) : `LOCKED`, `BOUND_SITE`. `LEGACY_NAME` reste déclaré uniquement pour relire les mondes de la v0.2.
- La gazette stocke un **type + des arguments**, jamais du texte : le client compose la phrase dans sa langue (`Texts.news`).
  Un nom vide désigne un villageois sans nom (« un villageois sans nom »).
- `archive/paper-plugin/` : ancienne version en plugin Paper, abandonnée. Ne pas la modifier.

## Tests
Pas de tests automatisés. On vérifie sur le serveur de test via RCON :
- écrire un `villages.json` de départ (positions des bornes et du tableau en `BlockPos.asLong` : X sur 26 bits << 38,
  Z sur 26 bits << 12, Y sur 12 bits) ;
- poser les bornes avec `setblock` et invoquer des villageois ;
- lire les actualités dans le JSON après `docker compose stop minecraft`.

Pour simuler un verrou ou une liaison : `data merge entity ... {"fabric:attachments":{"villageboard:locked":1b}}`.
`data remove` ne fonctionne pas : mettre `0b` à la place.

L'écran, la carte et les actions faites par un joueur ne se testent qu'en jeu : le dire explicitement au joueur.

## Conventions
- Indentation par tabulations dans le Java (modèle Fabric) ; commentaires et Javadoc en français.
- Tout texte affiché passe par une clé dans `assets/villageboard/lang/fr_fr.json` **et** `en_us.json`.
- Augmenter `version` dans `gradle.properties` à chaque livraison au joueur.
