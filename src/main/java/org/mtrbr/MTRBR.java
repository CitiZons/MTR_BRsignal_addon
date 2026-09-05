package org.mtrbr;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.client.ClientHooks;
import org.mtrbr.block.LedIndicatorBlock;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.block.ColorLightIndicatorBlock;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.block.ColorLightRoute;
import org.mtrbr.block.TripleIndicatorShapes;
import org.mtrbr.block.DispatcherConsoleBlock;
import org.mtrbr.block.SignalBracketBlock;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.event.LeftClickHandler;
import org.mtrbr.item.DebugToolItem;
import org.mtrbr.item.RouteToolItem;
import org.mtrbr.item.DispatcherToolItem;
import org.mtrbr.network.Network;
import org.mtrbr.network.SyncRouteBindingsPacket;
import org.mtrbr.network.SyncSignalAspectsPacket;
import org.mtrbr.network.SyncDispatcherDataPacket;
import org.mtrbr.web.WebTopologySnapshot;

import org.mtrbr.command.MTRBRCommands;

@Mod(MTRBR.MOD_ID)
public final class MTRBR {

	public static final String MOD_ID = "mtr_brsignal_addon";
	public static final String MOD_NAME = "MTR_BRsignal_addon";

	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
	public static final RegistryObject<Item> DEBUG_TOOL = ITEMS.register("signal_debug_tool", () -> new DebugToolItem(new Item.Properties()));
	public static final RegistryObject<Item> ROUTE_TOOL = ITEMS.register("route_tool", () -> new RouteToolItem(new Item.Properties()));
	public static final RegistryObject<Item> DISPATCHER_TOOL = ITEMS.register("dispatcher_tool", () -> new DispatcherToolItem(new Item.Properties()));
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);
	public static final RegistryObject<LedIndicatorBlock> LED_INDICATOR_BLOCK = BLOCKS.register("led_indicator", () -> new LedIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> LED_INDICATOR_ITEM = ITEMS.register("led_indicator", () -> new BlockItem(LED_INDICATOR_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<LedIndicatorBlockEntity>> LED_INDICATOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("led_indicator", () -> BlockEntityType.Builder.of(LedIndicatorBlockEntity::new, LED_INDICATOR_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_BLOCK = BLOCKS.register("indicator_1", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_ITEM = ITEMS.register("indicator_1", () -> new BlockItem(COLOR_LIGHT_INDICATOR_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_1", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_1_2_BLOCK = BLOCKS.register("indicator_1-2", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_1_2_ITEM = ITEMS.register("indicator_1-2", () -> new BlockItem(COLOR_LIGHT_INDICATOR_1_2_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_1_2_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_1-2", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_1_2_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_1_4_BLOCK = BLOCKS.register("indicator_1-4", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_1_4_ITEM = ITEMS.register("indicator_1-4", () -> new BlockItem(COLOR_LIGHT_INDICATOR_1_4_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_1_4_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_1-4", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_1_4_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_4_BLOCK = BLOCKS.register("indicator_4", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_4_ITEM = ITEMS.register("indicator_4", () -> new BlockItem(COLOR_LIGHT_INDICATOR_4_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_4_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_4", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_4_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_4_5_BLOCK = BLOCKS.register("indicator_4-5", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_4_5_ITEM = ITEMS.register("indicator_4-5", () -> new BlockItem(COLOR_LIGHT_INDICATOR_4_5_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_4_5_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_4-5", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_4_5_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_1_2_4_BLOCK = BLOCKS.register("indicator_1-2-4", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion(), TripleIndicatorShapes.ROUTES_1_2_4));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_1_2_4_ITEM = ITEMS.register("indicator_1-2-4", () -> new BlockItem(COLOR_LIGHT_INDICATOR_1_2_4_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_1_2_4_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_1-2-4", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_1_2_4_BLOCK.get()).build(null));
	public static final RegistryObject<ColorLightIndicatorBlock> COLOR_LIGHT_INDICATOR_1_4_5_BLOCK = BLOCKS.register("indicator_1-4-5", () -> new ColorLightIndicatorBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion(), TripleIndicatorShapes.ROUTES_1_4_5));
	public static final RegistryObject<BlockItem> COLOR_LIGHT_INDICATOR_1_4_5_ITEM = ITEMS.register("indicator_1-4-5", () -> new BlockItem(COLOR_LIGHT_INDICATOR_1_4_5_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<BlockEntityType<ColorLightIndicatorBlockEntity>> COLOR_LIGHT_INDICATOR_1_4_5_BLOCK_ENTITY = BLOCK_ENTITIES.register("indicator_1-4-5", () -> BlockEntityType.Builder.of(ColorLightIndicatorBlockEntity::new, COLOR_LIGHT_INDICATOR_1_4_5_BLOCK.get()).build(null));
	public static final RegistryObject<SignalBracketBlock> SIGNAL_BRACKET_BLOCK = BLOCKS.register("signal_bracket", () -> new SignalBracketBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
	public static final RegistryObject<BlockItem> SIGNAL_BRACKET_ITEM = ITEMS.register("signal_bracket", () -> new BlockItem(SIGNAL_BRACKET_BLOCK.get(), new Item.Properties()));
	public static final RegistryObject<DispatcherConsoleBlock> DISPATCHER_CONSOLE_BLOCK = BLOCKS.register("dispatcher_console", () -> new DispatcherConsoleBlock(BlockBehaviour.Properties.of().strength(2.0F)));
	public static final RegistryObject<BlockItem> DISPATCHER_CONSOLE_ITEM = ITEMS.register("dispatcher_console", () -> new BlockItem(DISPATCHER_CONSOLE_BLOCK.get(), new Item.Properties()));

	public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
	public static final RegistryObject<CreativeModeTab> CREATIVE_TAB = TABS.register("main", () -> CreativeModeTab.builder()
			.title(Component.literal(MOD_NAME))
			.icon(() -> new ItemStack(DEBUG_TOOL.get()))
			.displayItems((parameters, output) -> {
				output.accept(new ItemStack(DEBUG_TOOL.get()));
				output.accept(new ItemStack(ROUTE_TOOL.get()));
				output.accept(new ItemStack(DISPATCHER_TOOL.get()));
				output.accept(new ItemStack(DISPATCHER_CONSOLE_ITEM.get()));
				output.accept(new ItemStack(LED_INDICATOR_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_1_2_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_1_4_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_4_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_4_5_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_1_2_4_ITEM.get()));
				output.accept(new ItemStack(COLOR_LIGHT_INDICATOR_1_4_5_ITEM.get()));
				output.accept(new ItemStack(SIGNAL_BRACKET_ITEM.get()));
			})
			.build());

	public MTRBR() {
		final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		ITEMS.register(modEventBus);
		BLOCKS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		TABS.register(modEventBus);
		ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, org.mtrbr.config.MtrbrServerConfig.SPEC);

		Network.init();
		MinecraftForge.EVENT_BUS.addListener(MTRBR::onPlayerLoggedIn);
		MinecraftForge.EVENT_BUS.addListener(MTRBR::onPlayerLoggedOut);
		MinecraftForge.EVENT_BUS.addListener(MTRBR::onServerTick);
		MinecraftForge.EVENT_BUS.addListener(MTRBR::onServerStopping);
		MinecraftForge.EVENT_BUS.addListener(MTRBRCommands::register);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.server.ServerSignalRegistry::onChunkLoad);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.server.ServerSignalRegistry::onChunkUnload);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.server.ServerSignalRegistry::onBlockPlace);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.server.ServerSignalRegistry::onBlockBreak);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.web.PlatformGeometryCache::onChunkLoad);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.web.PlatformGeometryCache::onBlockPlace);
		MinecraftForge.EVENT_BUS.addListener(org.mtrbr.web.PlatformGeometryCache::onBlockBreak);
		MinecraftForge.EVENT_BUS.addListener(LeftClickHandler::onLeftClickBlock);
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::init);
	}

	private static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.END && event.getServer() != null) {
				if (event.getServer().getTickCount() % 20 == 0) {
				event.getServer().getAllLevels().forEach(level -> {
					org.mtrbr.server.ServerAspectManager.update(level);
					updateColorLightIndicatorRoutes(level);
					Network.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), new SyncSignalAspectsPacket(org.mtrbr.server.ServerAspectManager.snapshot(level)));
					final Simulator simulator = org.mtrbr.server.SectionStateManager.getSimulator(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
					if (simulator != null) {
						org.mtrbr.server.CapacityLeaseManager.persistCompiledZones(level, simulator);
						Network.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), new SyncDispatcherDataPacket(org.mtrbr.server.RouteRequestManager.getRequestSnapshots(simulator)));
					}
				});
				WebTopologySnapshot.publish(event.getServer());
			}
		}
	}

	private static void updateColorLightIndicatorRoutes(ServerLevel level) {
		final RouteBindingsSavedData bindings = RouteBindingsSavedData.get(level);
		final var displays = org.mtrbr.server.ServerAspectManager.snapshot(level);
		for (final var binding : bindings.getIndicatorBindings().entrySet()) {
			final var indicatorPos = binding.getKey();
			final var state = level.getBlockState(indicatorPos);
			if (!(state.getBlock() instanceof ColorLightIndicatorBlock)) continue;
			String content = "";
			for (final var display : displays.entrySet()) {
				if (display.getKey().signalPos().equals(binding.getValue()) && !display.getValue().authorizationId().isEmpty()) {
					content = display.getValue().routeContent();
					break;
				}
			}
			final ColorLightRoute route = ColorLightRoute.fromRouteContent(content);
			if (state.getValue(ColorLightIndicatorBlock.ROUTE) != route) {
				level.setBlock(indicatorPos, state.setValue(ColorLightIndicatorBlock.ROUTE, route), Block.UPDATE_CLIENTS);
			}
		}
	}

	private static void onServerStopping(ServerStoppingEvent event) {
		WebTopologySnapshot.reset();
		org.mtrbr.web.PlatformGeometryCache.resetAll();
		org.mtrbr.web.WebSessionManager.reset();
		org.mtrbr.server.SectionStateManager.resetAll();
		org.mtrbr.server.CapacityLeaseManager.resetAll();
		org.mtrbr.server.RouteRequestManager.resetAll();
		org.mtrbr.server.ServerAspectManager.resetAll();
		org.mtrbr.server.ServerSignalRegistry.resetAll();
	}

	private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel serverLevel) {
			final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
			Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new SyncSignalAspectsPacket(org.mtrbr.server.ServerAspectManager.snapshot(serverLevel)));
			final org.mtr.core.simulation.Simulator simulator = org.mtrbr.server.SectionStateManager.getSimulator(serverLevel.dimension().location().getNamespace() + "/" + serverLevel.dimension().location().getPath());
			if (simulator != null) {
				Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new SyncDispatcherDataPacket(org.mtrbr.server.RouteRequestManager.getRequestSnapshots(simulator)));
			}
		}
	}

	private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer serverPlayer) {
			org.mtrbr.web.WebSessionManager.invalidateForOfflinePlayer(serverPlayer.getUUID());
		}
	}

}
