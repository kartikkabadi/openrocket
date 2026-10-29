package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.util.CoordinateIF;

/** Inspecting and editing the component tree. */
public final class ComponentCommands {
	private ComponentCommands() {
	}

	/** Component types that can be added, keyed by class name. */
	public static final Map<String, Class<? extends RocketComponent>> TYPES = new LinkedHashMap<>();
	static {
		for (Class<? extends RocketComponent> c : List.of(AxialStage.class, ParallelStage.class, PodSet.class,
				NoseCone.class, BodyTube.class, Transition.class, TrapezoidFinSet.class, EllipticalFinSet.class,
				FreeformFinSet.class, TubeFinSet.class, LaunchLug.class, RailButton.class, InnerTube.class,
				TubeCoupler.class, CenteringRing.class, Bulkhead.class, EngineBlock.class, Parachute.class,
				Streamer.class, ShockCord.class, MassComponent.class)) {
			TYPES.put(c.getSimpleName(), c);
		}
	}

	private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
			Map.entry("stage", "AxialStage"), Map.entry("booster", "ParallelStage"), Map.entry("boosters", "ParallelStage"),
			Map.entry("pod", "PodSet"), Map.entry("pods", "PodSet"), Map.entry("nose", "NoseCone"),
			Map.entry("nosecone", "NoseCone"), Map.entry("tube", "BodyTube"), Map.entry("bodytube", "BodyTube"),
			Map.entry("fins", "TrapezoidFinSet"), Map.entry("finset", "TrapezoidFinSet"),
			Map.entry("trapezoidfins", "TrapezoidFinSet"), Map.entry("ellipticalfins", "EllipticalFinSet"),
			Map.entry("freeformfins", "FreeformFinSet"), Map.entry("tubefins", "TubeFinSet"),
			Map.entry("lug", "LaunchLug"), Map.entry("launchlug", "LaunchLug"), Map.entry("railbutton", "RailButton"),
			Map.entry("innertube", "InnerTube"), Map.entry("motormount", "InnerTube"), Map.entry("coupler", "TubeCoupler"),
			Map.entry("centeringring", "CenteringRing"), Map.entry("ring", "CenteringRing"),
			Map.entry("bulkhead", "Bulkhead"), Map.entry("engineblock", "EngineBlock"),
			Map.entry("parachute", "Parachute"), Map.entry("chute", "Parachute"), Map.entry("streamer", "Streamer"),
			Map.entry("shockcord", "ShockCord"), Map.entry("mass", "MassComponent"),
			Map.entry("masscomponent", "MassComponent"));

	public static Class<? extends RocketComponent> type(String name) {
		String key = name.replaceAll("[\\s_-]", "");
		for (String t : TYPES.keySet()) {
			if (t.equalsIgnoreCase(key)) {
				return TYPES.get(t);
			}
		}
		String alias = TYPE_ALIASES.get(key.toLowerCase(Locale.ROOT));
		if (alias != null) {
			return TYPES.get(alias);
		}
		throw AutomationException.badParam("Unknown component type '" + name + "'. Valid types: "
				+ String.join(", ", TYPES.keySet()));
	}

	static RocketComponent comp(AutomationSession s, OpenRocketDocument doc, Params p, String key) {
		return s.component(doc, p.string(key, null));
	}

	/** Apply a map of property values to an object, returning the properties that were set. */
	public static JsonArray applyProperties(AutomationSession s, OpenRocketDocument doc, Object target, JsonObject props) {
		JsonArray done = new JsonArray();
		for (Map.Entry<String, JsonElement> e : props.entrySet()) {
			try {
				BeanAccess.set(target, e.getKey(), e.getValue(), ref -> s.component(doc, ref));
			} catch (AutomationException ex) {
				throw new AutomationException(ex.getCode(), "Setting '" + e.getKey() + "': " + ex.getMessage(), ex);
			}
			done.add(e.getKey());
		}
		return done;
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("component.types")
				.description("List the component types that can be added, and (optionally) which are allowed under a given parent.")
				.doc()
				.param("parent", "string", "Component reference; only list types allowed as its children")
				.handler((p, s) -> {
					RocketComponent parent = null;
					if (p.has("parent")) {
						parent = s.component(s.document(p), p.string("parent"));
					}
					JsonArray a = new JsonArray();
					for (Map.Entry<String, Class<? extends RocketComponent>> e : TYPES.entrySet()) {
						if (parent != null && !parent.isCompatible(e.getValue())) {
							continue;
						}
						a.add(e.getKey());
					}
					return a;
				}).build());

		l.add(Command.builder("component.tree")
				.description("The component tree of the rocket (or of a sub-component), with ids, names, types and key dimensions (SI). "
						+ "Component references accepted elsewhere: id, unique name, a/b/c name path, 'Type#n', or 'rocket'.")
				.doc()
				.param("component", "string", "Root of the subtree (default: the rocket)")
				.param("depth", "integer", "Maximum depth (-1 = unlimited, default)")
				.param("properties", "boolean", "Include all property values for each component (verbose)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					return Describe.tree(comp(s, doc, p, "component"), p.integer("depth", -1), p.bool("properties", false));
				}).build());

		l.add(Command.builder("component.get")
				.description("All properties of a component with their current values (SI units), types, allowed choices and whether they are writable.")
				.doc()
				.required("component", "string", "Component reference")
				.param("names", "array", "Only these properties")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					JsonObject o = Describe.componentRef(c);
					o.addProperty("componentName", c.getComponentName());
					o.add("parent", c.getParent() == null ? null : Describe.componentRef(c.getParent()));
					JsonObject props = BeanAccess.describe(c, true);
					List<String> names = p.strings("names");
					if (!names.isEmpty()) {
						JsonObject f = new JsonObject();
						for (String n : names) {
							f.add(n, props.has(n) ? props.get(n) : ValueCodec.toJson(BeanAccess.get(c, n)));
						}
						props = f;
					}
					o.add("properties", props);
					JsonArray children = new JsonArray();
					c.getChildren().forEach(ch -> children.add(Describe.componentRef(ch)));
					o.add("children", children);
					if (c instanceof FinSet fs) {
						o.add("finPoints", ValueCodec.toJson(fs.getFinPoints()));
					}
					return o;
				}).build());

		l.add(Command.builder("component.set")
				.description("Set one or more properties of a component, e.g. {\"length\": \"30 cm\", \"material\": \"Balsa\", "
						+ "\"shapeType\": \"OGIVE\"}. Numbers are SI unless a unit is given in a string. Undoable.")
				.doc()
				.required("component", "string", "Component reference")
				.required("properties", "object", "Property name → value")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					doc.addUndoPosition("Modify " + c.getName());
					JsonArray set = applyProperties(s, doc, c, p.object("properties"));
					s.markDirty(doc);
					JsonObject o = Describe.componentRef(c);
					o.add("set", set);
					JsonObject now = new JsonObject();
					for (JsonElement n : set) {
						try {
							now.add(n.getAsString(), ValueCodec.toJson(BeanAccess.get(c, n.getAsString())));
						} catch (AutomationException ignored) {
						}
					}
					o.add("values", now);
					return o;
				}).build());

		l.add(Command.builder("component.add")
				.description("Add a new component under a parent, optionally setting properties. Body components "
						+ "(NoseCone, BodyTube, Transition) go in a stage; fins, lugs, inner tubes, recovery and mass items "
						+ "go in body components. Returns the new component's id.")
				.doc()
				.required("type", "string", "Component type (see component.types), e.g. BodyTube, TrapezoidFinSet, Parachute")
				.param("parent", "string", "Parent component reference (default: last stage for body components, else required)")
				.param("index", "integer", "Position among the parent's children (default: append)")
				.param("name", "string", "Component name")
				.param("properties", "object", "Initial property values")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					Class<? extends RocketComponent> type = type(p.string("type"));
					RocketComponent parent;
					if (p.has("parent")) {
						parent = s.component(doc, p.string("parent"));
					} else if (AxialStage.class.isAssignableFrom(type) && type != ParallelStage.class) {
						parent = doc.getRocket();
					} else if (BodyComponent.class.isAssignableFrom(type) && doc.getRocket().getStageCount() > 0) {
						parent = doc.getRocket().getChild(doc.getRocket().getChildCount() - 1);
					} else {
						throw AutomationException.badParam("Pass 'parent' for a " + type.getSimpleName());
					}
					if (!parent.isCompatible(type)) {
						throw AutomationException.badParam(type.getSimpleName() + " cannot be placed in "
								+ parent.getClass().getSimpleName() + " '" + parent.getName() + "'");
					}
					RocketComponent c = type.getConstructor().newInstance();
					if (p.has("name")) {
						c.setName(p.string("name"));
					}
					doc.addUndoPosition("Add " + c.getComponentName());
					int index = p.integer("index", -1);
					if (index >= 0 && index <= parent.getChildCount()) {
						parent.addChild(c, index);
					} else {
						parent.addChild(c);
					}
					if (p.has("properties")) {
						applyProperties(s, doc, c, p.object("properties"));
					}
					s.markDirty(doc);
					return Describe.tree(c, 0, false);
				}).build());

		l.add(Command.builder("component.remove")
				.description("Remove a component (and its children). Undoable.")
				.doc()
				.required("component", "string", "Component reference")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					if (c instanceof Rocket) {
						throw AutomationException.badParam("Cannot remove the rocket itself");
					}
					doc.addUndoPosition("Delete " + c.getName());
					c.getParent().removeChild(c);
					s.markDirty(doc);
					return J.obj("removed", Describe.componentRef(c));
				}).build());

		l.add(Command.builder("component.move")
				.description("Move a component to a new position and/or a new parent.")
				.doc()
				.required("component", "string", "Component reference")
				.param("parent", "string", "New parent (default: same parent)")
				.param("index", "integer", "New index among the parent's children (default: end)")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					RocketComponent parent = p.has("parent") ? s.component(doc, p.string("parent")) : c.getParent();
					if (!parent.isCompatible(c)) {
						throw AutomationException.badParam(c.getClass().getSimpleName() + " cannot be placed in " + parent.getName());
					}
					doc.addUndoPosition("Move " + c.getName());
					if (parent == c.getParent()) {
						int index = p.integer("index", parent.getChildCount() - 1);
						parent.moveChild(c, Math.max(0, Math.min(index, parent.getChildCount() - 1)));
					} else {
						c.getParent().removeChild(c);
						int index = p.integer("index", -1);
						if (index >= 0 && index <= parent.getChildCount()) {
							parent.addChild(c, index);
						} else {
							parent.addChild(c);
						}
					}
					s.markDirty(doc);
					return J.obj("parent", Describe.componentRef(parent), "index", parent.getChildPosition(c));
				}).build());

		l.add(Command.builder("component.duplicate")
				.description("Copy a component (with its children) and insert the copy under a parent (default: right after the original).")
				.doc()
				.required("component", "string", "Component reference")
				.param("parent", "string", "Parent for the copy")
				.param("index", "integer", "Index for the copy")
				.param("name", "string", "Name for the copy")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					RocketComponent copy = c.copy();
					if (p.has("name")) {
						copy.setName(p.string("name"));
					}
					RocketComponent parent = p.has("parent") ? s.component(doc, p.string("parent")) : c.getParent();
					if (parent == null || !parent.isCompatible(copy)) {
						throw AutomationException.badParam("Cannot place the copy under " + (parent == null ? "nothing" : parent.getName()));
					}
					doc.addUndoPosition("Duplicate " + c.getName());
					int index = p.integer("index", parent == c.getParent() ? parent.getChildPosition(c) + 1 : parent.getChildCount());
					parent.addChild(copy, Math.min(index, parent.getChildCount()));
					s.markDirty(doc);
					return Describe.tree(copy, 0, false);
				}).build());

		l.add(Command.builder("component.preset")
				.description("Apply a manufacturer part (from preset.search) to a component, or clear the preset.")
				.doc()
				.required("component", "string", "Component reference")
				.param("manufacturer", "string", "Manufacturer name")
				.param("partNo", "string", "Part number")
				.param("clear", "boolean", "Remove the preset association instead")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					doc.addUndoPosition("Preset " + c.getName());
					if (p.bool("clear", false)) {
						c.clearPreset();
					} else {
						JsonObject ref = J.obj("manufacturer", p.string("manufacturer"), "partNo", p.string("partNo"));
						ComponentPreset preset = ValueCodec.parsePreset(ref);
						c.loadPreset(preset);
					}
					s.markDirty(doc);
					return J.obj("component", Describe.componentRef(c), "preset", c.getPresetComponent());
				}).build());

		l.add(Command.builder("component.fin_points")
				.description("Get or set the outline points of a fin set (x along the root chord, y outward, SI). "
						+ "Setting points on a trapezoidal/elliptical fin set converts it to a freeform fin set first.")
				.doc()
				.required("component", "string", "Fin set reference")
				.param("points", "array", "New points, e.g. [[0,0],[0.05,0.04],[0.08,0.04],[0.1,0]]; first and last must have y=0")
				.mutating()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					RocketComponent c = comp(s, doc, p, "component");
					if (!(c instanceof FinSet fs)) {
						throw AutomationException.badParam(c.getName() + " is not a fin set");
					}
					if (!p.has("points")) {
						return J.obj("component", Describe.componentRef(c), "points", fs.getFinPoints());
					}
					doc.addUndoPosition("Edit fin points of " + c.getName());
					FreeformFinSet ff;
					if (fs instanceof FreeformFinSet f) {
						ff = f;
					} else {
						ff = FreeformFinSet.convertFinSet(fs);
					}
					JsonArray pts = p.array("points");
					CoordinateIF[] coords = new CoordinateIF[pts.size()];
					for (int i = 0; i < pts.size(); i++) {
						coords[i] = ValueCodec.parseCoordinate(pts.get(i));
					}
					ff.setPoints(coords);
					s.markDirty(doc);
					return J.obj("component", Describe.componentRef(ff), "points", ff.getFinPoints());
				}).build());

		return l;
	}
}
