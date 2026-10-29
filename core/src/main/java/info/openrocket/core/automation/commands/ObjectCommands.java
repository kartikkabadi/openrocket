package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.BeanAccess;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.Params;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import info.openrocket.core.startup.Application;

/**
 * Generic access to any object in the model by a target string. This is the escape hatch that
 * reaches every getter, setter and public method, including ones without a dedicated command.
 */
public final class ObjectCommands {
	private ObjectCommands() {
	}

	public static final String TARGET_HELP = "Target object: 'rocket', 'document', 'preferences', 'storage-options', "
			+ "'component:<ref>' (or just a component ref), 'sim:<ref>', 'sim-options:<ref>', "
			+ "'sim-extension:<sim>:<index>', 'config:<ref>', 'motor-config:<mount>@<config>', "
			+ "'deployment:<recovery device>@<config>', 'separation:<stage>@<config>', 'var:<script variable>'. "
			+ "<config> may be omitted (selected configuration) or 'default' to edit the default value shared by all "
			+ "configurations without an override.";

	/** Resolve a target string to an object. */
	public static Object resolve(AutomationSession s, OpenRocketDocument doc, String target) {
		String t = target.trim();
		int colon = t.indexOf(':');
		String kind = colon < 0 ? t : t.substring(0, colon);
		String arg = colon < 0 ? "" : t.substring(colon + 1);
		switch (kind) {
			case "rocket":
				return doc.getRocket();
			case "document":
				return doc;
			case "preferences":
				return Application.getPreferences();
			case "storage-options":
				return doc.getDefaultStorageOptions();
			case "component":
				return s.component(doc, arg);
			case "sim":
				return s.simulation(doc, new JsonPrimitive(arg));
			case "sim-options":
				return s.simulation(doc, new JsonPrimitive(arg)).getOptions();
			case "sim-extension": {
				int c = arg.lastIndexOf(':');
				Simulation sim = s.simulation(doc, new JsonPrimitive(arg.substring(0, c)));
				return sim.getSimulationExtensions().get(Integer.parseInt(arg.substring(c + 1)));
			}
			case "config":
				return s.configuration(doc, arg.isEmpty() ? null : new JsonPrimitive(arg));
			case "motor-config": {
				Split sp = split(s, doc, arg);
				if (!(sp.component instanceof MotorMount mm)) {
					throw AutomationException.badParam(sp.component.getName() + " is not a motor mount");
				}
				return sp.isDefault ? mm.getMotorConfigurationSet().getDefault() : mm.getMotorConfig(sp.fcid);
			}
			case "deployment": {
				Split sp = split(s, doc, arg);
				if (!(sp.component instanceof RecoveryDevice rd)) {
					throw AutomationException.badParam(sp.component.getName() + " is not a recovery device");
				}
				var set = rd.getDeploymentConfigurations();
				if (sp.isDefault) {
					return set.getDefault();
				}
				if (set.isDefault(sp.fcid)) {
					DeploymentConfiguration copy = set.getDefault().copy(sp.fcid);
					set.set(sp.fcid, copy);
				}
				return set.get(sp.fcid);
			}
			case "separation": {
				Split sp = split(s, doc, arg);
				if (!(sp.component instanceof AxialStage st)) {
					throw AutomationException.badParam(sp.component.getName() + " is not a stage");
				}
				var set = st.getSeparationConfigurations();
				if (sp.isDefault) {
					return set.getDefault();
				}
				if (set.isDefault(sp.fcid)) {
					StageSeparationConfiguration copy = set.getDefault().copy(sp.fcid);
					set.set(sp.fcid, copy);
				}
				return set.get(sp.fcid);
			}
			case "var": {
				Object v = s.getVariables().get(arg);
				if (v == null) {
					throw AutomationException.notFound("No script variable '" + arg + "'");
				}
				return v;
			}
			default:
				return s.component(doc, t);
		}
	}

	private record Split(RocketComponent component, FlightConfigurationId fcid, boolean isDefault) {
	}

	private static Split split(AutomationSession s, OpenRocketDocument doc, String arg) {
		int at = arg.lastIndexOf('@');
		String compRef = at < 0 ? arg : arg.substring(0, at);
		String cfg = at < 0 ? null : arg.substring(at + 1);
		RocketComponent c = s.component(doc, compRef);
		if ("default".equals(cfg)) {
			return new Split(c, null, true);
		}
		FlightConfiguration fc = s.configuration(doc, cfg == null ? null : new JsonPrimitive(cfg));
		return new Split(c, fc.getId(), false);
	}

	private static Object target(AutomationSession s, Params p) throws Exception {
		String t = p.string("target");
		OpenRocketDocument doc = t.equals("preferences") ? null : s.document(p);
		if (doc == null) {
			return Application.getPreferences();
		}
		return resolve(s, doc, t);
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("object.get").readOnly()
				.description("Read the properties of any model object (see 'target'). Without 'names', lists all simple "
						+ "properties with type and writability. With names, any getter can be read, including complex ones.")
				.doc()
				.required("target", "string", TARGET_HELP)
				.param("names", "array", "Property names to read")
				.handler((p, s) -> {
					Object obj = target(s, p);
					List<String> names = p.strings("names");
					JsonObject o = new JsonObject();
					o.addProperty("class", obj.getClass().getName());
					if (names.isEmpty()) {
						o.add("properties", BeanAccess.describe(obj, true));
					} else {
						JsonObject v = new JsonObject();
						for (String n : names) {
							v.add(n, ValueCodec.toJson(BeanAccess.get(obj, n)));
						}
						o.add("properties", v);
					}
					return o;
				}).build());

		l.add(Command.builder("object.set")
				.description("Set properties of any model object (see 'target'), e.g. target 'sim-options:0' with "
						+ "{\"launchRodLength\": \"1.5 m\", \"windSpeedAverage\": 3}. Undoable when it changes the document.")
				.doc()
				.required("target", "string", TARGET_HELP)
				.required("properties", "object", "Property name → value")
				.mutating()
				.handler((p, s) -> {
					String t = p.string("target");
					if (t.equals("preferences")) {
						Object prefs = Application.getPreferences();
						return J.obj("set", ComponentCommands.applyProperties(s, null, prefs, p.object("properties")));
					}
					OpenRocketDocument doc = s.document(p);
					doc.addUndoPosition("Modify " + t);
					Object obj = resolve(s, doc, t);
					JsonArray set = ComponentCommands.applyProperties(s, doc, obj, p.object("properties"));
					s.markDirty(doc);
					return J.obj("set", set, "values", BeanAccess.describe(obj, false));
				}).build());

		l.add(Command.builder("object.call")
				.description("Call any public method on a model object (see 'target') with JSON arguments converted to the "
						+ "parameter types. Use object.methods to discover methods. The result is returned as JSON and, if "
						+ "'store' is given, kept as a script variable reachable as target 'var:<store>'.")
				.doc()
				.required("target", "string", TARGET_HELP)
				.required("method", "string", "Method name")
				.param("args", "array", "Arguments")
				.param("store", "string", "Keep the return value under this variable name")
				.mutating()
				.handler((p, s) -> {
					Object obj = target(s, p);
					OpenRocketDocument doc = obj == Application.getPreferences() ? null : s.document(p);
					if (doc != null) {
						doc.addUndoPosition("Call " + p.string("method"));
						s.markDirty(doc);
					}
					JsonArray args = p.has("args") ? p.array("args") : new JsonArray();
					Object r = BeanAccess.call(obj, p.string("method"), args, ref -> s.component(doc, ref));
					if (p.has("store") && r != null) {
						s.getVariables().put(p.string("store"), r);
					}
					return J.obj("result", r, "resultClass", r == null ? null : r.getClass().getName());
				}).build());

		l.add(Command.builder("object.methods").readOnly()
				.description("List the public methods of a model object (see 'target').")
				.doc()
				.required("target", "string", TARGET_HELP)
				.param("filter", "string", "Substring of the method name")
				.handler((p, s) -> {
					Object obj = target(s, p);
					return J.obj("class", obj.getClass().getName(), "methods",
							BeanAccess.methods(obj, p.string("filter", null)));
				}).build());

		return l;
	}
}
