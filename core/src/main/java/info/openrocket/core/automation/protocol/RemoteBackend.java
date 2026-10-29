package info.openrocket.core.automation.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.automation.AutomationException;

/** Connects to a running OpenRocket automation server. */
public class RemoteBackend implements Backend {
	private final Socket socket;
	private final BufferedReader in;
	private final Writer out;
	private final boolean gui;
	private int nextId = 1;

	public RemoteBackend(ConnectionInfo info) throws IOException {
		socket = new Socket(InetAddress.getLoopbackAddress(), info.port);
		in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
		JsonObject auth = new JsonObject();
		auth.addProperty("method", "auth");
		auth.addProperty("token", info.token);
		JsonElement r = request(auth);
		gui = r.isJsonObject() && r.getAsJsonObject().has("gui") && r.getAsJsonObject().get("gui").getAsBoolean();
	}

	/** Connect using the published connection file, or return null if no server is running. */
	public static RemoteBackend connect() throws IOException {
		ConnectionInfo info = ConnectionInfo.read();
		if (info == null) {
			return null;
		}
		try {
			return new RemoteBackend(info);
		} catch (IOException e) {
			return null;
		}
	}

	public boolean isGui() {
		return gui;
	}

	private synchronized JsonElement request(JsonObject req) throws IOException {
		req.addProperty("id", nextId++);
		out.write(req.toString());
		out.write('\n');
		out.flush();
		String line = in.readLine();
		if (line == null) {
			throw new IOException("OpenRocket closed the connection");
		}
		JsonObject resp = JsonParser.parseString(line).getAsJsonObject();
		if (resp.has("error")) {
			JsonObject e = resp.getAsJsonObject("error");
			throw new AutomationException(e.get("code").getAsString(), e.get("message").getAsString(), null);
		}
		return resp.get("result");
	}

	@Override
	public JsonArray listCommands() throws IOException {
		JsonObject req = new JsonObject();
		req.addProperty("method", "list");
		return request(req).getAsJsonArray();
	}

	@Override
	public JsonElement call(String command, JsonObject params) throws IOException {
		JsonObject req = new JsonObject();
		req.addProperty("method", "call");
		req.addProperty("command", command);
		req.add("params", params == null ? new JsonObject() : params);
		return request(req);
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}
}
