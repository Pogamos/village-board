package fr.villageboard.block;

import fr.villageboard.VillageBoard;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.function.Function;

public final class ModBlocks {

	/** Le tableau de la mairie : le poser fonde un village. Quasi indestructible par explosion, inamovible au piston. */
	public static final Block TOWN_BOARD = register("town_board", TownBoardBlock::new, BlockBehaviour.Properties.of()
			.mapColor(MapColor.WOOD)
			.strength(2.5f, 3_600_000f)
			.sound(SoundType.WOOD)
			.noOcclusion()
			.pushReaction(PushReaction.BLOCK));

	/** Borne : délimite le territoire du village le plus proche. */
	public static final Block BOUNDARY_STONE = register("boundary_stone", BoundaryStoneBlock::new, BlockBehaviour.Properties.of()
			.mapColor(MapColor.STONE)
			.strength(2.0f, 1200f)
			.sound(SoundType.STONE)
			.requiresCorrectToolForDrops()
			.noOcclusion()
			.pushReaction(PushReaction.BLOCK));

	private ModBlocks() {
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, VillageBoard.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, VillageBoard.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(
				ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks")))
				.register(output -> {
					output.accept(TOWN_BOARD);
					output.accept(BOUNDARY_STONE);
				});
	}
}
