package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.database.Databases;
import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.material.Material;
import info.openrocket.core.material.MaterialGroup;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.preset.TypedKey;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;

/** Motor, component preset and material databases. */
public final class DatabaseCommands {
	private DatabaseCommands() {
	}

	static List<ThrustCurveMotor> allMotors() {
		List<ThrustCurveMotor> all = new ArrayList<>();
		for (ThrustCurveMotorSet set : Application.getThrustCurveMotorSetDatabase().getMotorSets()) {
			all.addAll(set.getMotors());
		}
		return all;
	}

	private static String norm(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]", "");
	}

	public static Motor findMotor(String name, String manufacturer, String digest) {
		List<ThrustCurveMotor> all = allMotors();
		if (digest != null) {
			for (ThrustCurveMotor m : all) {
				if (digest.equals(m.getDigest())) {
					return m;
				}
			}
			throw AutomationException.notFound("No motor with digest " + digest);
		}
		if (name == null) {
			throw AutomationException.badParam("Pass 'motor' (designation) or 'digest'");
		}
		String n = norm(name);
		List<ThrustCurveMotor> matches = new ArrayList<>();
		for (ThrustCurveMotor m : all) {
			if (manufacturer != null && !m.getManufacturer().matches(manufacturer)
					&& !norm(m.getManufacturer().getDisplayName()).contains(norm(manufacturer))) {
				continue;
			}
			if (norm(m.getDesignation()).equals(n) || norm(m.getCommonName()).equals(n)) {
				matches.add(m);
			}
		}
		if (matches.isEmpty()) {
			throw AutomationException.notFound("No motor '" + name + "'" + (manufacturer != null ? " by " + manufacturer : "")
					+ ". Use motor.search.");
		}
		// Prefer available motors, then the one with the most data
		matches.sort(Comparator.comparing((ThrustCurveMotor m) -> !m.isAvailable())
				.thenComparing(m -> -m.getTimePoints().length));
		return matches.get(0);
	}

	static JsonObject motorJson(ThrustCurveMotor m, boolean curve) {
		JsonObject o = J.obj(
				"manufacturer", m.getManufacturer().getDisplayName(),
				"designation", m.getDesignation(),
				"commonName", m.getCommonName(),
				"type", m.getMotorType(),
				"diameter", m.getDiameter(),
				"length", m.getLength(),
				"totalImpulse", m.getTotalImpulseEstimate(),
				"averageThrust", m.getAverageThrustEstimate(),
				"maxThrust", m.getMaxThrustEstimate(),
				"burnTime", m.getBurnTimeEstimate(),
				"launchMass", m.getLaunchMass(),
				"burnoutMass", m.getBurnoutMass(),
				"delays", m.getStandardDelays(),
				"available", m.isAvailable(),
				"caseInfo", m.getCaseInfo(),
				"propellant", m.getPropellantInfo(),
				"digest", m.getDigest());
		if (curve) {
			o.add("time", ValueCodec.toJson(m.getTimePoints()));
			o.add("thrust", ValueCodec.toJson(m.getThrustPoints()));
			o.addProperty("description", m.getDescription());
		}
		return o;
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("motor.search").readOnly()
				.description("Search the motor database. All filters are optional and combined; results are sorted by total impulse.")
				.param("query", "string", "Substring of designation / common name / manufacturer")
				.param("manufacturer", "string", "Manufacturer (e.g. Estes, Aerotech, Cesaroni)")
				.param("impulseClass", "string", "Impulse class letter(s), e.g. \"C\" or \"G-H\"")
				.param("diameter", "quantity", "Motor diameter (e.g. \"18 mm\", 0.029)")
				.param("type", "string", "SINGLE, RELOAD or HYBRID")
				.param("availableOnly", "boolean", "Skip out-of-production motors (default false)")
				.param("limit", "integer", "Max results (default 50)")
				.anyThread()
				.handler((p, s) -> {
					String q = norm(p.string("query", null));
					String man = p.string("manufacturer", null);
					String cls = p.string("impulseClass", null);
					double dia = p.has("diameter") ? p.quantity("diameter", UnitGroup.UNITS_MOTOR_DIMENSIONS) : Double.NaN;
					Motor.Type type = p.has("type") ? ValueCodec.parseEnum(Motor.Type.class, p.string("type")) : null;
					boolean avail = p.bool("availableOnly", false);
					int limit = p.integer("limit", 50);
					char lo = 0;
					char hi = 0;
					if (cls != null) {
						String c = cls.toUpperCase(Locale.ROOT).replace(" ", "");
						lo = c.charAt(0);
						hi = c.contains("-") ? c.charAt(c.length() - 1) : lo;
					}
					List<ThrustCurveMotor> out = new ArrayList<>();
					for (ThrustCurveMotor m : allMotors()) {
						if (!q.isEmpty() && !(norm(m.getDesignation()).contains(q) || norm(m.getCommonName()).contains(q)
								|| norm(m.getManufacturer().getDisplayName() + m.getCommonName()).contains(q))) {
							continue;
						}
						if (man != null && !m.getManufacturer().matches(man)
								&& !norm(m.getManufacturer().getDisplayName()).contains(norm(man))) {
							continue;
						}
						if (cls != null) {
							String cn = m.getCommonName().toUpperCase(Locale.ROOT);
							char k = cn.isEmpty() ? '?' : cn.charAt(0);
							if (k < lo || k > hi) {
								continue;
							}
						}
						if (!Double.isNaN(dia) && Math.abs(m.getDiameter() - dia) > 0.0015) {
							continue;
						}
						if (type != null && m.getMotorType() != type) {
							continue;
						}
						if (avail && !m.isAvailable()) {
							continue;
						}
						out.add(m);
					}
					out.sort(Comparator.comparingDouble(ThrustCurveMotor::getTotalImpulseEstimate));
					JsonArray a = new JsonArray();
					out.stream().limit(limit).forEach(m -> a.add(motorJson(m, false)));
					return J.obj("total", out.size(), "motors", a);
				}).build());

		l.add(Command.builder("motor.get").readOnly()
				.description("Full details of one motor including its thrust curve (time s, thrust N).")
				.param("motor", "string", "Designation or common name")
				.param("manufacturer", "string", "Manufacturer")
				.param("digest", "string", "Exact digest")
				.anyThread()
				.handler((p, s) -> motorJson((ThrustCurveMotor) findMotor(p.string("motor", null),
						p.string("manufacturer", null), p.string("digest", null)), true))
				.build());

		l.add(Command.builder("preset.search").readOnly()
				.description("Search manufacturer component presets (body tubes, nose cones, parachutes, ...). Apply one with component.preset.")
				.param("type", "string", "Preset type: " + ValueCodec.enumNames(ComponentPreset.Type.class))
				.param("query", "string", "Substring of manufacturer / part number / description")
				.param("limit", "integer", "Max results (default 50)")
				.anyThread()
				.handler((p, s) -> {
					List<ComponentPreset> list = p.has("type")
							? Application.getComponentPresetDao().listForType(
									ValueCodec.parseEnum(ComponentPreset.Type.class, p.string("type")))
							: Application.getComponentPresetDao().listAll();
					String q = p.string("query", "").toLowerCase(Locale.ROOT);
					int limit = p.integer("limit", 50);
					JsonArray a = new JsonArray();
					int total = 0;
					for (ComponentPreset cp : list) {
						String hay = (cp.getManufacturer().getDisplayName() + " " + cp.getPartNo() + " "
								+ (cp.has(ComponentPreset.DESCRIPTION) ? cp.get(ComponentPreset.DESCRIPTION) : ""))
								.toLowerCase(Locale.ROOT);
						if (!q.isEmpty() && !hay.contains(q)) {
							continue;
						}
						total++;
						if (a.size() >= limit) {
							continue;
						}
						JsonObject o = new JsonObject();
						for (TypedKey<?> k : cp.getType().getDisplayedColumns()) {
							if (cp.has(k)) {
								o.add(k.getName(), ValueCodec.toJson(cp.get(k)));
							}
						}
						o.addProperty("type", cp.getType().name());
						a.add(o);
					}
					return J.obj("total", total, "presets", a);
				}).build());

		l.add(Command.builder("material.list").readOnly()
				.description("List materials (density in SI: kg/m³ bulk, kg/m² surface, kg/m line).")
				.param("type", "string", "BULK, SURFACE or LINE (default all)")
				.anyThread()
				.handler((p, s) -> {
					JsonArray a = new JsonArray();
					for (Material.Type t : new Material.Type[] { Material.Type.BULK, Material.Type.SURFACE, Material.Type.LINE }) {
						if (p.has("type") && !t.name().equalsIgnoreCase(p.string("type"))) {
							continue;
						}
						for (Material m : Databases.getDatabase(t)) {
							a.add(ValueCodec.toJson(m));
						}
					}
					return a;
				}).build());

		l.add(Command.builder("material.add")
				.description("Add a user-defined material to the material database.")
				.required("name", "string", "Material name")
				.required("type", "string", "BULK, SURFACE or LINE")
				.required("density", "quantity", "Density (SI or with unit, e.g. \"1.2 g/cm³\")")
				.anyThread()
				.handler((p, s) -> {
					Material.Type t = ValueCodec.parseEnum(Material.Type.class, p.string("type"));
					double density = p.quantity("density", t.getUnitGroup());
					Material m = Material.newMaterial(t, p.string("name"), density, MaterialGroup.OTHER, true);
					Databases.addMaterial(m);
					return ValueCodec.toJson(m);
				}).build());

		return l;
	}
}
