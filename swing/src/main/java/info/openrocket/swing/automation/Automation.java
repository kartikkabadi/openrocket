package info.openrocket.swing.automation;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;

import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.CommandRegistry;
import info.openrocket.core.automation.HeadlessDocumentHost;
import info.openrocket.core.automation.commands.StandardCommands;
import info.openrocket.core.automation.protocol.LocalBackend;
import info.openrocket.core.automation.protocol.SocketServer;
import info.openrocket.core.plugin.PluginModule;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.startup.Application;
import info.openrocket.core.startup.CoreModule;
import info.openrocket.swing.gui.util.SwingPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates automation sessions for headless use and for the running GUI.
 */
public final class Automation {
	private static final Logger log = LoggerFactory.getLogger(Automation.class);

	/** System property: start the automation server when the GUI starts (value = port, 0 = any). */
	public static final String SERVER_PROPERTY = "openrocket.automation.port";
	/** System property: skip startup prompts (welcome, update permission) that would block automation. */
	public static final String NO_PROMPT_PROPERTY = "openrocket.automation.noprompt";

	private static AutomationSession guiSession;
	private static SocketServer guiServer;

	private Automation() {
	}

	public static boolean suppressStartupDialogs() {
		return Boolean.getBoolean(NO_PROMPT_PROPERTY);
	}

	/**
	 * Initialize OpenRocket without a GUI. Preferences are the same as the GUI's (units, defaults,
	 * user materials).
	 */
	public static synchronized void initHeadless() {
		if (Application.getInjector() != null) {
			return;
		}
		CoreModule core = new CoreModule();
		Injector injector = Guice.createInjector(Modules.override(core).with(
				binder -> binder.bind(ApplicationPreferences.class).to(SwingPreferences.class)
						.in(com.google.inject.Scopes.SINGLETON)),
				new PluginModule());
		Application.setInjector(injector);
		core.startLoader();
		SwingPreferences prefs = (SwingPreferences) Application.getPreferences();
		prefs.loadDefaultUnits();
		prefs.loadDefaultComponentMaterials();
	}

	public static CommandRegistry headlessRegistry() {
		return StandardCommands.registry();
	}

	public static AutomationSession headlessSession() {
		initHeadless();
		return new AutomationSession(new HeadlessDocumentHost(), headlessRegistry());
	}

	/** The session that drives the running GUI (created on first use, after the GUI has started). */
	public static synchronized AutomationSession guiSession() {
		if (guiSession == null) {
			CommandRegistry r = StandardCommands.registry();
			r.registerAll(GuiCommands.all());
			guiSession = new AutomationSession(new GuiDocumentHost(), r);
		}
		return guiSession;
	}

	/** Start the socket server for the GUI if requested by {@link #SERVER_PROPERTY}. */
	public static synchronized void startGuiServerIfRequested() {
		String port = System.getProperty(SERVER_PROPERTY);
		if (port == null || guiServer != null) {
			return;
		}
		try {
			guiServer = new SocketServer(new LocalBackend(guiSession()), true);
			int p = guiServer.start(port.isBlank() ? 0 : Integer.parseInt(port));
			System.err.println("OpenRocket automation server listening on 127.0.0.1:" + p);
		} catch (Exception e) {
			log.error("Could not start the automation server", e);
		}
	}
}
