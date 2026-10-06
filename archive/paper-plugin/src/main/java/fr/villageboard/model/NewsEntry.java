package fr.villageboard.model;

/**
 * @param time  horodatage réel (epoch ms)
 * @param day   jour de jeu du monde du village au moment de l'événement
 */
public record NewsEntry(long time, long day, NewsType type, String text) {
}
