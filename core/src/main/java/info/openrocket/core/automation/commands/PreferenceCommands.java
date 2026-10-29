package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.BeanAccess;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;

/** Application preferences and units. */
public final class PreferenceCommands {
	private PreferenceCommands() {
	}

	private static UnitGroup group(String name) {
		for (Map.Entry<String, UnitGroup> e : UnitGroup.UNITS.entrySet()) {
			if (e.getKey().equalsIgnoreCase(name)) {
				return e.getValue();
			}
		}
		throw AutomationException.badParam("Unknown unit group '" + name + "'. Valid: " + UnitGroup.UNITS.keySet());
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("pref.get")
				.description("Read application preferences (defaults for new simulations, launch site, materials, "
						+ "display options, ...). Without names lists all typed preferences.")
				.param("names", "array", "Preference property names")
				.param("key", "string", "Read a raw preference key instead (string value)")
				.handler((p, s) -> {
					ApplicationPreferences prefs = Application.getPreferences();
					if (p.has("key")) {
						return J.obj("key", p.string("key"), "value", prefs.getString(p.string("key"), null));
					}
					List<String> names = p.strings("names");
					if (names.isEmpty()) {
						return BeanAccess.describe(prefs, true);
					}
					JsonObject o = new JsonObject();
					for (String n : names) {
						o.add(n, ValueCodec.toJson(BeanAccess.get(prefs, n)));
					}
					return o;
				}).build());

		l.add(Command.builder("pref.set")
				.description("Change application preferences (persisted, shared with the GUI).")
				.param("properties", "object", "Preference property → value")
				.param("key", "string", "Raw preference key to set")
				.param("value", "string", "Raw value for 'key'")
				.handler((p, s) -> {
					ApplicationPreferences prefs = Application.getPreferences();
					if (p.has("key")) {
						prefs.putString(p.string("key"), p.string("value", null));
						return J.obj("key", p.string("key"), "value", prefs.getString(p.string("key"), null));
					}
					return J.obj("set", ComponentCommands.applyProperties(s, null, prefs, p.object("properties")));
				}).build());

		l.add(Command.builder("units.list")
				.description("Unit groups with their units and the user's preferred (display) unit.")
				.anyThread()
				.handler((p, s) -> {
					JsonArray a = new JsonArray();
					for (Map.Entry<String, UnitGroup> e : UnitGroup.UNITS.entrySet()) {
						JsonArray units = new JsonArray();
						for (Unit u : e.getValue().getUnits()) {
							units.add(u.getUnit());
						}
						a.add(J.obj("group", e.getKey(), "si", e.getValue().getSIUnit().getUnit(),
								"preferred", e.getValue().getDefaultUnit().getUnit(), "units", units));
					}
					return a;
				}).build());

		l.add(Command.builder("units.set_preferred")
				.description("Set the preferred display unit for a unit group (affects the GUI and 'preferred' exports), "
						+ "or switch all groups with system=metric|imperial.")
				.param("group", "string", "Unit group, e.g. LENGTH, MASS, VELOCITY")
				.param("unit", "string", "Unit symbol, e.g. in, cm, oz")
				.param("system", "string", "metric or imperial (sets all groups)")
				.handler((p, s) -> {
					if (p.has("system")) {
						if (p.string("system").equalsIgnoreCase("imperial")) {
							UnitGroup.setDefaultImperialUnits();
						} else {
							UnitGroup.setDefaultMetricUnits();
						}
					} else {
						UnitGroup g = group(p.string("group"));
						Unit u = ValueCodec.findUnit(p.string("unit"), g);
						if (u == null || !g.contains(u)) {
							throw AutomationException.badParam("Unit must be one of " + ValueCodec.unitList(g));
						}
						g.setDefaultUnit(g.getUnitIndex(u));
					}
					try {
						// Persisted by the GUI preference implementation only
						Application.getPreferences().getClass().getMethod("storeDefaultUnits")
								.invoke(Application.getPreferences());
					} catch (NoSuchMethodException ignored) {
					}
					return J.ok();
				}).build());

		l.add(Command.builder("units.convert")
				.description("Convert a quantity between units, e.g. value \"3 in\" to \"mm\".")
				.required("value", "quantity", "Value with unit, e.g. \"3 in\"")
				.param("to", "string", "Target unit (default SI)")
				.anyThread()
				.handler((p, s) -> {
					double si = p.number("value");
					if (!p.has("to")) {
						return J.obj("si", si);
					}
					Unit u = ValueCodec.findUnit(p.string("to"), null);
					if (u == null) {
						throw AutomationException.badParam("Unknown unit " + p.string("to"));
					}
					return J.obj("si", si, "value", u.toUnit(si), "unit", u.getUnit());
				}).build());

		return l;
	}
}
