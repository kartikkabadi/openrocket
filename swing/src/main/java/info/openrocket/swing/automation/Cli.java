package info.openrocket.swing.automation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.protocol.Backend;
import info.openrocket.core.automation.protocol.Errors;
import info.openrocket.core.automation.protocol.LocalBackend;
import info.openrocket.core.automation.protocol.RemoteBackend;
import info.openrocket.core.document.OpenRocketDocument;

/**
 * Command-line front end. Every automation command can be run as
 * {@code openrocket cli <command> --param value ...}; values that parse as JSON are passed as JSON,
 * anything else as a string.
 */
public final class Cli {
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
	private static final Gson COMPACT = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

	private static final String USAGE = """
			Usage:
			  openrocket cli [options] <command> [--param value ...] [--json '{...}' | --json @file.json]
			  openrocket cli [options] repl                interactive session (one command per line)
			  openrocket cli [options] run <file|->        run a script of commands, one per line
			  openrocket cli commands                      list commands (same as: help)
			  openrocket cli help <command>                describe a command's parameters

			Options:
			  --connect   operate a running OpenRocket (GUI started with 'openrocket gui', or 'openrocket serve')
			  --save      after the commands, save every changed document back to its file (headless)
			  --compact   print single-line JSON

			Script / REPL lines:  <command> [key=value ...] | <command> {json}   ('#' starts a comment)

			Examples:
			  openrocket cli doc.examples
			  openrocket cli sim.run --doc rocket.ork
			  openrocket cli component.set --doc rocket.ork --component "Nose cone" --properties '{"length":"12 cm"}' --save
			  openrocket cli motor.search --impulseClass C --diameter "18 mm"
			""";

	private Cli() {
	}

	public static int run(List<String> argv) throws Exception {
		List<String> args = new ArrayList<>(argv);
		boolean connect = args.remove("--connect") || "1".equals(System.getenv("OPENROCKET_CONNECT"));
		boolean save = args.remove("--save");
		boolean compact = args.remove("--compact");
		if (args.isEmpty() || args.get(0).equals("--help") || args.get(0).equals("-h")) {
			System.err.print(USAGE);
			return args.isEmpty() ? 2 : 0;
		}
		AutomationStartup.quietLogging(AutomationStartup.logLevel());
		PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
				StandardCharsets.UTF_8);
		Gson gson = compact ? COMPACT : PRETTY;

		Backend backend;
		AutomationSession session = null;
		if (connect) {
			backend = RemoteBackend.connect();
			if (backend == null) {
				System.err.println("No running OpenRocket automation server found. Start the GUI with "
						+ "'openrocket gui', or a headless server with 'openrocket serve'.");
				return 1;
			}
		} else {
			session = Automation.headlessSession();
			backend = new LocalBackend(session);
		}

		int status;
		try (backend) {
			String cmd = args.get(0);
			switch (cmd) {
				case "commands" -> status = runOne(backend, "help", new JsonObject(), out, gson);
				case "help" -> status = runOne(backend, "help",
						args.size() > 1 ? J.obj("command", args.get(1)) : new JsonObject(), out, gson);
				case "repl" -> status = script(backend, new InputStreamReader(System.in, StandardCharsets.UTF_8), out,
						gson, true);
				case "run" -> {
					if (args.size() < 2) {
						System.err.println("run needs a file (or - for stdin)");
						return 2;
					}
					Reader r = args.get(1).equals("-") ? new InputStreamReader(System.in, StandardCharsets.UTF_8)
							: new FileReader(args.get(1), StandardCharsets.UTF_8);
					status = script(backend, r, out, gson, false);
				}
				default -> status = runOne(backend, cmd, parseParams(args.subList(1, args.size())), out, gson);
			}
			if (save && session != null && status == 0) {
				for (OpenRocketDocument doc : session.takeDirty()) {
					if (doc.getFile() != null) {
						JsonObject p = J.obj("doc", session.idOf(doc));
						backend.call("doc.save", p);
						System.err.println("Saved " + doc.getFile());
					}
				}
			}
		}
		return status;
	}

	private static int runOne(Backend backend, String cmd, JsonObject params, PrintStream out, Gson gson) {
		try {
			JsonElement r = backend.call(cmd, params);
			if (r != null && r.isJsonObject() && r.getAsJsonObject().has("_image")) {
				r.getAsJsonObject().remove("_image"); // images are for MCP clients; use 'path' to save
				r.getAsJsonObject().addProperty("note", "image data omitted; pass --path to save the PNG");
			}
			out.println(gson.toJson(r));
			return 0;
		} catch (Throwable e) {
			JsonObject err = new JsonObject();
			err.add("error", Errors.toJson(e));
			System.err.println(gson.toJson(err));
			return 1;
		}
	}

	private static int script(Backend backend, Reader reader, PrintStream out, Gson gson, boolean interactive)
			throws IOException {
		BufferedReader in = new BufferedReader(reader);
		int failures = 0;
		if (interactive) {
			System.err.println("OpenRocket automation REPL — 'help' lists commands, Ctrl-D exits.");
		}
		while (true) {
			if (interactive) {
				System.err.print("openrocket> ");
				System.err.flush();
			}
			String line = in.readLine();
			if (line == null) {
				break;
			}
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.equals("exit") || line.equals("quit")) {
				break;
			}
			String cmd;
			JsonObject params;
			try {
				List<String> tokens = tokenize(line);
				cmd = tokens.get(0);
				String restText = line.substring(line.indexOf(cmd) + cmd.length()).trim();
				if (restText.startsWith("{")) {
					params = JsonParser.parseString(restText).getAsJsonObject();
				} else {
					params = new JsonObject();
					for (String t : tokens.subList(1, tokens.size())) {
						int eq = t.indexOf('=');
						if (eq <= 0) {
							throw new IllegalArgumentException("Expected key=value, got '" + t + "'");
						}
						params.add(t.substring(0, eq), value(t.substring(eq + 1)));
					}
				}
			} catch (RuntimeException e) {
				System.err.println("Syntax error: " + e.getMessage());
				failures++;
				if (!interactive) {
					return 1;
				}
				continue;
			}
			if (runOne(backend, cmd, params, out, gson) != 0) {
				failures++;
				if (!interactive) {
					return 1;
				}
			}
		}
		return failures == 0 || interactive ? 0 : 1;
	}

	/** Parse "--key value" / "--key=value" pairs and --json. */
	static JsonObject parseParams(List<String> args) throws IOException {
		JsonObject params = new JsonObject();
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if (!a.startsWith("--")) {
				throw new IllegalArgumentException("Unexpected argument '" + a + "' (parameters are --name value)");
			}
			String key = a.substring(2);
			String val;
			int eq = key.indexOf('=');
			if (eq >= 0) {
				val = key.substring(eq + 1);
				key = key.substring(0, eq);
			} else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
				val = args.get(++i);
			} else {
				val = "true";
			}
			if (key.equals("json")) {
				String text = val.startsWith("@") ? Files.readString(new File(val.substring(1)).toPath()) : val;
				JsonParser.parseString(text).getAsJsonObject().entrySet().forEach(e -> params.add(e.getKey(), e.getValue()));
			} else {
				params.add(key, value(val));
			}
		}
		return params;
	}

	/** A value is JSON if it parses as an object, array, number, boolean or null; otherwise a string. */
	static JsonElement value(String s) {
		String t = s.trim();
		if (t.startsWith("{") || t.startsWith("[") || t.equals("true") || t.equals("false") || t.equals("null")
				|| t.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
			try {
				return JsonParser.parseString(t);
			} catch (RuntimeException ignored) {
			}
		}
		if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
			try {
				return JsonParser.parseString(t);
			} catch (RuntimeException ignored) {
			}
		}
		return new JsonPrimitive(s);
	}

	/** Split a line on whitespace, honouring single/double quotes and {...}/[...] groups. */
	static List<String> tokenize(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		char quote = 0;
		int depth = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				} else {
					cur.append(c);
				}
			} else if ((c == '\'' || c == '"') && depth == 0) {
				quote = c;
			} else if (c == '{' || c == '[') {
				depth++;
				cur.append(c);
			} else if (c == '}' || c == ']') {
				depth--;
				cur.append(c);
			} else if (Character.isWhitespace(c) && depth == 0) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
			} else {
				cur.append(c);
			}
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException("Empty command");
		}
		return out;
	}
}
