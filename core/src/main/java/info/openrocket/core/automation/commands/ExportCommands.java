package info.openrocket.core.automation.commands;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.Describe;
import info.openrocket.core.automation.J;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.file.rasaero.export.RASAeroSaver;
import info.openrocket.core.file.rocksim.export.RockSimSaver;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.file.wavefrontobj.export.OBJExportOptions;
import info.openrocket.core.file.wavefrontobj.export.OBJExporterFactory;
import info.openrocket.core.logging.ErrorSet;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;

/** Export to other programs' formats. Saving .ork files is doc.save. */
public final class ExportCommands {
	private ExportCommands() {
	}

	private static JsonObject result(File f, WarningSet w, ErrorSet e) {
		JsonObject o = J.obj("written", f.getAbsolutePath());
		o.add("warnings", Describe.warnings(w));
		o.add("errors", J.arr(e));
		return o;
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("export.rocksim")
				.description("Export the design to a RockSim .rkt file.")
				.doc()
				.required("path", "string", "Output file")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					File f = new File(p.string("path"));
					WarningSet w = new WarningSet();
					ErrorSet e = new ErrorSet();
					try (OutputStream os = new FileOutputStream(f)) {
						new RockSimSaver().save(os, doc, doc.getDefaultStorageOptions(), w, e);
					}
					return result(f, w, e);
				}).build());

		l.add(Command.builder("export.rasaero")
				.description("Export the design to a RASAero II .CDX1 file.")
				.doc()
				.required("path", "string", "Output file")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					File f = new File(p.string("path"));
					WarningSet w = new WarningSet();
					ErrorSet e = new ErrorSet();
					try (OutputStream os = new FileOutputStream(f)) {
						new RASAeroSaver().save(os, doc, doc.getDefaultStorageOptions(), w, e);
					}
					return result(f, w, e);
				}).build());

		l.add(Command.builder("export.obj")
				.description("Export 3D geometry to a Wavefront .obj file (for 3D printing / CAD).")
				.doc()
				.required("path", "string", "Output .obj file")
				.param("components", "array", "Components to export (default: the whole rocket)")
				.param("config", "ref", "Flight configuration (default: selected)")
				.param("options", "object", "OBJ options: exportChildren, exportAllInstances, exportMotors, "
						+ "removeOffset, triangulate, triangulationMethod (DELAUNAY/SIMPLE), exportAppearance, exportAsSeparateFiles, LOD (LOW_QUALITY/"
						+ "NORMAL_QUALITY/HIGH_QUALITY), scalingDouble, useSRGB")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					FlightConfiguration fc = s.configuration(doc, p.get("config"));
					List<RocketComponent> comps = new ArrayList<>();
					for (String ref : p.strings("components")) {
						comps.add(s.component(doc, ref));
					}
					if (comps.isEmpty()) {
						comps.add(doc.getRocket());
					}
					OBJExportOptions opts = new OBJExportOptions(doc.getRocket());
					opts.setExportChildren(true);
					opts.setTriangulationMethod(ObjUtils.TriangulationMethod.DELAUNAY);
					if (p.has("options")) {
						ComponentCommands.applyProperties(s, doc, opts, p.object("options"));
					}
					File f = new File(p.string("path"));
					WarningSet w = new WarningSet();
					new OBJExporterFactory(comps, fc, f, opts, w).doExport();
					return result(f, w, new ErrorSet());
				}).build());

		return l;
	}
}
