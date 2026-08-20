package org.mtrbr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import org.mtrbr.render.SignalOverlayRenderer;
import org.mtrbr.render.SignalFarRenderer;
import org.mtrbr.render.VehicleHudRenderer;
import org.mtrbr.screen.IndicatorInfoScreen;
import org.mtrbr.screen.RouteTextInputScreen;
import org.mtrbr.screen.SignalDebugScreen;
import org.mtrbr.screen.DispatcherScreen;

public final class ClientHooks {

	private ClientHooks() {
	}

	public static void init() {
		MinecraftForge.EVENT_BUS.addListener(SignalOverlayRenderer::onRenderLevel);
		MinecraftForge.EVENT_BUS.addListener(SignalFarRenderer::onRenderLevel);
		MinecraftForge.EVENT_BUS.addListener(VehicleHudRenderer::onRenderGui);
		MinecraftForge.EVENT_BUS.addListener(CenterToast::onRenderGui);
		MinecraftForge.EVENT_BUS.addListener(TooltipRenderer::onTooltipPre);
	}

	public static void openSignalDebugScreen(BlockPos signalPos) {
		Minecraft.getInstance().setScreen(new SignalDebugScreen(signalPos));
	}

	public static void openIndicatorScreen(BlockPos indicatorPos, boolean isLed) {
		Minecraft.getInstance().setScreen(new IndicatorInfoScreen(indicatorPos, isLed));
	}

	public static void openRouteTextInputScreen(BlockPos signalPos, BlockPos nodePos) {
		Minecraft.getInstance().setScreen(new RouteTextInputScreen(signalPos, nodePos));
	}

	public static void openDispatcherScreen() {
		Minecraft.getInstance().setScreen(new DispatcherScreen());
	}
}
