package org.mtrbr.client;

import java.util.List;

/** 服务端下发的调度请求快照，仅供客户端显示。 */
public final class ClientDispatcherData {
	private static List<Entry> ENTRIES = List.of();

	private ClientDispatcherData() {
	}

	public static void replace(List<Entry> entries) {
		ENTRIES = List.copyOf(entries);
	}

	public static List<Entry> getEntries() {
		return ENTRIES;
	}

	public record Entry(long vehicleId, String vehicleCode, String state, boolean oneShotOverride, double head, double control, double requestEnd, double authorizationEnd, boolean authorized, double speedKmh, String routeName, String destination, String nextStation, int occupiedBlocks, int authorizedBlocks, int lockedBlocks) {
	}
}
