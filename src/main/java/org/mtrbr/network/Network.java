package org.mtrbr.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.mtrbr.MTRBR;

public final class Network {

	private static final String PROTOCOL_VERSION = "4";

	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "main"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals
	);

	private static int nextId = 0;

	private Network() {
	}

	public static void init() {
		CHANNEL.registerMessage(nextId++, SetRouteBindingPacket.class, SetRouteBindingPacket::encode, SetRouteBindingPacket::decode, SetRouteBindingPacket::handle);
		CHANNEL.registerMessage(nextId++, RemoveRouteBindingPacket.class, RemoveRouteBindingPacket::encode, RemoveRouteBindingPacket::decode, RemoveRouteBindingPacket::handle);
		CHANNEL.registerMessage(nextId++, SetNodeBindingPacket.class, SetNodeBindingPacket::encode, SetNodeBindingPacket::decode, SetNodeBindingPacket::handle);
		CHANNEL.registerMessage(nextId++, ToggleNodeBindingDirectionPacket.class, ToggleNodeBindingDirectionPacket::encode, ToggleNodeBindingDirectionPacket::decode, ToggleNodeBindingDirectionPacket::handle);
		CHANNEL.registerMessage(nextId++, BindIndicatorPacket.class, BindIndicatorPacket::encode, BindIndicatorPacket::decode, BindIndicatorPacket::handle);
		CHANNEL.registerMessage(nextId++, UnbindIndicatorPacket.class, UnbindIndicatorPacket::encode, UnbindIndicatorPacket::decode, UnbindIndicatorPacket::handle);
		CHANNEL.registerMessage(nextId++, SetSignalNamePacket.class, SetSignalNamePacket::encode, SetSignalNamePacket::decode, SetSignalNamePacket::handle);
		CHANNEL.registerMessage(nextId++, SyncRouteBindingsPacket.class, SyncRouteBindingsPacket::encode, SyncRouteBindingsPacket::decode, SyncRouteBindingsPacket::handle);
		CHANNEL.registerMessage(nextId++, SyncSignalAspectsPacket.class, SyncSignalAspectsPacket::encode, SyncSignalAspectsPacket::decode, SyncSignalAspectsPacket::handle);
	}
}
