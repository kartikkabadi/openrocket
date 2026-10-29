package info.openrocket.core.automation.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves a {@link Backend} on a localhost TCP port so that the CLI and MCP server can drive a
 * running OpenRocket (including the GUI). Messages are one JSON object per line:
 * <pre>
 * → {"id":1,"method":"auth","token":"..."}            (required first)
 * → {"id":2,"method":"call","command":"component.tree","params":{}}
 * → {"id":3,"method":"list"}
 * ← {"id":2,"result":...}  or  {"id":2,"error":{"code":"...","message":"..."}}
 * </pre>
 */
public class SocketServer {
	private static final Logger log = LoggerFactory.getLogger(SocketServer.class);

	private final Backend backend;
	private final boolean gui;
	private ServerSocket server;
	private String token;

	public SocketServer(Backend backend, boolean gui) {
		this.backend = backend;
		this.gui = gui;
	}

	/** Start listening on the given port (0 = any free port) and publish the connection file. */
	public synchronized int start(int port) throws IOException {
		server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		byte[] secret = new byte[24];
		new SecureRandom().nextBytes(secret);
		token = HexFormat.of().formatHex(secret);
		new ConnectionInfo(server.getLocalPort(), token, ProcessHandle.current().pid(), gui).write();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> ConnectionInfo.delete(ProcessHandle.current().pid())));
		Thread t = new Thread(this::acceptLoop, "OpenRocket automation server");
		t.setDaemon(true);
		t.start();
		log.info("Automation server listening on 127.0.0.1:{}", server.getLocalPort());
		return server.getLocalPort();
	}

	public int getPort() {
		return server == null ? -1 : server.getLocalPort();
	}

	public void stop() {
		try {
			if (server != null) {
				server.close();
			}
		} catch (IOException ignored) {
		}
		ConnectionInfo.delete(ProcessHandle.current().pid());
	}

	private void acceptLoop() {
		while (!server.isClosed()) {
			try {
				Socket s = server.accept();
				Thread t = new Thread(() -> handle(s), "OpenRocket automation client");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				if (!server.isClosed()) {
					log.warn("Automation server accept failed", e);
				}
			}
		}
	}

	private void handle(Socket socket) {
		try (socket;
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
			boolean authed = false;
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonObject resp = new JsonObject();
				try {
					JsonObject req = JsonParser.parseString(line).getAsJsonObject();
					if (req.has("id")) {
						resp.add("id", req.get("id"));
					}
					String method = req.has("method") ? req.get("method").getAsString() : "";
					if (!authed) {
						if (method.equals("auth") && req.has("token") && constantTimeEquals(req.get("token").getAsString(), token)) {
							authed = true;
							JsonObject r = new JsonObject();
							r.addProperty("gui", gui);
							resp.add("result", r);
						} else {
							resp.add("error", error("unauthorized", "Send {\"method\":\"auth\",\"token\":...} first"));
							write(out, resp);
							return;
						}
					} else if (method.equals("list")) {
						resp.add("result", backend.listCommands());
					} else if (method.equals("call")) {
						JsonObject params = req.has("params") && req.get("params").isJsonObject()
								? req.getAsJsonObject("params") : new JsonObject();
						JsonElement r = backend.call(req.get("command").getAsString(), params);
						resp.add("result", r);
					} else {
						resp.add("error", error("unknown_method", "Unknown method " + method));
					}
				} catch (Throwable e) {
					resp.add("error", Errors.toJson(e));
				}
				write(out, resp);
			}
		} catch (IOException e) {
			log.debug("Automation client disconnected", e);
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static JsonObject error(String code, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		return e;
	}

	private static void write(Writer out, JsonObject o) throws IOException {
		out.write(o.toString());
		out.write('\n');
		out.flush();
	}
}
