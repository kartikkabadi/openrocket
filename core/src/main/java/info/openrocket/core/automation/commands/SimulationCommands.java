package info.openrocket.core.automation.commands;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.Describe;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.Params;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.CSVExport;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.extension.SimulationExtension;
import info.openrocket.core.simulation.extension.SimulationExtensionProvider;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;

/** Creating, configuring and running simulations, and reading their results. */
public final class SimulationCommands {
	private SimulationCommands() {
	}

	private static final String SIM = "Simulation: 0-based index or name";

	static Simulation sim(AutomationSession s, OpenRocketDocument doc, Params p) {
		return s.simulation(doc, p.get("sim"));
	}

	static FlightDataBranch branch(Simulation sim, Params p) {
		FlightData d = sim.getSimulatedData();
		if (d == null || d.getBranchCount() == 0) {
			throw AutomationException.badParam("Simulation '" + sim.getName() + "' has no data; run sim.run first");
		}
		int b = p.integer("branch", 0);
		if (b < 0 || b >= d.getBranchCount()) {
			throw AutomationException.badParam("Branch " + b + " out of range (0.." + (d.getBranchCount() - 1) + ")");
		}
		return d.getBranch(b);
	}

	static FlightDataType dataType(FlightDataBranch branch, String name) {
		String n = name.trim();
		for (FlightDataType t : branch.getTypes()) {
			if (t.getSaveKey() != null && t.getSaveKey().equalsIgnoreCase(n)) {
				return t;
			}
		}
		for (FlightDataType t : branch.getTypes()) {
			if (t.getName().equalsIgnoreCase(n) || t.getSymbol().equals(n)) {
				return t;
			}
		}
		String nn = n.toLowerCase(Locale.ROOT).replace("_", " ");
		for (FlightDataType t : branch.getTypes()) {
			if (t.getName().toLowerCase(Locale.ROOT).contains(nn)) {
				return t;
			}
		}
		throw AutomationException.badParam("Unknown data type '" + name + "'. Use sim.data_types.");
	}

	static JsonObject typeJson(FlightDataType t) {
		Unit si = t.getUnitGroup().getSIUnit();
		return J.obj("key", t.getSaveKey(), "name", t.getName(), "symbol", t.getSymbol(),
				"unit", si == null ? "" : si.getUnit(), "group", t.getGroup() == null ? null : t.getGroup().getName());
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("sim.list").readOnly()
				.description("List simulations with status, configuration and (if run) summary results in SI units.")
				.doc()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					JsonArray a = new JsonArray();
					for (Simulation sim : doc.getSimulations()) {
						a.add(Describe.simulation(doc, sim, false));
					}
					return a;
				}).build());

		l.add(Command.builder("sim.get").readOnly()
				.description("One simulation: status, summary, warnings, all launch/simulation options and extensions. "
						+ "Change options with sim.set.")
				.doc()
				.param("sim", "ref", SIM)
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					return Describe.simulation(doc, sim(s, doc, p), true);
				}).build());

		l.add(Command.builder("sim.create")
				.description("Add a simulation for a flight configuration, with options from preferences unless overridden.")
				.doc()
				.param("name", "string", "Name (default: next 'Simulation N')")
				.param("config", "ref", "Flight configuration (default: selected)")
				.param("options", "object", "Simulation options to set (see sim.get for names), e.g. "
						+ "{\"launchRodLength\":\"1 m\",\"windSpeedAverage\":2,\"launchRodAngle\":\"5 deg\"}")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					doc.addUndoPosition("Add simulation");
					Simulation sim = new Simulation(doc, doc.getRocket());
					sim.setFlightConfigurationId(fc.getId());
					sim.setName(p.string("name", doc.getNextSimulationName()));
					if (p.has("options")) {
						ComponentCommands.applyProperties(s, doc, sim.getOptions(), p.object("options"));
					}
					doc.addSimulation(sim);
					s.markDirty(doc);
					return Describe.simulation(doc, sim, true);
				}).build());

		l.add(Command.builder("sim.set")
				.description("Change a simulation's name, flight configuration and/or options (launch site, wind, "
						+ "atmosphere, rod, time step, ...).")
				.doc()
				.param("sim", "ref", SIM)
				.param("name", "string", "New name")
				.param("config", "ref", "Flight configuration to simulate")
				.param("options", "object", "Option name → value")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					doc.addUndoPosition("Edit simulation");
					if (p.has("name")) {
						sim.setName(p.string("name"));
					}
					if (p.has("config")) {
						sim.setFlightConfigurationId(s.configuration(doc, p.get("config")).getId());
					}
					if (p.has("options")) {
						ComponentCommands.applyProperties(s, doc, sim.getOptions(), p.object("options"));
					}
					s.markDirty(doc);
					return Describe.simulation(doc, sim, true);
				}).build());

		l.add(Command.builder("sim.remove")
				.description("Delete a simulation.")
				.doc()
				.required("sim", "ref", SIM)
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					doc.addUndoPosition("Delete simulation");
					doc.removeSimulation(sim);
					s.markDirty(doc);
					return J.obj("removed", sim.getName());
				}).build());

		l.add(Command.builder("sim.duplicate")
				.description("Copy a simulation (options and extensions, not results).")
				.doc()
				.required("sim", "ref", SIM)
				.param("name", "string", "Name for the copy")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					doc.addUndoPosition("Duplicate simulation");
					Simulation copy = sim.copy();
					copy.setName(p.string("name", doc.getNextSimulationName()));
					doc.addSimulation(copy);
					s.markDirty(doc);
					return Describe.simulation(doc, copy, false);
				}).build());

		l.add(Command.builder("sim.run")
				.description("Run simulations and return their summaries (apogee, max velocity, etc. in SI) and warnings.")
				.doc()
				.param("sim", "ref", SIM + ". Omit to run all simulations.")
				.param("outdatedOnly", "boolean", "When running all, skip simulations that are up to date")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					List<Simulation> sims = new ArrayList<>();
					if (p.has("sim")) {
						sims.add(sim(s, doc, p));
					} else {
						for (Simulation sim : doc.getSimulations()) {
							if (!p.bool("outdatedOnly", false) || !Simulation.isStatusUpToDate(sim.getStatus())) {
								sims.add(sim);
							}
						}
					}
					JsonArray a = new JsonArray();
					for (Simulation sim : sims) {
						JsonObject o;
						try {
							sim.simulate();
							o = Describe.simulation(doc, sim, false);
						} catch (Exception e) {
							o = Describe.simulation(doc, sim, false);
							o.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
						}
						a.add(o);
					}
					s.markDirty(doc);
					return a;
				}).build());

		l.add(Command.builder("sim.events").readOnly()
				.description("Flight events (launch, burnout, apogee, deployment, ground hit, ...) of a simulated flight.")
				.doc()
				.param("sim", "ref", SIM)
				.param("branch", "integer", "Branch (0 = sustainer; boosters/separated stages follow)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					FlightDataBranch b = branch(sim, p);
					JsonArray a = new JsonArray();
					for (FlightEvent e : b.getEvents()) {
						JsonObject o = J.obj("type", e.getType(), "time", e.getTime());
						if (e.getSource() != null) {
							o.add("source", Describe.componentRef(e.getSource()));
						}
						a.add(o);
					}
					return J.obj("branch", b.getName(), "events", a);
				}).build());

		l.add(Command.builder("sim.data_types").readOnly()
				.description("The data series available in a simulated flight branch (key, name, SI unit).")
				.doc()
				.param("sim", "ref", SIM)
				.param("branch", "integer", "Branch index")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					FlightDataBranch b = branch(sim, p);
					JsonArray a = new JsonArray();
					for (FlightDataType t : b.getTypes()) {
						a.add(typeJson(t));
					}
					JsonArray branches = new JsonArray();
					sim.getSimulatedData().getBranches().forEach(x -> branches.add(x.getName()));
					return J.obj("branches", branches, "types", a);
				}).build());

		l.add(Command.builder("sim.data").readOnly()
				.description("Time series of a simulated flight in SI units, as columns. Downsampled to 'maxPoints'.")
				.doc()
				.param("sim", "ref", SIM)
				.param("branch", "integer", "Branch index (default 0)")
				.param("types", "array", "Data series by key or name (default: time, altitude, total velocity, "
						+ "total acceleration, mach). See sim.data_types.")
				.param("maxPoints", "integer", "Maximum rows (default 200, 0 = all)")
				.param("from", "number", "Start time (s)")
				.param("to", "number", "End time (s)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					FlightDataBranch b = branch(sim, p);
					List<String> names = p.strings("types");
					if (names.isEmpty()) {
						names = List.of("time", "altitude", "velocity_total", "acceleration_total", "mach_number");
					}
					List<FlightDataType> types = new ArrayList<>();
					for (String n : names) {
						try {
							types.add(dataType(b, n));
						} catch (AutomationException e) {
							if (p.has("types")) {
								throw e;
							}
						}
					}
					if (!types.contains(FlightDataType.TYPE_TIME)) {
						types.add(0, FlightDataType.TYPE_TIME);
					}
					List<Double> time = b.get(FlightDataType.TYPE_TIME);
					double from = p.number("from", Double.NEGATIVE_INFINITY);
					double to = p.number("to", Double.POSITIVE_INFINITY);
					List<Integer> rows = new ArrayList<>();
					for (int i = 0; i < time.size(); i++) {
						double t = time.get(i);
						if (t >= from && t <= to) {
							rows.add(i);
						}
					}
					int max = p.integer("maxPoints", 200);
					List<Integer> sel = rows;
					if (max > 0 && rows.size() > max) {
						sel = new ArrayList<>();
						double step = (rows.size() - 1) / (double) (max - 1);
						for (int k = 0; k < max; k++) {
							sel.add(rows.get((int) Math.round(k * step)));
						}
					}
					JsonObject cols = new JsonObject();
					JsonObject units = new JsonObject();
					for (FlightDataType t : types) {
						List<Double> v = b.get(t);
						JsonArray col = new JsonArray();
						for (int i : sel) {
							col.add(v == null || i >= v.size() ? null : ValueCodec.number(v.get(i)));
						}
						String key = t.getSaveKey() != null ? t.getSaveKey() : t.getName();
						cols.add(key, col);
						Unit si = t.getUnitGroup().getSIUnit();
						units.addProperty(key, si == null ? "" : si.getUnit());
					}
					return J.obj("branch", b.getName(), "rows", sel.size(), "totalRows", rows.size(),
							"units", units, "columns", cols);
				}).build());

		l.add(Command.builder("sim.export_csv")
				.description("Export simulated flight data to a CSV file (SI units unless 'units' is 'preferred').")
				.doc()
				.param("sim", "ref", SIM)
				.required("path", "string", "Output .csv file")
				.param("branch", "integer", "Branch index")
				.param("types", "array", "Series to export (default: all)")
				.param("units", "string", "'si' (default) or 'preferred' (the user's preferred units)")
				.param("separator", "string", "Field separator (default ',')")
				.param("decimals", "integer", "Decimal places (default 6)")
				.param("comments", "boolean", "Include simulation/field/event comment lines (default true)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					FlightDataBranch b = branch(sim, p);
					List<FlightDataType> types = new ArrayList<>();
					if (p.has("types")) {
						for (String n : p.strings("types")) {
							types.add(dataType(b, n));
						}
					} else {
						types.addAll(List.of(b.getTypes()));
					}
					boolean preferred = "preferred".equalsIgnoreCase(p.string("units", "si"));
					Unit[] units = new Unit[types.size()];
					for (int i = 0; i < units.length; i++) {
						units[i] = preferred ? types.get(i).getUnitGroup().getDefaultUnit()
								: types.get(i).getUnitGroup().getSIUnit();
					}
					File f = new File(p.string("path"));
					boolean comments = p.bool("comments", true);
					try (OutputStream os = new FileOutputStream(f)) {
						CSVExport.exportCSV(os, sim, b, types.toArray(new FlightDataType[0]), units,
								p.string("separator", ","), p.integer("decimals", 6), false, "#", comments, comments,
								comments);
					}
					return J.obj("written", f.getAbsolutePath(), "columns", types.size(), "rows", b.getLength());
				}).build());

		l.add(Command.builder("sim.extensions").readOnly()
				.description("List the simulation extensions that can be added (id and name), e.g. air-start, "
						+ "roll control, scripting.")
				.handler((p, s) -> {
					JsonArray a = new JsonArray();
					for (SimulationExtensionProvider prov : providers()) {
						for (String id : prov.getIds()) {
							a.add(J.obj("id", id, "name", prov.getName(id)));
						}
					}
					return a;
				}).build());

		l.add(Command.builder("sim.add_extension")
				.description("Add a simulation extension to a simulation and optionally configure it. Its settings "
						+ "can later be changed with object.set target 'sim-extension:<sim>:<index>'.")
				.doc()
				.param("sim", "ref", SIM)
				.required("id", "string", "Extension id (see sim.extensions)")
				.param("properties", "object", "Extension settings (e.g. for the scripting extension: "
						+ "{\"language\":\"JavaScript\",\"script\":\"...\"})")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					String id = p.string("id");
					SimulationExtension ext = null;
					for (SimulationExtensionProvider prov : providers()) {
						if (prov.getIds().contains(id)) {
							ext = prov.getInstance(id);
						}
					}
					if (ext == null) {
						throw AutomationException.notFound("No simulation extension '" + id + "'");
					}
					doc.addUndoPosition("Add extension");
					if (p.has("properties")) {
						ComponentCommands.applyProperties(s, doc, ext, p.object("properties"));
					}
					sim.getSimulationExtensions().add(ext);
					s.markDirty(doc);
					return J.obj("index", sim.getSimulationExtensions().size() - 1, "id", ext.getId(), "name", ext.getName(),
							"properties", info.openrocket.core.automation.BeanAccess.describe(ext, false));
				}).build());

		l.add(Command.builder("sim.remove_extension")
				.description("Remove a simulation extension by index.")
				.doc()
				.param("sim", "ref", SIM)
				.required("index", "integer", "Extension index (see sim.get)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Simulation sim = sim(s, doc, p);
					doc.addUndoPosition("Remove extension");
					SimulationExtension e = sim.getSimulationExtensions().remove(p.integer("index"));
					s.markDirty(doc);
					return J.obj("removed", e.getName());
				}).build());

		return l;
	}

	static Set<SimulationExtensionProvider> providers() {
		return Application.getInjector().getInstance(Key.get(new TypeLiteral<Set<SimulationExtensionProvider>>() {
		}));
	}

}
