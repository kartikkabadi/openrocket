package info.openrocket.core.automation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

/**
 * State shared by a sequence of automation commands: the open documents and how to reach
 * objects inside them by reference.
 */
public class AutomationSession {
	private final DocumentHost host;
	private final CommandRegistry registry;
	private final Map<OpenRocketDocument, String> ids = new WeakHashMap<>();
	private int nextId = 1;
	/** Documents changed by commands since the last save, used by one-shot CLI runs. */
	private final List<OpenRocketDocument> dirty = new ArrayList<>();
	private final Map<String, Object> variables = new java.util.HashMap<>();

	public AutomationSession(DocumentHost host, CommandRegistry registry) {
		this.host = host;
		this.registry = registry;
	}

	public DocumentHost getHost() {
		return host;
	}

	public CommandRegistry getRegistry() {
		return registry;
	}

	/** Scratch storage for scripts (persisting across script.eval calls). */
	public Map<String, Object> getVariables() {
		return variables;
	}

	public JsonElement execute(Command c, JsonObject params) throws Exception {
		Params p = new Params(params);
		if (c.runsOnGuiThread()) {
			return host.run(() -> c.getHandler().run(p, this));
		}
		return c.getHandler().run(p, this);
	}

	public JsonElement call(String command, JsonObject params) throws Exception {
		return registry.invoke(command, params, this);
	}

	// ------------------------------------------------------------------ documents

	public synchronized String idOf(OpenRocketDocument doc) {
		return ids.computeIfAbsent(doc, d -> "doc" + (nextId++));
	}

	public List<OpenRocketDocument> documents() {
		return host.documents();
	}

	public void addDocument(OpenRocketDocument doc) {
		host.add(doc);
		idOf(doc);
	}

	public void markDirty(OpenRocketDocument doc) {
		synchronized (dirty) {
			if (!dirty.contains(doc)) {
				dirty.add(doc);
			}
		}
	}

	public List<OpenRocketDocument> takeDirty() {
		synchronized (dirty) {
			List<OpenRocketDocument> l = new ArrayList<>(dirty);
			dirty.clear();
			return l;
		}
	}

	/**
	 * Resolve the "doc" parameter: a document id, a file path (opened on demand), or nothing for the
	 * active document.
	 */
	public OpenRocketDocument document(Params p) throws Exception {
		return document(p.string("doc", null));
	}

	public OpenRocketDocument document(String ref) throws Exception {
		List<OpenRocketDocument> docs = host.documents();
		if (ref == null || ref.isBlank() || ref.equals("active")) {
			OpenRocketDocument a = host.active();
			if (a != null && docs.contains(a)) {
				return a;
			}
			if (docs.size() == 1) {
				return docs.get(0);
			}
			if (docs.isEmpty()) {
				throw AutomationException.notFound("No document is open. Use doc.new or doc.open first, "
						+ "or pass \"doc\" as a file path.");
			}
			throw AutomationException.badParam("Several documents are open; pass \"doc\" (see doc.list)");
		}
		for (OpenRocketDocument d : docs) {
			if (ref.equals(idOf(d))) {
				return d;
			}
		}
		File f = new File(ref);
		if (f.exists()) {
			String canonical = canonical(f);
			for (OpenRocketDocument d : docs) {
				if (d.getFile() != null && canonical(d.getFile()).equals(canonical)) {
					return d;
				}
			}
			OpenRocketDocument doc = host.load(f);
			addDocument(doc);
			return doc;
		}
		for (OpenRocketDocument d : docs) {
			if (d.getFile() != null && d.getFile().getName().equals(ref)) {
				return d;
			}
		}
		throw AutomationException.notFound("No open document '" + ref + "' and no such file");
	}

	private static String canonical(File f) {
		try {
			return f.getCanonicalPath();
		} catch (IOException e) {
			return f.getAbsolutePath();
		}
	}

	// ------------------------------------------------------------------ components

	/**
	 * Resolve a component reference: its UUID, a unique name, a path of names separated by '/'
	 * (e.g. "Sustainer/Body tube/Trapezoidal fin set"), "rocket" for the root, or "Type#n" for the
	 * n-th (0-based) component of a type, e.g. "FinSet#0".
	 */
	public RocketComponent component(OpenRocketDocument doc, String ref) {
		Rocket rocket = doc.getRocket();
		if (ref == null || ref.isBlank() || ref.equalsIgnoreCase("rocket") || ref.equals("/")) {
			return rocket;
		}
		String r = ref.trim();
		try {
			UUID id = UUID.fromString(r);
			for (RocketComponent c : rocket) {
				if (c.getID().equals(id)) {
					return c;
				}
			}
			throw AutomationException.notFound("No component with id " + r);
		} catch (IllegalArgumentException ignored) {
		}
		int hash = r.lastIndexOf('#');
		if (hash > 0) {
			String type = r.substring(0, hash);
			int n;
			try {
				n = Integer.parseInt(r.substring(hash + 1));
			} catch (NumberFormatException e) {
				n = -1;
			}
			if (n >= 0) {
				int i = 0;
				for (RocketComponent c : rocket) {
					if (c.getClass().getSimpleName().equalsIgnoreCase(type)
							|| c.getComponentName().equalsIgnoreCase(type) || isSubtype(c, type)) {
						if (i++ == n) {
							return c;
						}
					}
				}
				throw AutomationException.notFound("No component " + r);
			}
		}
		List<RocketComponent> matches = new ArrayList<>();
		for (RocketComponent c : rocket) {
			if (c.getName().equals(r)) {
				matches.add(c);
			}
		}
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.size() > 1) {
			throw AutomationException.badParam("Component name '" + r + "' is ambiguous (" + matches.size()
					+ " matches); use its id or a path such as 'Stage/Body tube/" + r + "'");
		}
		if (r.contains("/")) {
			RocketComponent cur = rocket;
			String[] parts = r.split("/");
			int start = parts[0].equals(rocket.getName()) || parts[0].isEmpty() ? 1 : 0;
			for (int i = start; i < parts.length; i++) {
				RocketComponent next = null;
				for (RocketComponent ch : cur.getChildren()) {
					if (ch.getName().equals(parts[i])) {
						next = ch;
						break;
					}
				}
				if (next == null) {
					throw AutomationException.notFound("No component '" + parts[i] + "' under '" + cur.getName() + "'");
				}
				cur = next;
			}
			return cur;
		}
		for (RocketComponent c : rocket) {
			if (c.getName().equalsIgnoreCase(r)) {
				matches.add(c);
			}
		}
		if (matches.size() == 1) {
			return matches.get(0);
		}
		throw AutomationException.notFound("No component '" + r + "'. Use component.tree to list components.");
	}

	private static boolean isSubtype(RocketComponent c, String type) {
		for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			if (k.getSimpleName().equalsIgnoreCase(type)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ simulations

	/** Resolve a simulation by 0-based index or by name. */
	public Simulation simulation(OpenRocketDocument doc, JsonElement ref) {
		List<Simulation> sims = doc.getSimulations();
		if (ref == null || ref.isJsonNull()) {
			if (sims.size() == 1) {
				return sims.get(0);
			}
			throw AutomationException.badParam("Pass \"sim\" (index or name); the document has " + sims.size()
					+ " simulations");
		}
		if (ref.isJsonPrimitive() && ref.getAsJsonPrimitive().isNumber()) {
			int i = ref.getAsInt();
			if (i < 0 || i >= sims.size()) {
				throw AutomationException.notFound("Simulation index " + i + " out of range (0.." + (sims.size() - 1) + ")");
			}
			return sims.get(i);
		}
		String s = ref.getAsString();
		for (Simulation sim : sims) {
			if (sim.getName().equals(s)) {
				return sim;
			}
		}
		for (Simulation sim : sims) {
			if (sim.getName().equalsIgnoreCase(s)) {
				return sim;
			}
		}
		try {
			return simulation(doc, new com.google.gson.JsonPrimitive(Integer.parseInt(s)));
		} catch (NumberFormatException ignored) {
		}
		throw AutomationException.notFound("No simulation '" + s + "'");
	}

	// ------------------------------------------------------------------ flight configurations

	/**
	 * Resolve a flight configuration by id, name, 0-based index, or "selected" (the default when null).
	 */
	public FlightConfiguration configuration(OpenRocketDocument doc, JsonElement ref) {
		Rocket rocket = doc.getRocket();
		if (ref == null || ref.isJsonNull() || ref.getAsString().equals("selected")) {
			return rocket.getSelectedConfiguration();
		}
		if (ref.isJsonPrimitive() && ref.getAsJsonPrimitive().isNumber()) {
			int i = ref.getAsInt();
			if (i < 0 || i >= rocket.getConfigurationCount()) {
				throw AutomationException.notFound("Configuration index " + i + " out of range");
			}
			return rocket.getFlightConfigurationByIndex(i);
		}
		String s = ref.getAsString();
		if (s.equals("default")) {
			return rocket.getEmptyConfiguration();
		}
		try {
			FlightConfigurationId id = new FlightConfigurationId(UUID.fromString(s));
			if (rocket.containsFlightConfigurationID(id)) {
				return rocket.getFlightConfiguration(id);
			}
		} catch (IllegalArgumentException ignored) {
		}
		for (FlightConfigurationId id : rocket.getIds()) {
			FlightConfiguration fc = rocket.getFlightConfiguration(id);
			if (fc.getName().equals(s) || fc.getNameRaw().equals(s)) {
				return fc;
			}
		}
		try {
			return configuration(doc, new com.google.gson.JsonPrimitive(Integer.parseInt(s)));
		} catch (NumberFormatException ignored) {
		}
		throw AutomationException.notFound("No flight configuration '" + s + "'. Use config.list.");
	}
}
