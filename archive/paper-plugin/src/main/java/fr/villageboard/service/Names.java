package fr.villageboard.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Prénoms donnés automatiquement aux villageois sans nom. */
final class Names {

    private static final List<String> FIRST_NAMES = List.of(
            "Adèle", "Albin", "Alice", "Ambroise", "Anatole", "Anselme", "Apolline", "Armand", "Aubin",
            "Augustin", "Aurore", "Bastien", "Bérénice", "Blanche", "Capucine", "Céleste", "Clément",
            "Clotilde", "Colette", "Côme", "Constance", "Désiré", "Edmond", "Éloïse", "Émile", "Ernest",
            "Eugénie", "Fernand", "Firmin", "Flore", "Gaspard", "Gustave", "Héloïse", "Honorine", "Hortense",
            "Hugo", "Irène", "Jacinthe", "Joséphine", "Jules", "Juliette", "Léon", "Léonie", "Lucien",
            "Lucienne", "Madeleine", "Marcel", "Margot", "Marius", "Mathurin", "Maurice", "Mélanie", "Noémie",
            "Octave", "Odette", "Olympe", "Pacôme", "Paulin", "Philomène", "Prosper", "Rosalie", "Roseline",
            "Sidonie", "Simone", "Solange", "Suzanne", "Théodore", "Théophile", "Ursule", "Valentin",
            "Victor", "Violette", "Yvonne", "Zélie");

    private static final String[] SUFFIXES = {"II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private Names() {
    }

    /** Un prénom pas encore utilisé dans le village (« Jules II » une fois tous les prénoms pris). */
    static String pick(Set<String> used) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            String name = FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
            if (!used.contains(name)) {
                return name;
            }
        }
        for (String suffix : SUFFIXES) {
            String name = FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size())) + " " + suffix;
            if (!used.contains(name)) {
                return name;
            }
        }
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size())) + " " + (used.size() + 1);
    }
}
