package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.BeanAccess;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.Describe;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.Params;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.IgnitionEvent;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurableComponent;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import info.openrocket.core.unit.UnitGroup;

/** Flight configurations: motors, stage activity, recovery deployment and stage separation. */
public final class ConfigCommands {
	private ConfigCommands() {
	}

	private static final String CONFIG = "Flight configuration: id, name, 0-based index, or 'selected' (default)";

	private static FlightConfiguration config(AutomationSession s, OpenRocketDocument doc, Params p) {
		return s.configuration(doc, p.get("config"));
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("config.list").readOnly()
				.description("List flight configurations with their motors and active stages.")
				.doc()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Rocket r = doc.getRocket();
					JsonArray a = new JsonArray();
					FlightConfigurationId sel = r.getSelectedConfiguration().getId();
					for (FlightConfigurationId id : r.getIds()) {
						a.add(Describe.configuration(r.getFlightConfiguration(id), id.equals(sel)));
					}
					return a;
				}).build());

		l.add(Command.builder("config.create")
				.description("Create a new flight configuration (empty, or a copy of an existing one), select it, and "
						+ "by default add a simulation for it (as the GUI does).")
				.doc()
				.param("name", "string", "Name (default: derived from motors)")
				.param("copyFrom", "ref", "Configuration to duplicate")
				.param("addSimulation", "boolean", "Also create a simulation for it (default true)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Rocket rocket = doc.getRocket();
					doc.addUndoPosition("Add configuration");
					FlightConfiguration fc;
					if (p.has("copyFrom")) {
						FlightConfiguration old = s.configuration(doc, p.get("copyFrom"));
						fc = old.copy(null);
						for (RocketComponent c : rocket) {
							if (c instanceof FlightConfigurableComponent fcc) {
								fcc.copyFlightConfiguration(old.getId(), fc.getId());
							}
						}
					} else {
						fc = new FlightConfiguration(rocket, null);
					}
					rocket.setFlightConfiguration(fc.getId(), fc);
					if (p.has("name")) {
						fc.setName(p.string("name"));
					}
					rocket.setSelectedConfiguration(fc.getId());
					JsonObject o = Describe.configuration(fc, true);
					if (p.bool("addSimulation", true)) {
						Simulation sim = new Simulation(doc, rocket);
						sim.setName(doc.getNextSimulationName());
						doc.addSimulation(sim);
						o.addProperty("simulation", sim.getName());
					}
					s.markDirty(doc);
					return o;
				}).build());

		l.add(Command.builder("config.remove")
				.description("Remove a flight configuration together with its simulations.")
				.doc()
				.required("config", "ref", CONFIG)
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					doc.addUndoPosition("Remove configuration");
					doc.removeFlightConfigurationAndSimulations(fc.getId());
					s.markDirty(doc);
					return J.obj("removed", fc.getId());
				}).build());

		l.add(Command.builder("config.select")
				.description("Select the active flight configuration (used by the 3D/2D view, stability and new simulations).")
				.doc()
				.required("config", "ref", CONFIG)
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					doc.getRocket().setSelectedConfiguration(fc.getId());
					return Describe.configuration(fc, true);
				}).build());

		l.add(Command.builder("config.rename")
				.description("Rename a flight configuration. Use '{motors}' / '{manufacturers}' placeholders as in the GUI; "
						+ "an empty name resets to the automatic name.")
				.doc()
				.param("config", "ref", CONFIG)
				.required("name", "string", "New name")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					doc.addUndoPosition("Rename configuration");
					fc.setName(p.string("name"));
					s.markDirty(doc);
					return Describe.configuration(fc, fc == doc.getSelectedConfiguration());
				}).build());

		l.add(Command.builder("config.set_motor")
				.description("Put a motor in a motor mount for a configuration. The mount is made a motor mount if needed. "
						+ "Identify the motor by designation/common name (e.g. \"C6\", \"H128W\") plus optional manufacturer, "
						+ "or by digest from motor.search.")
				.doc()
				.param("config", "ref", CONFIG)
				.required("mount", "string", "Inner tube / body tube that holds the motor")
				.param("motor", "string", "Designation or common name")
				.param("manufacturer", "string", "Manufacturer (to disambiguate)")
				.param("digest", "string", "Exact motor digest")
				.param("delay", "quantity", "Ejection delay in s; \"none\" or \"plugged\" for no ejection charge")
				.param("ignitionEvent", "string", "Ignition event: " + ValueCodec.enumNames(IgnitionEvent.class))
				.param("ignitionDelay", "quantity", "Ignition delay (s)")
				.param("overhang", "quantity", "Motor overhang (m)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					RocketComponent c = s.component(doc, p.string("mount"));
					if (!(c instanceof MotorMount mm)) {
						throw AutomationException.badParam(c.getName() + " (" + c.getClass().getSimpleName()
								+ ") cannot hold a motor; use an InnerTube or BodyTube");
					}
					Motor motor = DatabaseCommands.findMotor(p.string("motor", null), p.string("manufacturer", null),
							p.string("digest", null));
					doc.addUndoPosition("Set motor");
					if (fc.getId().isDefaultId() || !doc.getRocket().containsFlightConfigurationID(fc.getId())) {
						// The rocket has only the placeholder "[No motors]" configuration: create a real one
						fc = new FlightConfiguration(doc.getRocket(), null);
						doc.getRocket().setFlightConfiguration(fc.getId(), fc);
						doc.getRocket().setSelectedConfiguration(fc.getId());
					}
					mm.setMotorMount(true);
					MotorConfiguration mc = new MotorConfiguration(mm, fc.getId());
					mc.setMotor(motor);
					if (p.has("delay")) {
						String d = p.string("delay").trim().toLowerCase();
						mc.setEjectionDelay(d.equals("none") || d.equals("plugged") || d.equals("p")
								? Motor.PLUGGED_DELAY : p.quantity("delay", UnitGroup.UNITS_SHORT_TIME));
					} else {
						double[] delays = motor instanceof info.openrocket.core.motor.ThrustCurveMotor t
								? t.getStandardDelays() : new double[0];
						if (delays.length > 0) {
							mc.setEjectionDelay(delays[0]);
						}
					}
					if (p.has("ignitionEvent")) {
						mc.setIgnitionEvent(ValueCodec.parseEnum(IgnitionEvent.class, p.string("ignitionEvent")));
					}
					if (p.has("ignitionDelay")) {
						mc.setIgnitionDelay(p.quantity("ignitionDelay", UnitGroup.UNITS_SHORT_TIME));
					}
					mm.setMotorConfig(mc, fc.getId());
					if (p.has("overhang")) {
						mm.setMotorOverhang(p.quantity("overhang", UnitGroup.UNITS_LENGTH));
					}
					doc.getRocket().fireComponentChangeEvent(
							info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE);
					s.markDirty(doc);
					return Describe.configuration(fc, fc == doc.getSelectedConfiguration());
				}).build());

		l.add(Command.builder("config.remove_motor")
				.description("Remove the motor from a mount in a configuration.")
				.doc()
				.param("config", "ref", CONFIG)
				.required("mount", "string", "Motor mount")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					RocketComponent c = s.component(doc, p.string("mount"));
					if (!(c instanceof MotorMount mm)) {
						throw AutomationException.badParam(c.getName() + " is not a motor mount");
					}
					doc.addUndoPosition("Remove motor");
					mm.setMotorConfig(null, fc.getId());
					doc.getRocket().fireComponentChangeEvent(
							info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE);
					s.markDirty(doc);
					return Describe.configuration(fc, fc == doc.getSelectedConfiguration());
				}).build());

		l.add(Command.builder("config.set_stages")
				.description("Choose which stages are active (flown) in a configuration.")
				.doc()
				.param("config", "ref", CONFIG)
				.param("active", "array", "Stage numbers (0 = top stage) to activate; others are deactivated")
				.param("all", "boolean", "Activate all stages")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = config(s, doc, p);
					doc.addUndoPosition("Set active stages");
					if (p.bool("all", false)) {
						fc.setAllStages();
					} else {
						fc.clearAllStages();
						for (String n : p.strings("active")) {
							fc._setStageActive(Integer.parseInt(n), true);
						}
					}
					doc.getRocket().fireComponentChangeEvent(
							info.openrocket.core.rocketcomponent.ComponentChangeEvent.TREE_CHANGE);
					s.markDirty(doc);
					return Describe.configuration(fc, fc == doc.getSelectedConfiguration());
				}).build());

		l.add(Command.builder("config.set_deployment")
				.description("Set when a parachute/streamer deploys in a configuration (or for all configurations "
						+ "with config='default').")
				.doc()
				.param("config", "ref", CONFIG + ", or 'default'")
				.required("device", "string", "Recovery device (Parachute/Streamer)")
				.param("event", "string", "Deploy event: " + ValueCodec.enumNames(DeploymentConfiguration.DeployEvent.class))
				.param("delay", "quantity", "Delay after the event (s)")
				.param("altitude", "quantity", "Altitude for the ALTITUDE event (m)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = s.component(doc, p.string("device"));
					if (!(c instanceof RecoveryDevice)) {
						throw AutomationException.badParam(c.getName() + " is not a recovery device");
					}
					doc.addUndoPosition("Set deployment");
					String cfg = p.has("config") ? p.string("config") : "";
					DeploymentConfiguration dc = (DeploymentConfiguration) ObjectCommands.resolve(s, doc,
							"deployment:" + c.getID() + (cfg.isEmpty() ? "" : "@" + cfg));
					if (p.has("event")) {
						dc.setDeployEvent(ValueCodec.parseEnum(DeploymentConfiguration.DeployEvent.class, p.string("event")));
					}
					if (p.has("delay")) {
						dc.setDeployDelay(p.quantity("delay", UnitGroup.UNITS_SHORT_TIME));
					}
					if (p.has("altitude")) {
						dc.setDeployAltitude(p.quantity("altitude", UnitGroup.UNITS_DISTANCE));
					}
					s.markDirty(doc);
					return BeanAccess.describe(dc, false);
				}).build());

		l.add(Command.builder("config.set_separation")
				.description("Set when a stage separates in a configuration (or config='default' for all).")
				.doc()
				.param("config", "ref", CONFIG + ", or 'default'")
				.required("stage", "string", "Stage reference")
				.param("event", "string", "Separation event: "
						+ ValueCodec.enumNames(StageSeparationConfiguration.SeparationEvent.class))
				.param("delay", "quantity", "Delay after the event (s)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = s.component(doc, p.string("stage"));
					if (!(c instanceof AxialStage)) {
						throw AutomationException.badParam(c.getName() + " is not a stage");
					}
					doc.addUndoPosition("Set separation");
					String cfg = p.has("config") ? p.string("config") : "";
					StageSeparationConfiguration sc = (StageSeparationConfiguration) ObjectCommands.resolve(s, doc,
							"separation:" + c.getID() + (cfg.isEmpty() ? "" : "@" + cfg));
					if (p.has("event")) {
						sc.setSeparationEvent(ValueCodec.parseEnum(StageSeparationConfiguration.SeparationEvent.class,
								p.string("event")));
					}
					if (p.has("delay")) {
						sc.setSeparationDelay(p.quantity("delay", UnitGroup.UNITS_SHORT_TIME));
					}
					s.markDirty(doc);
					return BeanAccess.describe(sc, false);
				}).build());

		return l;
	}
}
