package org.mtrbr.mixin;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.RouteRequestManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Simulator.class)
public abstract class SimulatorMixin {

	@Inject(method = "tick", at = @At("HEAD"), remap = false)
	private void mtrbr$beginTick(CallbackInfo callbackInfo) {
		SectionStateManager.beginSimulation((Simulator) (Object) this);
	}

	@Inject(method = "tick", at = @At("TAIL"), remap = false)
	private void mtrbr$endTick(CallbackInfo callbackInfo) {
		RouteRequestManager.finishSimulationTick((Simulator) (Object) this);
		SectionStateManager.endSimulation((Simulator) (Object) this);
	}
}
