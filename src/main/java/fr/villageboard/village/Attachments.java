package fr.villageboard.village;

import com.mojang.serialization.Codec;
import fr.villageboard.VillageBoard;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.GlobalPos;

/** Données stockées directement sur l'entité villageois : elles la suivent même si le registre est perdu. */
public final class Attachments {

	public static final AttachmentType<Boolean> LOCKED =
			AttachmentRegistry.create(VillageBoard.id("locked"), builder -> builder.persistent(Codec.BOOL));
	/** Poste de travail attitré par un contrat. */
	public static final AttachmentType<GlobalPos> BOUND_SITE =
			AttachmentRegistry.create(VillageBoard.id("bound_site"), builder -> builder.persistent(GlobalPos.CODEC));
	/** Ancien prénom automatique (version 0.2) : toujours déclaré pour relire les mondes existants, puis effacé. */
	@Deprecated
	static final AttachmentType<String> LEGACY_NAME =
			AttachmentRegistry.create(VillageBoard.id("name"), builder -> builder.persistent(Codec.STRING));

	private Attachments() {
	}

	public static void init() {
		// charge la classe : les types sont enregistrés à l'initialisation des champs
	}
}
