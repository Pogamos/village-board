package fr.villageboard;

import fr.villageboard.block.ModBlocks;
import fr.villageboard.item.ModItems;
import fr.villageboard.net.Payloads;
import fr.villageboard.village.Attachments;
import fr.villageboard.village.ServerEvents;
import fr.villageboard.village.VillageCommand;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageBoard implements ModInitializer {

	public static final String MOD_ID = "villageboard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Config.load();
		ModBlocks.init();
		ModItems.init();
		Attachments.init();
		Payloads.init();
		ServerEvents.init();
		VillageCommand.init();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
