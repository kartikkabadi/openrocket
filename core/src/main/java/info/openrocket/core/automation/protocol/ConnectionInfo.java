package info.openrocket.core.automation.protocol;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.arch.SystemInfo;

/**
 * The file through which clients find a running automation server: its port and the secret token
 * required to use it. The file is readable by the current user only.
 */
public final class ConnectionInfo {
	public final int port;
	public final String token;
	public final long pid;
	public final boolean gui;

	public ConnectionInfo(int port, String token, long pid, boolean gui) {
		this.port = port;
		this.token = token;
		this.pid = pid;
		this.gui = gui;
	}

	public static File file() {
		String override = System.getProperty("openrocket.automation.file", System.getenv("OPENROCKET_AUTOMATION_FILE"));
		if (override != null && !override.isBlank()) {
			return new File(override);
		}
		return new File(SystemInfo.getUserApplicationDirectory(), "automation.json");
	}

	public void write() throws IOException {
		File f = file();
		f.getParentFile().mkdirs();
		JsonObject o = new JsonObject();
		o.addProperty("port", port);
		o.addProperty("token", token);
		o.addProperty("pid", pid);
		o.addProperty("gui", gui);
		Files.writeString(f.toPath(), o.toString(), StandardCharsets.UTF_8);
		try {
			Files.setPosixFilePermissions(f.toPath(),
					EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ignored) {
			// Windows: the user profile directory is already private
		}
	}

	public static ConnectionInfo read() throws IOException {
		File f = file();
		if (!f.exists()) {
			return null;
		}
		JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
		return new ConnectionInfo(o.get("port").getAsInt(), o.get("token").getAsString(),
				o.has("pid") ? o.get("pid").getAsLong() : -1, o.has("gui") && o.get("gui").getAsBoolean());
	}

	public static void delete(long pid) {
		try {
			ConnectionInfo info = read();
			if (info != null && info.pid == pid) {
				Files.deleteIfExists(file().toPath());
			}
		} catch (IOException | RuntimeException ignored) {
		}
	}
}
