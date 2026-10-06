package fr.villageboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/villageboard.json — créé avec les valeurs par défaut au premier lancement. */
public final class Config {

	/** Rayon (blocs) du territoire provisoire d'un village qui a moins de 3 bornes. */
	public int defaultRadius = 48;
	/** Distance maximale entre une borne et le tableau de la mairie auquel elle se rattache. */
	public int maxBorneDistance = 256;
	public int scanIntervalSeconds = 10;
	public int maxNews = 200;
	public int glowSeconds = 30;
	/** Un villageois vu hors du territoire pendant ce délai est considéré comme parti du village. */
	public int leaveDelaySeconds = 60;
	/** « everyone » : tout le monde gère les villageois ; « founder » : le fondateur et les opérateurs. */
	public String managers = "everyone";
	/** À la fin de sa réplique, un villageois qui a un métier fait le bruit de son travail. */
	public boolean jobSound = true;

	private static Config instance = new Config();

	public static Config get() {
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("villageboard.json");
	}

	static void load() {
		Path file = file();
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (Files.exists(file)) {
				Config loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (loaded != null) {
					instance = loaded;
				}
			}
			Files.writeString(file, gson.toJson(instance), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}, valeurs par défaut utilisées", file, e);
		}
	}
}
