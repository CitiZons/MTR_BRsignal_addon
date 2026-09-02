package org.mtrbr.web;

import org.mtr.libraries.javax.servlet.http.HttpServlet;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MtrbrWebServlet extends HttpServlet {
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		final String path = request.getPathInfo() == null || request.getPathInfo().equals("/") ? "/index.html" : request.getPathInfo();
		 switch (path) {
			case "/api/topology" -> send(response, "application/json; charset=UTF-8", WebTopologySnapshot.topologyJson());
			case "/api/state" -> send(response, "application/json; charset=UTF-8", WebTopologySnapshot.stateJson());
			case "/api/lines" -> send(response, "application/json; charset=UTF-8", WebTopologySnapshot.linesJson());
			case "/api/session" -> {
				final WebSessionManager.SessionView session = WebTopologySnapshot.session(token(request), deviceId(request));
				send(response, "application/json; charset=UTF-8", "{\"canDispatch\":" + session.canDispatch() + ",\"invalidationReason\":\"" + session.invalidationReason() + "\"}");
			}
			case "/api/contract" -> send(response, "application/json; charset=UTF-8", WebApiContract.json());
			case "/index.html", "/app.css", "/app.js" -> sendResource(response, path);
			case "/Terminus-Regular.ttf" -> sendBinaryResource(response, path, "font/ttf");
			default -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if ("/api/lines/preview-nodes".equals(request.getPathInfo())) {
			final JsonObject body = JsonParser.parseReader(request.getReader()).getAsJsonObject();
			final JsonObject result = DepotPathEditorService.previewNodes(WebTopologySnapshot.server(), token(request), deviceId(request), body);
			send(response, result.get("ok").getAsBoolean() ? HttpServletResponse.SC_OK : HttpServletResponse.SC_CONFLICT, "application/json; charset=UTF-8", result.toString());
		} else if ("/api/lines/save-nodes".equals(request.getPathInfo())) {
			final JsonObject body = JsonParser.parseReader(request.getReader()).getAsJsonObject();
			final JsonObject result = DepotPathEditorService.saveNodes(WebTopologySnapshot.server(), token(request), deviceId(request), body);
			send(response, result.get("ok").getAsBoolean() ? HttpServletResponse.SC_OK : HttpServletResponse.SC_CONFLICT, "application/json; charset=UTF-8", result.toString());
		} else if ("/api/commands".equals(request.getPathInfo())) {
			final JsonObject body = JsonParser.parseReader(request.getReader()).getAsJsonObject();
			final String action = body.has("action") ? body.get("action").getAsString() : "";
			final boolean accepted;
			if ("name_signal".equals(action)) {
				accepted = body.has("signalId") && WebTopologySnapshot.renameSignal(token(request), deviceId(request), body.get("signalId").getAsString(), body.has("name") ? body.get("name").getAsString() : "");
			} else {
				final long vehicleId = body.has("vehicleId") ? body.get("vehicleId").getAsLong() : Long.MIN_VALUE;
				accepted = WebTopologySnapshot.dispatch(token(request), deviceId(request), action, vehicleId);
			}
			if (accepted) {
				send(response, "application/json; charset=UTF-8", "{\"ok\":true}");
			} else {
				send(response, HttpServletResponse.SC_FORBIDDEN, "application/json; charset=UTF-8", "{\"ok\":false}");
			}
		} else {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

	private static String token(HttpServletRequest request) {
		return request.getHeader("X-MTRBR-Token");
	}

	private static String deviceId(HttpServletRequest request) {
		return request.getHeader("X-MTRBR-Device");
	}

	private static void sendResource(HttpServletResponse response, String path) throws IOException {
		final String resourcePath = "assets/mtr_brsignal_addon/web" + path;
		try (InputStream stream = MtrbrWebServlet.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			send(response, path.endsWith(".css") ? "text/css; charset=UTF-8" : path.endsWith(".js") ? "application/javascript; charset=UTF-8" : "text/html; charset=UTF-8", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static void sendBinaryResource(HttpServletResponse response, String path, String contentType) throws IOException {
		final String resourcePath = "assets/mtr_brsignal_addon/web" + path;
		try (InputStream stream = MtrbrWebServlet.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(contentType);
			stream.transferTo(response.getOutputStream());
		}
	}

	private static void send(HttpServletResponse response, String contentType, String body) throws IOException {
		send(response, HttpServletResponse.SC_OK, contentType, body);
	}

	private static void send(HttpServletResponse response, int status, String contentType, String body) throws IOException {
		response.setStatus(status);
		response.setContentType(contentType);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(body);
	}
}
