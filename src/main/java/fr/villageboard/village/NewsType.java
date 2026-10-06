package fr.villageboard.village;

/**
 * Types d'actualités, avec leur symbole et leur couleur dans la gazette.
 * Un nom vide dans les arguments désigne un villageois sans nom.
 */
public enum NewsType {
	/** nom du village, nombre d'habitants */
	FOUNDED("✎", 0xFF5A4A3A),
	/** nom */
	ARRIVAL("→", 0xFF1F4E8C),
	/** nom, village d'origine */
	MOVED_IN("→", 0xFF1F4E8C),
	/** nom, village de destination */
	MOVED_OUT("←", 0xFF6A2C7A),
	/** nom, métier */
	LEFT("←", 0xFF6A2C7A),
	/** nom (vide pour un bébé), parents nommés (vide si aucun) */
	BIRTH("✦", 0xFF2E7D32),
	/** nom, métier, cause */
	DEATH("✝", 0xFF8B1A1A),
	/** nom, métier */
	JOB("⚒", 0xFF00695C),
	/** nom, ancien métier */
	JOB_LOST("✖", 0xFFB26A00),
	/** nom, métier, coordonnées du poste */
	ASSIGNED("⚒", 0xFF00695C),
	/** nom, métier */
	UNBOUND("✖", 0xFFB26A00),
	/** nom, coordonnées du lit */
	HOME_ASSIGNED("⌂", 0xFF1F4E8C),
	/** nom */
	HOME_UNBOUND("✖", 0xFFB26A00),
	/** nom */
	ZOMBIFIED("✝", 0xFF4E6B2E),
	/** nom */
	WITCH("✝", 0xFF6A2C7A),
	/** nom */
	CURED("✚", 0xFF2E7D32),
	/** ancien nom, nouveau nom (vide si retiré) */
	RENAMED("✎", 0xFF5A4A3A),
	/** nombre total de lits */
	HOUSING_FULL("⌂", 0xFFB26A00),
	/** nombre de lits libres */
	HOUSING_FREE("⌂", 0xFF2E7D32),
	/** nombre de bornes */
	TERRITORY("⚑", 0xFF5A4A3A),
	/** ancien nom, nouveau nom */
	VILLAGE_RENAMED("✎", 0xFF5A4A3A),
	/** joueur qui l'a retiré (vide si inconnu) */
	BOARD_REMOVED("✖", 0xFF8B1A1A),
	/** coordonnées du nouveau tableau */
	BOARD_MOVED("⚑", 0xFF5A4A3A),
	/** époux, épouse, « ceremony » (acte de mariage) ou « child » (mariage d'office au premier enfant) */
	MARRIED("♥", 0xFFC2185B),
	/** les deux ex-époux */
	DIVORCED("♡", 0xFF7A6548);

	public final String symbol;
	public final int color;

	NewsType(String symbol, int color) {
		this.symbol = symbol;
		this.color = color;
	}

	public String translationKey() {
		return "villageboard.news." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
