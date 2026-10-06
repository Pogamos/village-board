package fr.villageboard.client;

import fr.villageboard.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class VillageBoardClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenBoard.TYPE, (payload, context) ->
				context.client().execute(() -> {
					Minecraft mc = context.client();
					if (mc.gui.screen() instanceof BoardScreen screen && screen.villageId().equals(payload.view().id())) {
						screen.update(payload.view());
					} else {
						mc.gui.setScreen(new BoardScreen(payload.view()));
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Borders.TYPE, (payload, context) ->
				context.client().execute(() -> BorderDisplay.setBorders(payload.borders())));
		ClientTickEvents.END_CLIENT_TICK.register(BorderDisplay::tick);
	}
}
