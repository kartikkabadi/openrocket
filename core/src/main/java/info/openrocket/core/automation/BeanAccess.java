package info.openrocket.core.automation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.unit.UnitGroup;

/**
 * Reflection-based access to the bean properties (getX/isX + setX) and public methods of any
 * OpenRocket object. This is what makes every component, simulation option, preference and
 * configuration field reachable without a hand-written command for each one.
 */
public final class BeanAccess {

	public static final class Prop {
		final String name;
		final Method getter;
		final List<Method> setters = new ArrayList<>();

		Prop(String name, Method getter) {
			this.name = name;
			this.getter = getter;
		}

		public String getName() {
			return name;
		}

		public Class<?> getType() {
			if (getter != null) {
				return getter.getReturnType();
			}
			return setters.get(0).getParameterTypes()[0];
		}

		public boolean isReadable() {
			return getter != null;
		}

		public boolean isWritable() {
			return !setters.isEmpty();
		}
	}

	private static final Set<String> EXCLUDED = Set.of("class", "parent", "root", "children", "allChildren",
			"listeners", "configListeners", "componentChangeListeners", "changeListeners", "document",
			"modID", "massModID", "aerodynamicModID", "treeModID", "functionalModID", "stateChangeListener",
			"injector", "listenerList", "eventsEnabled", "debugName", "simulationListeners");

	private static final Map<Class<?>, Map<String, Prop>> CACHE = new ConcurrentHashMap<>();

	private BeanAccess() {
	}

	public static Map<String, Prop> properties(Class<?> cls) {
		return CACHE.computeIfAbsent(cls, BeanAccess::scan);
	}

	private static Map<String, Prop> scan(Class<?> cls) {
		Map<String, Prop> props = new LinkedHashMap<>();
		Method[] methods = cls.getMethods();
		java.util.Arrays.sort(methods, Comparator.comparing(Method::getName));
		for (Method m : methods) {
			if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
					|| m.getReturnType() == void.class) {
				continue;
			}
			String n = propertyName(m.getName(), m.getReturnType());
			if (n == null || EXCLUDED.contains(n)) {
				continue;
			}
			props.putIfAbsent(n, new Prop(n, accessible(m)));
		}
		for (Method m : methods) {
			if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || !m.getName().startsWith("set")
					|| m.getName().length() < 4) {
				continue;
			}
			String n = decapitalize(m.getName().substring(3));
			if (EXCLUDED.contains(n)) {
				continue;
			}
			Prop p = props.get(n);
			if (p == null) {
				p = new Prop(n, null);
				props.put(n, p);
			}
			p.setters.add(accessible(m));
		}
		// Prefer the setter whose parameter type matches the getter
		for (Prop p : props.values()) {
			if (p.getter != null && p.setters.size() > 1) {
				Class<?> rt = p.getter.getReturnType();
				p.setters.sort(Comparator.comparingInt(s -> s.getParameterTypes()[0] == rt ? 0 : 1));
			}
		}
		return props;
	}

	/**
	 * Public methods of non-public classes (e.g. anonymous enum bodies) are only callable through
	 * the declaring public interface/superclass method.
	 */
	private static Method accessible(Method m) {
		if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
			return m;
		}
		for (Class<?> c = m.getDeclaringClass(); c != null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				try {
					return accessible(i.getMethod(m.getName(), m.getParameterTypes()));
				} catch (NoSuchMethodException ignored) {
				}
			}
			if (c.getSuperclass() != null && Modifier.isPublic(c.getSuperclass().getModifiers())) {
				try {
					return c.getSuperclass().getMethod(m.getName(), m.getParameterTypes());
				} catch (NoSuchMethodException ignored) {
				}
			}
		}
		return m;
	}

	private static String propertyName(String method, Class<?> rt) {
		if (method.startsWith("get") && method.length() > 3) {
			return decapitalize(method.substring(3));
		}
		if (method.startsWith("is") && method.length() > 2 && (rt == boolean.class || rt == Boolean.class)) {
			return decapitalize(method.substring(2));
		}
		return null;
	}

	private static String decapitalize(String s) {
		if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) {
			return s; // e.g. "ID", "CGX"
		}
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	private static Prop find(Object obj, String name) {
		Map<String, Prop> props = properties(obj.getClass());
		Prop p = props.get(name);
		if (p == null) {
			for (Prop q : props.values()) {
				if (q.name.equalsIgnoreCase(name)) {
					return q;
				}
			}
			throw AutomationException.badParam("No property '" + name + "' on " + obj.getClass().getSimpleName()
					+ ". component.get / object.get list the available properties.");
		}
		return p;
	}

	public static Object get(Object obj, String name) {
		Prop p = find(obj, name);
		if (p.getter == null) {
			throw AutomationException.badParam("Property '" + name + "' is write-only");
		}
		return invoke(p.getter, obj);
	}

	/**
	 * Set a property from JSON.
	 */
	public static void set(Object obj, String name, JsonElement value, Function<String, RocketComponent> resolver) {
		Prop p = find(obj, name);
		if (p.setters.isEmpty()) {
			throw AutomationException.badParam("Property '" + p.name + "' of " + obj.getClass().getSimpleName()
					+ " is read-only");
		}
		Object current = null;
		if (p.getter != null) {
			try {
				current = p.getter.invoke(obj);
			} catch (Exception ignored) {
			}
		}
		UnitGroup group = unitHint(p.name);
		AutomationException last = null;
		for (Method setter : p.setters) {
			Type pt = setter.getGenericParameterTypes()[0];
			Object v;
			try {
				v = ValueCodec.fromJson(value, pt, group, current, resolver);
			} catch (AutomationException e) {
				last = e;
				continue;
			} catch (RuntimeException e) {
				last = AutomationException.badParam("Cannot convert " + value + " to "
						+ setter.getParameterTypes()[0].getSimpleName() + ": " + e.getMessage());
				continue;
			}
			invoke(setter, obj, v);
			return;
		}
		throw last != null ? last : AutomationException.badParam("Cannot set " + p.name);
	}

	/**
	 * Describe all simple-valued properties of an object.
	 *
	 * @param full include type information (otherwise just name → value)
	 */
	public static JsonObject describe(Object obj, boolean full) {
		JsonObject out = new JsonObject();
		for (Prop p : properties(obj.getClass()).values()) {
			Class<?> t = p.getType();
			if (!ValueCodec.isSimpleType(t)) {
				continue;
			}
			JsonElement value = null;
			if (p.getter != null) {
				try {
					value = ValueCodec.toJson(p.getter.invoke(obj), 2);
				} catch (Exception e) {
					continue;
				}
			}
			if (!full) {
				if (value != null) {
					out.add(p.name, value);
				}
				continue;
			}
			JsonObject d = new JsonObject();
			if (value != null) {
				d.add("value", value);
			}
			d.addProperty("type", typeName(t));
			d.addProperty("writable", p.isWritable());
			List<String> enums = ValueCodec.enumNames(t);
			if (!enums.isEmpty()) {
				JsonArray a = new JsonArray();
				enums.forEach(a::add);
				d.add("choices", a);
			}
			UnitGroup g = unitHint(p.name);
			if (g != null && (t == double.class || t == Double.class)) {
				d.addProperty("siUnit", g.getSIUnit().getUnit());
			}
			out.add(p.name, d);
		}
		return out;
	}

	public static String typeName(Class<?> t) {
		if (t == double.class || t == Double.class || t == float.class || t == Float.class) {
			return "number";
		}
		if (t == int.class || t == Integer.class || t == long.class || t == Long.class) {
			return "integer";
		}
		if (t == boolean.class || t == Boolean.class) {
			return "boolean";
		}
		if (t == String.class) {
			return "string";
		}
		if (t.isEnum() || (t.getEnclosingClass() != null && t.getEnclosingClass().isEnum())) {
			return "enum";
		}
		return t.getSimpleName();
	}

	/**
	 * Guess the unit group of a numeric property from its name, so that strings such as "3 in" are
	 * interpreted correctly. Bare numbers are always taken as SI regardless.
	 */
	public static UnitGroup unitHint(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.contains("mass")) {
			return UnitGroup.UNITS_MASS;
		}
		if (n.contains("angle") || n.contains("cant") || n.contains("sweep") || n.contains("rotation")
				|| n.contains("theta") || n.contains("direction") || n.contains("tilt") || n.contains("latitude")
				|| n.contains("longitude")) {
			return UnitGroup.UNITS_ANGLE;
		}
		if (n.contains("velocity") || n.contains("speed")) {
			return UnitGroup.UNITS_VELOCITY;
		}
		if (n.contains("temperature")) {
			return UnitGroup.UNITS_TEMPERATURE;
		}
		if (n.contains("pressure")) {
			return UnitGroup.UNITS_PRESSURE;
		}
		if (n.contains("delay") || n.contains("time") || n.contains("duration")) {
			return UnitGroup.UNITS_SHORT_TIME;
		}
		if (n.contains("length") || n.contains("radius") || n.contains("diameter") || n.contains("thickness")
				|| n.contains("offset") || n.contains("height") || n.contains("width") || n.contains("span")
				|| n.contains("chord") || n.contains("overhang") || n.contains("altitude") || n.contains("spacing")
				|| n.contains("separation") || n.contains("depth") || n.contains("cgx") || n.contains("position")
				|| n.contains("distance")) {
			return UnitGroup.UNITS_LENGTH;
		}
		return null;
	}

	// ------------------------------------------------------------------ methods

	/** List public instance methods (signatures) of an object's class. */
	public static JsonArray methods(Object obj, String filter) {
		JsonArray a = new JsonArray();
		List<String> sigs = new ArrayList<>();
		for (Method m : obj.getClass().getMethods()) {
			if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
				continue;
			}
			StringBuilder sb = new StringBuilder();
			sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
			Class<?>[] pts = m.getParameterTypes();
			for (int i = 0; i < pts.length; i++) {
				sb.append(i > 0 ? ", " : "").append(pts[i].getSimpleName());
			}
			sb.append(')');
			String s = sb.toString();
			if (filter == null || m.getName().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
				sigs.add(s);
			}
		}
		sigs.stream().distinct().sorted(Comparator.comparing(x -> x.substring(x.indexOf(' ') + 1)))
				.forEach(a::add);
		return a;
	}

	/**
	 * Invoke a public method by name, converting JSON arguments to the parameter types of the first
	 * overload that accepts them.
	 */
	public static Object call(Object obj, String method, JsonArray args, Function<String, RocketComponent> resolver) {
		AutomationException last = null;
		List<Method> candidates = new ArrayList<>();
		for (Method m : obj.getClass().getMethods()) {
			if (m.getName().equals(method) && m.getParameterCount() == args.size()
					&& !Modifier.isStatic(m.getModifiers())) {
				candidates.add(accessible(m));
			}
		}
		if (candidates.isEmpty()) {
			throw AutomationException.badParam("No public method " + method + " taking " + args.size()
					+ " argument(s) on " + obj.getClass().getSimpleName());
		}
		for (Method m : candidates) {
			Type[] types = m.getGenericParameterTypes();
			Object[] converted = new Object[types.length];
			try {
				for (int i = 0; i < types.length; i++) {
					converted[i] = ValueCodec.fromJson(args.get(i), types[i], null, null, resolver);
				}
			} catch (RuntimeException e) {
				last = e instanceof AutomationException ae ? ae : AutomationException.badParam(e.getMessage());
				continue;
			}
			return invoke(m, obj, converted);
		}
		throw last;
	}

	private static Object invoke(Method m, Object obj, Object... args) {
		try {
			return m.invoke(obj, args);
		} catch (InvocationTargetException e) {
			Throwable c = e.getCause();
			if (c instanceof AutomationException ae) {
				throw ae;
			}
			throw new AutomationException(m.getName() + " failed: " + c, c);
		} catch (IllegalAccessException e) {
			throw new AutomationException("Cannot access " + m, e);
		}
	}
}
