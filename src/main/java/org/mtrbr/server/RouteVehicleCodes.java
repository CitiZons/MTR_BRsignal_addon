package org.mtrbr.server;

import java.util.HashMap;
import java.util.Map;

/** Runtime vehicle-code allocation and reverse lookup. */
final class RouteVehicleCodes {
	private RouteVehicleCodes() {}

	private static final Map<Long, String> VEHICLE_CODES = new HashMap<>();

	private static final Map<String, Long> CODE_TO_VEHICLE = new HashMap<>();

	private static long nextVehicleCode;

	private static final String VEHICLE_CODE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".replace("O", "");

	private static final long VEHICLE_CODE_CAPACITY = (long) Math.pow(VEHICLE_CODE_ALPHABET.length(), 4);

	static synchronized String getVehicleCode(long vehicleId) {
		return VEHICLE_CODES.computeIfAbsent(vehicleId, RouteVehicleCodes::allocateVehicleCode);
	}

	static synchronized Long resolveVehicleCode(String code) {
		return CODE_TO_VEHICLE.get(code);
	}

	private static String allocateVehicleCode(long ignoredVehicleId) {
		final int base = VEHICLE_CODE_ALPHABET.length();
		for (long attempts = 0; attempts < VEHICLE_CODE_CAPACITY; attempts++) {
			long value = nextVehicleCode++;
			final StringBuilder code = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				code.insert(0, VEHICLE_CODE_ALPHABET.charAt((int) (value % base)));
				value /= base;
			}
			if (!CODE_TO_VEHICLE.containsKey(code.toString())) {
				CODE_TO_VEHICLE.put(code.toString(), ignoredVehicleId);
				MtrbrDebugLog.event("MTRBR-VEHICLE-CODE", "vehicle=" + ignoredVehicleId + " vehicleCode=" + code);
				return code.toString();
			}
		}
		final String detail = "[MTRBR-VEHICLE-CODE-EXHAUSTED] capacity=" + VEHICLE_CODE_CAPACITY;
		MtrbrDebugLog.event("MTRBR-VEHICLE-CODE-EXHAUSTED", detail);
		System.err.println(detail);
		throw new IllegalStateException(detail);
	}

	static synchronized void releaseVehicleCode(long vehicleId) {
		final String code = VEHICLE_CODES.remove(vehicleId);
		if (code != null) {
			CODE_TO_VEHICLE.remove(code, vehicleId);
		}
	}

	static synchronized void reset() {
		VEHICLE_CODES.clear();
		CODE_TO_VEHICLE.clear();
		nextVehicleCode = 0;
	}
}
