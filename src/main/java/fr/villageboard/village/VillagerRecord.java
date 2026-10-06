package fr.villageboard.village;

/** Fiche d'un villageois au registre, conservée même quand il est dans une zone non chargée. */
public final class VillagerRecord {

	public String uuid;
	/** Nom donné par un joueur (nametag ou tableau) ; null = villageois sans nom. */
	public String customName;
	/** Identifiant complet, ex. « minecraft:librarian ». */
	public String profession = "minecraft:none";
	/** Type (biome) du villageois, ex. « minecraft:plains » : sert à dessiner son visage. */
	public String type = Professions.DEFAULT_TYPE;
	public int level = 1;
	public boolean baby;
	public boolean locked;
	/** Lit du villageois (BlockPos compacté), ou null s'il est sans abri. */
	public Long home;
	/** Poste attitré par un contrat de travail (BlockPos compacté), ou null. */
	public Long boundSite;
	/** Lit attitré par un bail de logement (BlockPos compacté), ou null. */
	public Long boundHome;
	public String dimension;
	public double x;
	public double y;
	public double z;
	public long firstSeen;
	public long firstSeenDay;
	public boolean born;
	public String parents;
	public long lastSeen;
	/** Depuis quand le villageois est vu hors du territoire (0 = il est dedans). */
	public long outsideSince;

	/** Nom à afficher, ou chaîne vide pour un villageois sans nom (le client affiche alors « sans nom »). */
	public String displayName() {
		return customName != null ? customName : "";
	}

	public boolean employed() {
		return !baby && !profession.equals(Professions.NONE) && !profession.equals(Professions.NITWIT);
	}
}
