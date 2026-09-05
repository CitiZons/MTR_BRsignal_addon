package org.mtrbr.web;

/**
 * Versioned contract for the Web dispatcher with token-bound in-game operator identity.
 */
public final class WebApiContract {
	private static final String JSON = """
			{"schema":4,"readOnly":false,"snapshots":[
			{"id":"topology","method":"GET","path":"/mtrbr/api/topology"},
			{"id":"state","method":"GET","path":"/mtrbr/api/state"},
			{"id":"session","method":"GET","path":"/mtrbr/api/session"},
			{"id":"lines","method":"GET","path":"/mtrbr/api/lines","staticTopology":true},
			{"id":"linePreviewNodes","method":"POST","path":"/mtrbr/api/lines/preview-nodes","staticTopology":false},
			{"id":"lineSaveNodes","method":"POST","path":"/mtrbr/api/lines/save-nodes","staticTopology":false}
			],"commandEndpoint":{"method":"POST","path":"/mtrbr/api/commands","available":true,"authentication":"X-MTRBR-Token and X-MTRBR-Device from /mtrbr web_token generate","request":{"action":"approve|revoke|override|confirm_quarantine_removal","vehicleId":"long"},"commands":[
			"dispatcher.approve","dispatcher.revoke","dispatcher.override","dispatcher.quarantine","dispatcher.release","dispatcher.deletePending","dispatcher.cancelDelete","dispatcher.confirmQuarantineRemoval"
			]}}
			""";

	private WebApiContract() {
	}

	public static String json() {
		return JSON;
	}

	public static String controlsDisabledJson() {
		return "{\"error\":\"CONTROL_DISABLED\",\"message\":\"Web controls are reserved but disabled in this read-only release.\"}";
	}
}
