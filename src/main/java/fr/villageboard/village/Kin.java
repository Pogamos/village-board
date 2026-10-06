package fr.villageboard.village;

import java.util.ArrayList;
import java.util.List;

/**
 * Entrée de l'état civil (voir {@link Genealogy}) : un villageois qui a des parents ou des enfants connus.
 * Conservée après sa mort ou son départ, pour que l'arbre généalogique reste complet.
 */
public final class Kin {

	public enum Fate {
		ALIVE, DEAD, LEFT, ZOMBIFIED, WITCH, MISSING
	}

	/** Nom donné par un joueur, ou chaîne vide pour un villageois sans nom. */
	public String name = "";
	public String profession = Professions.NONE;
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
}
