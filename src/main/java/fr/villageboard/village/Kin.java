package fr.villageboard.village;

import java.util.ArrayList;
import java.util.List;

/**
 * Entrée de l'état civil (voir {@link Genealogy}) : un villageois qui a des parents, des enfants ou un conjoint connus.
 * Conservée après sa mort ou son départ, pour que l'arbre généalogique reste complet.
 */
public final class Kin {

	public enum Fate {
		ALIVE, DEAD, LEFT, ZOMBIFIED, WITCH, MISSING
	}

	/** Nom donné par un joueur, ou chaîne vide pour un villageois sans nom. */
	public String name = "";
	public String profession = Professions.NONE;
	/** Type (biome) du villageois, pour dessiner son visage : « minecraft:plains »… */
	public String type = Professions.DEFAULT_TYPE;
	public boolean baby;
	/** UUID des parents connus (0 à 2). */
	public List<String> parents = new ArrayList<>();
	/** Jour de naissance, ou -1 s'il est inconnu (villageois arrivé adulte). */
	public long born = -1;
	/** Identifiant du dernier village où il a été recensé (null si aucun). */
	public String village;
	public Fate fate = Fate.ALIVE;
	/** Jour de la mort ou du départ. */
	public long fateDay;
	/** Conjoint actuel (UUID), ou null. Un mort garde le sien ; le survivant passe en {@link #widowed}. */
	public String spouse;
	public long marriedDay;
	/** Anciens conjoints dont il a divorcé. */
	public List<String> divorced = new ArrayList<>();
	/** Conjoints morts. */
	public List<String> widowed = new ArrayList<>();

	/** Parents, conjoint et anciens conjoints : tous les liens directs. */
	List<String> links() {
		List<String> all = new ArrayList<>(parents);
		if (spouse != null) {
			all.add(spouse);
		}
		all.addAll(divorced);
		all.addAll(widowed);
		return all;
	}
}
