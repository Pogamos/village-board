import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Génère les textures 16×16 du mod.
 * Lancement : java tools/GenTextures.java src/main/resources/assets/villageboard/textures/block
 */
public class GenTextures {

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        ImageIO.write(boardFront(), "png", new File(dir, "town_board_front.png"));
        ImageIO.write(stoneSide(), "png", new File(dir, "boundary_stone_side.png"));
        ImageIO.write(stoneTop(), "png", new File(dir, "boundary_stone_top.png"));
        File items = new File(dir.getParentFile(), "item");
        items.mkdirs();
        ImageIO.write(contract(), "png", new File(items, "work_contract.png"));
    }

    /** Contrat de travail : parchemin roulé en haut, lignes d'écriture, sceau de cire rouge et ruban. */
    static BufferedImage contract() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 15; y++) {
            for (int x = 3; x < 13; x++) {
                set(img, x, y, (x + y) % 5 == 0 ? 0xE6D6AA : 0xF1E4BF);
            }
            set(img, 2, y, 0xB89B62);
            set(img, 13, y, 0xB89B62);
        }
        for (int x = 2; x < 14; x++) {
            set(img, x, 1, 0xC9AE7C);
            set(img, x, 2, 0xA8895A);
            set(img, x, 15, 0xB89B62);
        }
        set(img, 1, 1, 0xA8895A);
        set(img, 14, 1, 0xA8895A);
        int[][] lines = {{4, 11, 5}, {4, 10, 7}, {4, 11, 9}, {4, 7, 11}};
        for (int[] l : lines) {
            for (int x = l[0]; x <= l[1]; x++) {
                set(img, x, l[2], 0x5A4632);
            }
        }
        int[][] seal = {{10, 11}, {11, 11}, {9, 12}, {10, 12}, {11, 12}, {12, 12}, {9, 13}, {10, 13}, {11, 13}, {12, 13}, {10, 14}, {11, 14}};
        for (int[] s : seal) {
            set(img, s[0], s[1], 0xA81E1E);
        }
        set(img, 10, 12, 0xD84040);
        set(img, 9, 14, 0x7A1414);
        set(img, 12, 14, 0x7A1414);
        return img;
    }

    /** Liège encadré de bois avec trois parchemins épinglés (lignes 0..11 ; le modèle n'utilise que celles-ci). */
    static BufferedImage boardFront() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(7);
        int[] cork = {0xB5844F, 0xA8774A, 0xC08F5A, 0xAD7D4B};
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                set(img, x, y, cork[r.nextInt(cork.length)]);
            }
        }
        for (int i = 0; i < 16; i++) {
            set(img, i, 0, 0x4A2F1A);
            set(img, i, 11, 0x4A2F1A);
            for (int y = 12; y < 16; y++) {
                set(img, i, y, 0x5C3A21);
            }
        }
        for (int y = 0; y < 12; y++) {
            set(img, 0, y, 0x4A2F1A);
            set(img, 15, y, 0x4A2F1A);
        }
        note(img, 2, 2, 5, 6, 0xEFE3C2, 0x9C8C6A);
        note(img, 9, 2, 5, 4, 0xF7F1E1, 0x8A8A8A);
        note(img, 8, 7, 6, 3, 0xE8D9A8, 0x9C8C6A);
        return img;
    }

    static void note(BufferedImage img, int x0, int y0, int w, int h, int paper, int ink) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                set(img, x, y, paper);
            }
        }
        for (int y = y0 + 2; y < y0 + h; y += 2) {
            for (int x = x0 + 1; x < x0 + w - 1; x++) {
                set(img, x, y, ink);
            }
        }
        set(img, x0 + w / 2, y0, 0xC0392B);
    }

    /** Pierre taillée, rainure gravée et bande ocre peinte (comme un balisage de sentier). */
    static BufferedImage stoneSide() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(11);
        int[] stone = {0x7F7F7F, 0x8A8A8A, 0x747474, 0x838383};
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                set(img, x, y, stone[r.nextInt(stone.length)]);
            }
        }
        for (int x = 0; x < 16; x++) {
            set(img, x, 0, 0x5E5E5E);
            set(img, x, 15, 0x5E5E5E);
            set(img, x, 3, 0xD4A017);
            set(img, x, 4, 0xB8860B);
        }
        for (int y = 6; y < 14; y++) {
            set(img, 7, y, 0x5A5A5A);
            set(img, 8, y, 0x666666);
        }
        set(img, 6, 7, 0x5A5A5A);
        set(img, 9, 7, 0x5A5A5A);
        return img;
    }

    static BufferedImage stoneTop() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(3);
        int[] stone = {0x9A9A9A, 0x929292, 0xA0A0A0};
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean edge = x == 0 || y == 0 || x == 15 || y == 15;
                set(img, x, y, edge ? 0x7A7A7A : stone[r.nextInt(stone.length)]);
            }
        }
        for (int i = 4; i < 12; i++) {
            set(img, i, 7, 0x6E6E6E);
            set(img, 7, i, 0x6E6E6E);
        }
        set(img, 7, 7, 0xD4A017);
        return img;
    }

    static void set(BufferedImage img, int x, int y, int rgb) {
        img.setRGB(x, y, 0xFF000000 | rgb);
    }
}
