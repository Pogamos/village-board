package fr.villageboard.item;

import fr.villageboard.VillageBoard;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {

	/** Villageois inscrit sur un contrat de travail. */
	public static final DataComponentType<ContractTarget> CONTRACT_TARGET = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE, VillageBoard.id("contract_target"),
			DataComponentType.<ContractTarget>builder()
					.persistent(ContractTarget.CODEC)
					.networkSynchronized(ContractTarget.STREAM_CODEC)
					.build());

	/** Contrat de travail : lie un villageois à un poste de travail précis. */
	public static final Item WORK_CONTRACT = register("work_contract", ContractKind.WORK);
	/** Bail de logement : lie un villageois à un lit précis. */
	public static final Item HOUSING_LEASE = register("housing_lease", ContractKind.HOME);
	/** Acte de mariage : marie deux villageois. */
	public static final Item MARRIAGE_CERTIFICATE = register("marriage_certificate", ContractKind.MARRIAGE);

	private ModItems() {
	}

	private static Item register(String name, ContractKind kind) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, VillageBoard.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new ContractItem(new Item.Properties().setId(key).stacksTo(1), kind));
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(
				ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
				.register(output -> {
					output.accept(WORK_CONTRACT);
					output.accept(HOUSING_LEASE);
					output.accept(MARRIAGE_CERTIFICATE);
				});
	}
}
