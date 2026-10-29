package info.openrocket.core.automation;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Message;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;

/** JSON views of common OpenRocket objects. */
public final class Describe {
	private Describe() {
	}

	public static JsonObject document(AutomationSession s, OpenRocketDocument doc) {
		JsonObject o = new JsonObject();
		o.addProperty("id", s.idOf(doc));
		o.addProperty("name", doc.getRocket().getName());
		o.addProperty("file", doc.getFile() == null ? null : doc.getFile().getAbsolutePath());
		o.addProperty("saved", doc.isSaved());
		o.addProperty("active", doc == s.getHost().active());
		o.addProperty("components", count(doc.getRocket()));
		o.addProperty("stages", doc.getRocket().getStageCount());
		o.addProperty("flightConfigurations", doc.getRocket().getConfigurationCount());
		o.addProperty("simulations", doc.getSimulationCount());
		o.add("selectedConfiguration", ValueCodec.toJson(doc.getSelectedConfiguration()));
		o.addProperty("undo", doc.isUndoAvailable() ? doc.getUndoDescription() : null);
		o.addProperty("redo", doc.isRedoAvailable() ? doc.getRedoDescription() : null);
		return o;
	}

	private static int count(RocketComponent root) {
		int n = 0;
		for (RocketComponent ignored : root) {
			n++;
		}
		return n;
	}

	public static JsonObject componentRef(RocketComponent c) {
		JsonObject o = new JsonObject();
		o.addProperty("id", c.getID().toString());
		o.addProperty("name", c.getName());
		o.addProperty("type", c.getClass().getSimpleName());
		return o;
	}

	public static JsonObject tree(RocketComponent c, int depth, boolean props) {
		JsonObject o = componentRef(c);
		if (props) {
			o.add("properties", BeanAccess.describe(c, false));
		} else {
			JsonObject summary = new JsonObject();
			for (String key : List.of("length", "outerRadius", "foreRadius", "aftRadius", "finCount", "massOverridden",
					"motorMount", "axialOffset", "axialMethod", "material", "componentMass")) {
				try {
					Object v = BeanAccess.get(c, key);
					summary.add(key, ValueCodec.toJson(v, 1));
				} catch (RuntimeException ignored) {
				}
			}
			o.add("summary", summary);
		}
		if (c.getChildCount() > 0) {
			if (depth != 0) {
				JsonArray ch = new JsonArray();
				for (RocketComponent child : c.getChildren()) {
					ch.add(tree(child, depth - 1, props));
				}
				o.add("children", ch);
			} else {
				o.addProperty("childCount", c.getChildCount());
			}
		}
		return o;
	}

	public static JsonObject configuration(FlightConfiguration fc, boolean selected) {
		JsonObject o = new JsonObject();
		o.addProperty("id", fc.getId().toString());
		o.addProperty("name", fc.getName());
		o.addProperty("nameOverridden", fc.isNameOverridden());
		o.addProperty("selected", selected);
		JsonArray stages = new JsonArray();
		for (var stage : fc.getRocket().getStageList()) {
			JsonObject st = new JsonObject();
			st.addProperty("number", stage.getStageNumber());
			st.addProperty("name", stage.getName());
			st.addProperty("active", fc.isStageActive(stage.getStageNumber()));
			stages.add(st);
		}
		o.add("stages", stages);
		JsonArray motors = new JsonArray();
		for (RocketComponent c : fc.getRocket()) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				MotorConfiguration mc = mm.getMotorConfig(fc.getId());
				JsonObject m = new JsonObject();
				m.add("mount", componentRef(c));
				if (mc != null && mc.hasMotor()) {
					m.add("motor", ValueCodec.toJson(mc.getMotor()));
					m.add("ejectionDelay", ValueCodec.number(mc.getEjectionDelay()));
					m.addProperty("ignitionEvent", mc.getIgnitionEvent() == null ? null : mc.getIgnitionEvent().name());
					m.add("ignitionDelay", ValueCodec.number(mc.getIgnitionDelay()));
					m.addProperty("count", mc.getMotorCount());
				}
				motors.add(m);
			}
		}
		o.add("motors", motors);
		return o;
	}

	public static JsonObject simulation(OpenRocketDocument doc, Simulation sim, boolean includeOptions) {
		JsonObject o = new JsonObject();
		o.addProperty("index", doc.getSimulationIndex(sim));
		o.addProperty("name", sim.getName());
		o.addProperty("status", sim.getStatus().name());
		o.addProperty("configurationId", sim.getFlightConfigurationId().toString());
		FlightConfiguration fc = sim.getActiveConfiguration();
		o.addProperty("configuration", fc == null ? null : fc.getName());
		if (sim.hasSummaryData()) {
			o.add("summary", summary(sim.getSimulatedData()));
		}
		if (sim.getSimulatedWarnings() != null) {
			o.add("warnings", warnings(sim.getSimulatedWarnings()));
		}
		if (includeOptions) {
			o.add("options", BeanAccess.describe(sim.getOptions(), false));
			JsonArray ext = new JsonArray();
			sim.getSimulationExtensions().forEach(e -> ext.add(J.obj("id", e.getId(), "name", e.getName())));
			o.add("extensions", ext);
		}
		return o;
	}

	public static JsonObject summary(FlightData d) {
		return J.obj(
				"maxAltitude", d.getMaxAltitude(),
				"maxVelocity", d.getMaxVelocity(),
				"maxAcceleration", d.getMaxAcceleration(),
				"maxMachNumber", d.getMaxMachNumber(),
				"timeToApogee", d.getTimeToApogee(),
				"flightTime", d.getFlightTime(),
				"groundHitVelocity", d.getGroundHitVelocity(),
				"launchRodVelocity", d.getLaunchRodVelocity(),
				"deploymentVelocity", d.getDeploymentVelocity(),
				"optimumDelay", d.getOptimumDelay(),
				"branches", d.getBranchCount(),
				"units", "SI (m, m/s, m/s², s)");
	}

	public static JsonArray warnings(WarningSet w) {
		JsonArray a = new JsonArray();
		for (Message m : w) {
			a.add(J.obj("priority", m.getPriority(), "message", m.toString()));
		}
		return a;
	}
}
