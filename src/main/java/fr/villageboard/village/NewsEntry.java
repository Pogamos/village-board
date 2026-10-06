package fr.villageboard.village;

import java.util.List;

/**
 * Une actualité de la gazette. Le texte n'est pas stocké : le client le compose dans sa langue
 * à partir du type et des arguments (noms, identifiants de métier, cause de décès…).
 */
public record NewsEntry(long time, long day, NewsType type, List<String> args) {
}
