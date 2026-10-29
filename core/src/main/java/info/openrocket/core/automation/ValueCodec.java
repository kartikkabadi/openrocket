package info.openrocket.core.automation;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.database.Databases;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.ClusterConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.util.ORColor;

/**
 * Conversion between Java values used by OpenRocket and JSON. Numbers are always SI on output;
 * on input, a string such as "5 cm", "2.5in" or "30 deg" is converted to SI.
 */
public final class ValueCodec {
	private static final Pattern QUANTITY = Pattern.compile(
			"^\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(.*?)\\s*$");
	private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
			Map.entry("deg", "°"), Map.entry("degree", "°"), Map.entry("degrees", "°"),
			Map.entry("inch", "in"), Map.entry("inches", "in"), Map.entry("\"", "in"), Map.entry("feet", "ft"),
			Map.entry("'", "ft"), Map.entry("percent", "%"), Map.entry("degc", "°C"), Map.entry("degf", "°F"));

	private ValueCodec() {
	}

	// ------------------------------------------------------------------ output

	public static JsonElement toJson(Object v) {
		return toJson(v, 3);
	}

	public static JsonElement toJson(Object v, int depth) {
		if (v == null) {
			return JsonNull.INSTANCE;
		}
		if (v instanceof JsonElement e) {
			return e;
		}
		if (v instanceof Double d) {
			return number(d);
		}
		if (v instanceof Float f) {
			return number(f.doubleValue());
		}
		if (v instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (v instanceof CharSequence || v instanceof Character || v instanceof UUID) {
			return new JsonPrimitive(v.toString());
		}
		if (v instanceof Enum<?> e) {
			return new JsonPrimitive(e.name());
		}
		if (v instanceof FlightConfigurationId id) {
			return new JsonPrimitive(id.toString());
		}
		if (v instanceof RocketComponent c) {
			JsonObject o = new JsonObject();
			o.addProperty("id", c.getID().toString());
			o.addProperty("name", c.getName());
			o.addProperty("type", c.getClass().getSimpleName());
			return o;
		}
		if (v instanceof Material m) {
			JsonObject o = new JsonObject();
			o.addProperty("name", m.getName());
			o.addProperty("type", m.getType().name());
			o.add("density", number(m.getDensity()));
			o.addProperty("userDefined", m.isUserDefined());
			return o;
		}
		if (v instanceof ClusterConfiguration cc) {
			return new JsonPrimitive(cc.getXMLName());
		}
		if (v instanceof ORColor c) {
			return new JsonPrimitive(String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(),
					c.getAlpha()));
		}
		if (v instanceof CoordinateIF c) {
			JsonObject o = new JsonObject();
			o.add("x", number(c.getX()));
			o.add("y", number(c.getY()));
			o.add("z", number(c.getZ()));
			if (c.getWeight() != 0) {
				o.add("weight", number(c.getWeight()));
			}
			return o;
		}
		if (v instanceof Motor m) {
			JsonObject o = new JsonObject();
			o.addProperty("designation", m.getDesignation());
			o.addProperty("commonName", m.getCommonName());
			o.addProperty("digest", m.getDigest());
			if (m instanceof info.openrocket.core.motor.ThrustCurveMotor t) {
				o.addProperty("manufacturer", t.getManufacturer().getDisplayName());
			}
			return o;
		}
		if (v instanceof ComponentPreset p) {
			JsonObject o = new JsonObject();
			o.addProperty("manufacturer", p.getManufacturer().getDisplayName());
			o.addProperty("partNo", p.getPartNo());
			o.addProperty("type", p.getType().name());
			return o;
		}
		if (v instanceof FlightConfiguration fc) {
			JsonObject o = new JsonObject();
			o.addProperty("id", fc.getId().toString());
			o.addProperty("name", fc.getName());
			return o;
		}
		if (v instanceof Simulation s) {
			JsonObject o = new JsonObject();
			o.addProperty("name", s.getName());
			o.addProperty("status", s.getStatus().name());
			return o;
		}
		if (v instanceof Unit u) {
			return new JsonPrimitive(u.getUnit());
		}
		if (v instanceof UnitGroup) {
			Unit si = ((UnitGroup) v).getSIUnit();
			return new JsonPrimitive(si == null ? "" : si.getUnit());
		}
		if (depth <= 0) {
			return new JsonPrimitive(v.toString());
		}
		if (v.getClass().isArray()) {
			JsonArray a = new JsonArray();
			int n = Array.getLength(v);
			for (int i = 0; i < n; i++) {
				a.add(toJson(Array.get(v, i), depth - 1));
			}
			return a;
		}
		if (v instanceof Collection<?> col) {
			JsonArray a = new JsonArray();
			for (Object x : col) {
				a.add(toJson(x, depth - 1));
			}
			return a;
		}
		if (v instanceof Map<?, ?> map) {
			JsonObject o = new JsonObject();
			for (Map.Entry<?, ?> e : map.entrySet()) {
				o.add(String.valueOf(e.getKey()), toJson(e.getValue(), depth - 1));
			}
			return o;
		}
		return new JsonPrimitive(v.toString());
	}

	/** JSON number, or null for NaN / infinite values. */
	public static JsonElement number(double d) {
		if (Double.isNaN(d) || Double.isInfinite(d)) {
			return JsonNull.INSTANCE;
		}
		return new JsonPrimitive(d);
	}

	/** Whether values of this type are rendered as a plain JSON value (and so worth listing as properties). */
	public static boolean isSimpleType(Class<?> t) {
		return t.isPrimitive() || Number.class.isAssignableFrom(t) || t == Boolean.class || t == String.class
				|| t.isEnum() || t == UUID.class || Material.class.isAssignableFrom(t) || t == ORColor.class
				|| CoordinateIF.class.isAssignableFrom(t) || RocketComponent.class.isAssignableFrom(t)
				|| ComponentPreset.class.isAssignableFrom(t) || t == FlightConfigurationId.class || t == ClusterConfiguration.class
				|| (t.isArray() && isSimpleType(t.getComponentType()))
				|| (t.getEnclosingClass() != null && t.getEnclosingClass().isEnum());
	}

	// ------------------------------------------------------------------ input

	/**
	 * Parse a number, optionally with a unit, to SI.
	 *
	 * @param group  the expected unit group, or null to accept any known unit
	 */
	public static double parseQuantity(JsonElement e, UnitGroup group) {
		if (e == null || e.isJsonNull()) {
			throw AutomationException.badParam("Expected a number");
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return e.getAsDouble();
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			throw AutomationException.badParam("Expected a number, got " + e);
		}
		if (e.isJsonObject() && e.getAsJsonObject().has("value")) {
			JsonObject o = e.getAsJsonObject();
			String unit = o.has("unit") ? o.get("unit").getAsString() : "";
			return parseQuantity(new JsonPrimitive(o.get("value").getAsString() + " " + unit), group);
		}
		return parseQuantity(e.getAsString(), group);
	}

	public static double parseQuantity(String s, UnitGroup group) {
		String str = s.trim();
		if (str.equalsIgnoreCase("nan")) {
			return Double.NaN;
		}
		Matcher m = QUANTITY.matcher(str);
		if (!m.matches()) {
			throw AutomationException.badParam("Cannot parse '" + s + "' as a number with optional unit");
		}
		double value = Double.parseDouble(m.group(1));
		String unitName = m.group(2);
		if (unitName.isEmpty()) {
			return value;
		}
		if (group == UnitGroup.UNITS_LATITUDE || group == UnitGroup.UNITS_LONGITUDE) {
			// Stored in degrees: accept "30 deg", "30°", "30 N", "80.6 W" (south/west are negative)
			String u = unitName.toLowerCase(Locale.ROOT).replace("°", "").replace("degrees", "").replace("deg", "").trim();
			switch (u) {
				case "", "n", "e":
					return value;
				case "s", "w":
					return -value;
				default:
					break;
			}
		}
		Unit unit = findUnit(unitName, group);
		if (unit == null) {
			throw AutomationException.badParam("Unknown unit '" + unitName + "' in '" + s + "'"
					+ (group != null ? ". Valid units: " + unitList(group) : ""));
		}
		return unit.fromUnit(value);
	}

	public static Unit findUnit(String name, UnitGroup group) {
		Unit u = findUnitExact(name, group);
		if (u == null) {
			// ASCII spellings: "g/cm3", "m^2", "m/s2", "kg*m2", "deg/s"
			String ascii = name.replace("^", "").replaceAll("(?<=[a-zA-Z])2", "²").replaceAll("(?<=[a-zA-Z])3", "³")
					.replace('*', '·').replace("deg", "°");
			if (!ascii.equals(name)) {
				u = findUnitExact(ascii, group);
			}
		}
		return u;
	}

	private static Unit findUnitExact(String name, UnitGroup group) {
		String n = UNIT_ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name);
		for (boolean ignoreCase : new boolean[] { false, true }) {
			if (group != null) {
				Unit u = findInGroup(group, n, ignoreCase);
				if (u != null) {
					return u;
				}
			}
			for (UnitGroup g : UnitGroup.UNITS.values()) {
				Unit u = findInGroup(g, n, ignoreCase);
				if (u != null) {
					return u;
				}
			}
		}
		return null;
	}

	private static Unit findInGroup(UnitGroup g, String name, boolean ignoreCase) {
		for (Unit u : g.getUnits()) {
			if (ignoreCase ? u.getUnit().equalsIgnoreCase(name) : u.getUnit().equals(name)) {
				return u;
			}
		}
		return null;
	}

	public static String unitList(UnitGroup g) {
		List<String> names = new ArrayList<>();
		for (Unit u : g.getUnits()) {
			names.add(u.getUnit());
		}
		return String.join(", ", names);
	}

	/**
	 * Convert JSON to a value of the given Java type.
	 *
	 * @param e        the JSON value
	 * @param type     the target (generic) type
	 * @param group    unit group hint for numbers (may be null)
	 * @param current  the current value of the property (may be null), used to pick e.g. the material type
	 * @param resolver resolves component references (may be null)
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Object fromJson(JsonElement e, Type type, UnitGroup group, Object current,
			java.util.function.Function<String, RocketComponent> resolver) {
		Class<?> t = rawClass(type);
		if (e == null || e.isJsonNull()) {
			if (t.isPrimitive()) {
				throw AutomationException.badParam("null is not allowed for " + t.getSimpleName());
			}
			return null;
		}
		if (t == double.class || t == Double.class) {
			return parseQuantity(e, group);
		}
		if (t == float.class || t == Float.class) {
			return (float) parseQuantity(e, group);
		}
		if (t == int.class || t == Integer.class) {
			return (int) Math.round(parseQuantity(e, group));
		}
		if (t == long.class || t == Long.class) {
			return Math.round(parseQuantity(e, group));
		}
		if (t == boolean.class || t == Boolean.class) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
				String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
				if (!s.equals("true") && !s.equals("false")) {
					throw AutomationException.badParam("Expected true/false, got '" + e.getAsString() + "'");
				}
				return Boolean.parseBoolean(s);
			}
			return e.getAsBoolean();
		}
		if (t == String.class) {
			return e.isJsonPrimitive() ? e.getAsString() : e.toString();
		}
		if (t == UUID.class) {
			return UUID.fromString(e.getAsString());
		}
		if (t.isEnum() || (t.getEnclosingClass() != null && t.getEnclosingClass().isEnum())) {
			Class<? extends Enum> et = (Class<? extends Enum>) (t.isEnum() ? t : t.getEnclosingClass());
			return parseEnum(et, e.getAsString());
		}
		if (Material.class.isAssignableFrom(t)) {
			return parseMaterial(e, t, current);
		}
		if (t == ClusterConfiguration.class) {
			String want = e.getAsString();
			List<String> names = new ArrayList<>();
			for (ClusterConfiguration cc : ClusterConfiguration.CONFIGURATIONS) {
				if (cc.getXMLName().equalsIgnoreCase(want)) {
					return cc;
				}
				names.add(cc.getXMLName());
			}
			throw AutomationException.badParam("Unknown cluster configuration '" + want + "'. Choices: " + names);
		}
		if (t == ORColor.class) {
			return parseColor(e.getAsString());
		}
		if (CoordinateIF.class.isAssignableFrom(t)) {
			return parseCoordinate(e);
		}
		if (RocketComponent.class.isAssignableFrom(t)) {
			if (resolver == null) {
				throw AutomationException.badParam("Component references are not supported here");
			}
			String ref = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
			return resolver.apply(ref);
		}
		if (ComponentPreset.class.isAssignableFrom(t)) {
			return parsePreset(e);
		}
		if (t == FlightConfigurationId.class) {
			return new FlightConfigurationId(UUID.fromString(e.getAsString()));
		}
		if (t.isArray()) {
			JsonArray a = e.getAsJsonArray();
			Object arr = Array.newInstance(t.getComponentType(), a.size());
			for (int i = 0; i < a.size(); i++) {
				Array.set(arr, i, fromJson(a.get(i), t.getComponentType(), group, null, resolver));
			}
			return arr;
		}
		if (List.class.isAssignableFrom(t) && type instanceof ParameterizedType pt) {
			Type elem = pt.getActualTypeArguments()[0];
			ArrayList list = new ArrayList();
			for (JsonElement x : e.getAsJsonArray()) {
				list.add(fromJson(x, elem, group, null, resolver));
			}
			return list;
		}
		throw AutomationException.badParam("Values of type " + t.getSimpleName() + " cannot be set from JSON");
	}

	public static Class<?> rawClass(Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType pt) {
			return (Class<?>) pt.getRawType();
		}
		return Object.class;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static <E extends Enum> E parseEnum(Class<E> type, String s) {
		String norm = s.trim().replace(' ', '_').replace('-', '_');
		for (E c : type.getEnumConstants()) {
			if (c.name().equalsIgnoreCase(norm)) {
				return c;
			}
		}
		for (E c : type.getEnumConstants()) {
			if (c.toString().equalsIgnoreCase(s.trim())) {
				return c;
			}
		}
		throw AutomationException.badParam("Invalid value '" + s + "' for " + type.getSimpleName()
				+ ". Valid values: " + enumNames(type));
	}

	public static List<String> enumNames(Class<?> type) {
		List<String> names = new ArrayList<>();
		if (type == ClusterConfiguration.class) {
			for (ClusterConfiguration cc : ClusterConfiguration.CONFIGURATIONS) {
				names.add(cc.getXMLName());
			}
			return names;
		}
		Object[] constants = type.isEnum() ? type.getEnumConstants()
				: type.getEnclosingClass() != null ? type.getEnclosingClass().getEnumConstants() : null;
		if (constants != null) {
			for (Object c : constants) {
				names.add(((Enum<?>) c).name());
			}
		}
		return names;
	}

	public static Material parseMaterial(JsonElement e, Class<?> requested, Object current) {
		Material.Type mtype = null;
		if (current instanceof Material m) {
			mtype = m.getType();
		} else if (requested == Material.Bulk.class) {
			mtype = Material.Type.BULK;
		} else if (requested == Material.Surface.class) {
			mtype = Material.Type.SURFACE;
		} else if (requested == Material.Line.class) {
			mtype = Material.Type.LINE;
		}
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			String name = o.get("name").getAsString();
			if (o.has("type")) {
				mtype = parseEnum(Material.Type.class, o.get("type").getAsString());
			}
			if (mtype == null) {
				mtype = Material.Type.BULK;
			}
			if (o.has("density")) {
				double density = parseQuantity(o.get("density"), mtype.getUnitGroup());
				return Databases.findMaterial(mtype, name, density);
			}
			return lookupMaterial(mtype, name);
		}
		return lookupMaterial(mtype, e.getAsString());
	}

	private static Material lookupMaterial(Material.Type mtype, String name) {
		Material.Type[] types = mtype != null ? new Material.Type[] { mtype }
				: new Material.Type[] { Material.Type.BULK, Material.Type.SURFACE, Material.Type.LINE };
		for (Material.Type ty : types) {
			for (Material m : Databases.getDatabase(ty)) {
				if (m.getName().equalsIgnoreCase(name.trim())) {
					return m;
				}
			}
		}
		throw AutomationException.badParam("Unknown material '" + name + "'. Use material.list to see "
				+ "available materials, or pass {\"name\":..., \"density\":...} for a custom one.");
	}

	public static ORColor parseColor(String s) {
		String h = s.trim();
		if (h.startsWith("#")) {
			h = h.substring(1);
		}
		try {
			if (h.length() == 6 || h.length() == 8) {
				int r = Integer.parseInt(h.substring(0, 2), 16);
				int g = Integer.parseInt(h.substring(2, 4), 16);
				int b = Integer.parseInt(h.substring(4, 6), 16);
				int a = h.length() == 8 ? Integer.parseInt(h.substring(6, 8), 16) : 255;
				return new ORColor(r, g, b, a);
			}
		} catch (NumberFormatException ignored) {
		}
		throw AutomationException.badParam("Colors must be given as #rrggbb or #rrggbbaa, got '" + s + "'");
	}

	public static Coordinate parseCoordinate(JsonElement e) {
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			double x = a.size() > 0 ? parseQuantity(a.get(0), UnitGroup.UNITS_LENGTH) : 0;
			double y = a.size() > 1 ? parseQuantity(a.get(1), UnitGroup.UNITS_LENGTH) : 0;
			double z = a.size() > 2 ? parseQuantity(a.get(2), UnitGroup.UNITS_LENGTH) : 0;
			return new Coordinate(x, y, z);
		}
		JsonObject o = e.getAsJsonObject();
		double x = o.has("x") ? parseQuantity(o.get("x"), UnitGroup.UNITS_LENGTH) : 0;
		double y = o.has("y") ? parseQuantity(o.get("y"), UnitGroup.UNITS_LENGTH) : 0;
		double z = o.has("z") ? parseQuantity(o.get("z"), UnitGroup.UNITS_LENGTH) : 0;
		double w = o.has("weight") ? o.get("weight").getAsDouble() : 0;
		return new Coordinate(x, y, z, w);
	}

	public static ComponentPreset parsePreset(JsonElement e) {
		String manufacturer;
		String partNo;
		if (e.isJsonObject()) {
			manufacturer = e.getAsJsonObject().get("manufacturer").getAsString();
			partNo = e.getAsJsonObject().get("partNo").getAsString();
		} else {
			String[] parts = e.getAsString().split("\\|", 2);
			if (parts.length != 2) {
				throw AutomationException.badParam("Presets are given as {\"manufacturer\":..,\"partNo\":..} "
						+ "or \"Manufacturer|PartNo\"");
			}
			manufacturer = parts[0].trim();
			partNo = parts[1].trim();
		}
		List<ComponentPreset> found = Application.getComponentPresetDao().find(manufacturer, partNo);
		if (found.isEmpty()) {
			throw AutomationException.notFound("No preset " + manufacturer + " " + partNo);
		}
		return found.get(0);
	}
}
