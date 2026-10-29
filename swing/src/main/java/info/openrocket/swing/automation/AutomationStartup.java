package info.openrocket.swing.automation;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import info.openrocket.core.automation.protocol.Backend;
import info.openrocket.core.automation.protocol.LocalBackend;
import info.openrocket.core.automation.protocol.McpServer;
import info.openrocket.core.automation.protocol.RemoteBackend;
import info.openrocket.core.automation.protocol.SocketServer;
import info.openrocket.swing.startup.SwingStartup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/**
 * Entry point for the programmatic interfaces:
 * <pre>
 * openrocket cli   &lt;command&gt; [--param value ...]   run one command (or: repl, run FILE, commands)
 * openrocket mcp   [--gui | --connect | --auto] [files]   Model Context Protocol server on stdio
 *                  (--auto: attach to a running OpenRocket if there is one, else headless)
 * openrocket serve [--port N]                    headless automation server for cli/mcp --connect
 * openrocket gui   [--port N] [files]            the GUI with the automation server enabled
 * </pre>
 */
public final class AutomationStartup {
	public static final List<String> MODES = List.of("cli", "mcp", "serve", "gui");

	private AutomationStartup() {
	}

	/** Whether the arguments select a headless mode (decided before AWT is touched). */
	public static boolean isHeadless(String[] args) {
		if (args.length == 0) {
			return false;
		}
		List<String> a = Arrays.asList(args);
		return switch (args[0]) {
			case "cli", "serve" -> true;
			case "mcp" -> !a.contains("--gui");
			default -> false;
		};
	}

	public static void main(String[] args) throws Exception {
		// GraalJS (script.eval, scripting extension) warns on stderr about interpreter-only mode
		System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
		String mode = args[0];
		List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
		switch (mode) {
			case "cli" -> System.exit(Cli.run(rest));
			case "mcp" -> mcp(rest);
			case "serve" -> serve(rest);
			case "gui" -> gui(rest);
			default -> throw new IllegalArgumentException(mode);
		}
	}

	/** Route all logging and stray prints to stderr so stdout stays clean for protocol data. */
	static void quietLogging(Level level) {
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
		LoggerContext ctx = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
		Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
		root.detachAndStopAllAppenders();
		ConsoleAppender<ILoggingEvent> app = new ConsoleAppender<>();
		app.setContext(ctx);
		app.setTarget("System.err");
		PatternLayoutEncoder enc = new PatternLayoutEncoder();
		enc.setContext(ctx);
		enc.setPattern("%-5level %logger{20} - %msg%n");
		enc.start();
		app.setEncoder(enc);
		app.start();
		root.addAppender(app);
		root.setLevel(level);
	}

	static Level logLevel() {
		String l = System.getenv("OPENROCKET_LOG_LEVEL");
		return l == null ? Level.WARN : Level.toLevel(l, Level.WARN);
	}

	private static String option(List<String> args, String name) {
		int i = args.indexOf(name);
		if (i >= 0 && i + 1 < args.size()) {
			String v = args.get(i + 1);
			args.remove(i + 1);
			args.remove(i);
			return v;
		}
		return null;
	}

	private static boolean flag(List<String> args, String name) {
		return args.remove(name);
	}

	private static void mcp(List<String> args) throws Exception {
		// Protocol output goes to the real stdout; everything else to stderr
		OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
		InputStream protocolIn = System.in;
		quietLogging(logLevel());
		Backend backend;
		if (flag(args, "--auto")) {
			// Attach to a running GUI or server when there is one, else work headless
			backend = RemoteBackend.connect();
			if (backend == null) {
				backend = new LocalBackend(Automation.headlessSession());
			}
			for (String f : args) {
				backend.call("doc.open", info.openrocket.core.automation.J.obj("path", f));
			}
		} else if (flag(args, "--connect")) {
			backend = RemoteBackend.connect();
			if (backend == null) {
				System.err.println("No running OpenRocket automation server found. Start one with "
						+ "'openrocket gui' or 'openrocket serve'.");
				System.exit(1);
				return;
			}
		} else if (flag(args, "--gui")) {
			System.setProperty(Automation.NO_PROMPT_PROPERTY, "true");
			useSwingFileChooser();
			String port = option(args, "--port");
			if (port != null) {
				System.setProperty(Automation.SERVER_PROPERTY, port);
			}
			SwingStartup.main(args.toArray(new String[0]));
			backend = new LocalBackend(Automation.guiSession());
		} else {
			backend = new LocalBackend(Automation.headlessSession());
			for (String f : args) {
				backend.call("doc.open", info.openrocket.core.automation.J.obj("path", f));
			}
		}
		new McpServer(backend, protocolIn, protocolOut).run();
		System.exit(0);
	}

	private static void serve(List<String> args) throws Exception {
		quietLogging(logLevel());
		String port = option(args, "--port");
		LocalBackend backend = new LocalBackend(Automation.headlessSession());
		for (String f : args) {
			backend.call("doc.open", info.openrocket.core.automation.J.obj("path", f));
		}
		SocketServer server = new SocketServer(backend, false);
		int p = server.start(port == null ? 0 : Integer.parseInt(port));
		System.err.println("OpenRocket headless automation server listening on 127.0.0.1:" + p
				+ " (connection file: " + info.openrocket.core.automation.protocol.ConnectionInfo.file() + ")");
		Thread.currentThread().join();
	}

	private static void gui(List<String> args) throws Exception {
		String port = option(args, "--port");
		System.setProperty(Automation.SERVER_PROPERTY, port == null ? "0" : port);
		useSwingFileChooser();
		if (flag(args, "--no-prompt")) {
			System.setProperty(Automation.NO_PROMPT_PROPERTY, "true");
		}
		SwingStartup.main(args.toArray(new String[0]));
	}

	/**
	 * Native (OS) file dialogs are not Java windows, so the gui.* commands cannot see or operate them.
	 * Use Swing's file chooser instead unless the user chose otherwise with -Dflatlaf.useSystemFileChooser.
	 */
	private static void useSwingFileChooser() {
		if (System.getProperty("flatlaf.useSystemFileChooser") == null) {
			System.setProperty("flatlaf.useSystemFileChooser", "false");
		}
	}
}
