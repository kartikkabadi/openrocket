package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.aerodynamics.AerodynamicCalculator;
import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.Describe;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.Params;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.CMAnalysisEntry;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.util.MathUtil;

/** Stability, mass and aerodynamic analysis of a design (the numbers shown in the design view and component analysis dialog). */
public final class AnalysisCommands {
	private AnalysisCommands() {
	}

	private static Command.Builder conditions(Command.Builder b) {
		return b.doc()
				.param("config", "ref", "Flight configuration (default: selected)")
				.param("mach", "number", "Mach number (default: preference, usually 0.3)")
				.param("aoa", "quantity", "Angle of attack (default 0)")
				.param("theta", "quantity", "Wind direction; omit to use the worst-case direction like the GUI")
				.param("rollRate", "quantity", "Roll rate (rad/s, default 0)");
	}

	private static FlightConditions conditions(FlightConfiguration fc, Params p) {
		FlightConditions c = new FlightConditions(fc);
		c.setMach(p.number("mach", Application.getPreferences().getDefaultMach()));
		c.setAOA(p.quantity("aoa", UnitGroup.UNITS_ANGLE, 0));
		if (p.has("theta")) {
			c.setTheta(p.quantity("theta", UnitGroup.UNITS_ANGLE));
		}
		c.setRollRate(p.number("rollRate", 0));
		return c;
	}

	static JsonObject stability(FlightConfiguration fc, FlightConditions cond, boolean worst) {
		AerodynamicCalculator calc = new BarrowmanCalculator();
		WarningSet warnings = new WarningSet();
		calc.checkGeometry(fc, fc.getRocket(), warnings);
		CoordinateIF cp = worst ? calc.getWorstCP(fc, cond, warnings) : calc.getCP(fc, cond, warnings);
		RigidBody launch = MassCalculator.calculateLaunch(fc);
		RigidBody structure = MassCalculator.calculateStructure(fc);
		RigidBody burnout = MassCalculator.calculateBurnout(fc);
		CoordinateIF cg = launch.getCM();
		double length = fc.getLength();
		double diameter = Double.NaN;
		for (RocketComponent c : fc.getCoreComponents()) {
			if (c instanceof SymmetricComponent sc) {
				diameter = MathUtil.max(diameter, sc.getForeRadius() * 2, sc.getAftRadius() * 2);
			}
		}
		double refLength = fc.getReferenceLength();
		double margin = (cp.getX() - cg.getX()) / refLength;
		AerodynamicForces forces = calc.getAerodynamicForces(fc, cond, new WarningSet());
		JsonObject o = J.obj(
				"configuration", fc.getName(),
				"cg", cg.getX(),
				"cp", cp.getWeight() > MathUtil.EPSILON ? cp.getX() : Double.NaN,
				"cnAlpha", cp.getWeight(),
				"stabilityMarginCalibers", margin,
				"stabilityMarginPercentOfLength", length > 0 ? (cp.getX() - cg.getX()) / length * 100 : Double.NaN,
				"referenceLength", refLength,
				"length", length,
				"maxDiameter", diameter,
				"massWithMotors", launch.getMass(),
				"massWithoutMotors", structure.getMass(),
				"burnoutMass", burnout.getMass(),
				"cgWithoutMotors", structure.getCM().getX(),
				"cgBurnout", burnout.getCM().getX(),
				"cd", forces.getCD(),
				"mach", cond.getMach(),
				"units", "SI (m, kg); cg/cp measured from the nose tip");
		o.add("warnings", Describe.warnings(warnings));
		return o;
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(conditions(Command.builder("analysis.stability"))
				.description("Stability of the design: CG, CP, stability margin (calibers), CNα, mass with/without "
						+ "motors, length, max diameter, total CD and design warnings — the figures the GUI shows "
						+ "under the rocket drawing.")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					return stability(fc, conditions(fc, p), !p.has("theta"));
				}).build());

		l.add(Command.builder("analysis.mass")
				.description("Mass and CG breakdown per component (and motors) for a configuration.")
				.doc()
				.param("config", "ref", "Flight configuration (default: selected)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					Map<Integer, CMAnalysisEntry> map = MassCalculator.getCMAnalysis(fc);
					JsonArray a = new JsonArray();
					for (CMAnalysisEntry e : map.values()) {
						JsonObject o = J.obj("name", e.name, "mass", e.eachMass, "cg", e.totalCM.getX(),
								"totalMass", e.totalCM.getWeight());
						if (e.source instanceof RocketComponent c) {
							o.add("component", Describe.componentRef(c));
						} else if (e.source != null) {
							o.addProperty("motor", e.name);
						}
						a.add(o);
					}
					RigidBody launch = MassCalculator.calculateLaunch(fc);
					return J.obj("totalMass", launch.getMass(), "cg", launch.getCM().getX(),
							"longitudinalInertia", launch.getLongitudinalInertia(), "rotationalInertia", launch.getIxx(),
							"components", a);
				}).build());

		l.add(conditions(Command.builder("analysis.aero"))
				.description("Aerodynamic breakdown per component: CP, CNα, pressure/base/friction/total CD, roll "
						+ "forcing and damping (the component analysis dialog).")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					FlightConditions cond = conditions(fc, p);
					AerodynamicCalculator calc = new BarrowmanCalculator();
					WarningSet w = new WarningSet();
					Map<RocketComponent, AerodynamicForces> map = calc.getForceAnalysis(fc, cond, w);
					JsonArray a = new JsonArray();
					for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
						AerodynamicForces f = e.getValue();
						JsonObject o = J.obj(
								"cp", f.getCP() == null ? Double.NaN : f.getCP().getX(),
								"cnAlpha", f.getCP() == null ? Double.NaN : f.getCP().getWeight(),
								"pressureCD", f.getPressureCD(), "baseCD", f.getBaseCD(), "frictionCD", f.getFrictionCD(),
								"cd", f.getCD(), "cdTotal", f.getCDTotal(), "rollForcing", f.getCrollForce(),
								"rollDamping", f.getCrollDamp());
						if (e.getKey() != null) {
							o.add("component", Describe.componentRef(e.getKey()));
						}
						a.add(o);
					}
					return J.obj("mach", cond.getMach(), "aoa", cond.getAOA(), "components", a, "warnings",
							Describe.warnings(w));
				}).build());

		l.add(conditions(Command.builder("analysis.sweep"))
				.description("Sweep a flight condition and report total CD, CP, CNα and stability margin at each value.")
				.required("parameter", "string", "mach, aoa, theta or rollRate")
				.required("min", "quantity", "Start value")
				.required("max", "quantity", "End value")
				.param("step", "quantity", "Step (default: (max-min)/20)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					String param = p.string("parameter");
					UnitGroup g = param.equals("mach") || param.equals("rollRate") ? null : UnitGroup.UNITS_ANGLE;
					double min = p.quantity("min", g);
					double max = p.quantity("max", g);
					double step = p.has("step") ? p.quantity("step", g) : (max - min) / 20;
					if (step <= 0 || max < min) {
						throw AutomationException.badParam("Need min <= max and a positive step");
					}
					AerodynamicCalculator calc = new BarrowmanCalculator();
					CoordinateIF cg = MassCalculator.calculateLaunch(fc).getCM();
					JsonArray rows = new JsonArray();
					for (double v = min; v <= max + step * 1e-9; v += step) {
						FlightConditions c = conditions(fc, p);
						switch (param) {
							case "mach" -> c.setMach(v);
							case "aoa" -> c.setAOA(v);
							case "theta" -> c.setTheta(v);
							case "rollRate" -> c.setRollRate(v);
							default -> throw AutomationException.badParam("parameter must be mach, aoa, theta or rollRate");
						}
						WarningSet w = new WarningSet();
						CoordinateIF cp = calc.getCP(fc, c, w);
						AerodynamicForces f = calc.getAerodynamicForces(fc, c, w);
						rows.add(J.obj(param, v, "cd", f.getCD(), "cp", cp.getX(), "cnAlpha", cp.getWeight(),
								"stabilityMarginCalibers", (cp.getX() - cg.getX()) / fc.getReferenceLength()));
					}
					return J.obj("parameter", param, "cg", cg.getX(), "rows", rows);
				}).build());

		return l;
	}
}
