package org.mtrbr.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.mod.block.BlockSignalBase;

/** Central registry for signal block adapters; style modules register here. */
public final class SignalDeviceCatalog {
	private static final List<SignalDeviceAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

	static {
		register(new SignalDeviceAdapter() {
			@Override public boolean supports(BlockState state) { return state.getBlock() instanceof BlockSignalBase; }
			@Override public SignalDeviceKind kind() { return SignalDeviceKind.MAIN_SIGNAL; }
		});
	}

	private SignalDeviceCatalog() { }

	public static void register(SignalDeviceAdapter adapter) {
		if (adapter != null && !ADAPTERS.contains(adapter)) ADAPTERS.add(adapter);
	}

	public static boolean isMainSignal(BlockState state) {
		return ADAPTERS.stream().anyMatch(adapter -> adapter.kind() == SignalDeviceAdapter.SignalDeviceKind.MAIN_SIGNAL && adapter.supports(state));
	}
}
