package info.openrocket.core.automation.commands;

import java.io.File;
import java.net.URL;
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
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.StorageOptions;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/** Creating, opening, saving and closing documents; undo/redo. */
public final class DocumentCommands {
	private DocumentCommands() {
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("doc.list")
				.description("List open documents (designs) with their ids.")
				.handler((p, s) -> {
					JsonArray a = new JsonArray();
					for (OpenRocketDocument d : s.documents()) {
						a.add(Describe.document(s, d));
					}
					return a;
				}).build());

		l.add(Command.builder("doc.new")
				.description("Create a new rocket design (one stage, empty) and make it the active document.")
				.param("name", "string", "Rocket name")
				.param("empty", "boolean", "Create a rocket with no stages at all (default false)")
				.handler((p, s) -> {
					OpenRocketDocument doc = p.bool("empty", false) ? OpenRocketDocumentFactory.createEmptyRocket()
							: OpenRocketDocumentFactory.createNewRocket();
					if (p.has("name")) {
						doc.getRocket().setName(p.string("name"));
					}
					doc.clearUndo();
					s.addDocument(doc);
					return Describe.document(s, doc);
				}).build());

		l.add(Command.builder("doc.open")
				.description("Open a design file (.ork, RockSim .rkt, RASAero .CDX1) and make it active.")
				.required("path", "string", "Path of the file to open")
				.handler((p, s) -> {
					File f = new File(p.string("path"));
					if (!f.exists()) {
						throw AutomationException.notFound("File not found: " + f.getAbsolutePath());
					}
					OpenRocketDocument doc = s.document(f.getPath());
					s.getHost().activate(doc);
					return Describe.document(s, doc);
				}).build());

		l.add(Command.builder("doc.examples")
				.description("List the example designs bundled with OpenRocket, or open one by name.")
				.param("open", "string", "Name of the example to open (e.g. \"A simple model rocket\")")
				.handler((p, s) -> {
					List<Resource> res = new ArrayList<>();
					try (ScanResult scan = new ClassGraph().acceptPaths("datafiles/examples").scan()) {
						res.addAll(scan.getResourcesWithExtension("ork"));
						if (!p.has("open")) {
							JsonArray a = new JsonArray();
							res.stream().map(r -> new File(r.getPath()).getName().replaceAll("\\.ork$", ""))
									.sorted().forEach(a::add);
							return a;
						}
						String want = p.string("open");
						for (Resource r : res) {
							String n = new File(r.getPath()).getName().replaceAll("\\.ork$", "");
							if (n.equalsIgnoreCase(want) || n.equalsIgnoreCase(want.replaceAll("\\.ork$", ""))) {
								URL url = r.getURL();
								OpenRocketDocument doc;
								try (java.io.InputStream in = new java.io.BufferedInputStream(url.openStream())) {
									doc = new GeneralRocketLoader(url).load(in, n);
								}
								doc.setSaved(true);
								s.addDocument(doc);
								return Describe.document(s, doc);
							}
						}
					}
					throw AutomationException.notFound("No example named '" + p.string("open") + "'");
				}).build());

		l.add(Command.builder("doc.info")
				.description("Summary of a document: file, counts, selected configuration, undo state, rocket properties.")
				.doc()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					JsonObject o = Describe.document(s, doc);
					o.add("rocket", BeanAccess.describe(doc.getRocket(), false));
					return o;
				}).build());

		l.add(Command.builder("doc.activate")
				.description("Make a document the active one (the default target of later commands; in the GUI, brings its window to front).")
				.required("doc", "string", "Document id or path")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					s.getHost().activate(doc);
					return Describe.document(s, doc);
				}).build());

		l.add(Command.builder("doc.save")
				.description("Save a document. Without 'path' saves to its current file. The format follows the "
						+ "extension (.ork; use export.* for other formats).")
				.doc()
				.param("path", "string", "Destination file (save as)")
				.param("saveSimulationData", "boolean", "Store simulated flight data in the file (default: document setting)")
				.anyThread()
				.handler((p, s) -> {
					OpenRocketDocument doc = s.getHost().run(() -> s.document(p));
					File f = p.has("path") ? new File(p.string("path")) : doc.getFile();
					if (f == null) {
						throw AutomationException.badParam("Document has never been saved; pass 'path'");
					}
					if (!f.getName().contains(".")) {
						f = new File(f.getPath() + ".ork");
					}
					StorageOptions opts = doc.getDefaultStorageOptions();
					if (p.has("saveSimulationData")) {
						opts.setSaveSimulationData(p.bool("saveSimulationData", true));
					}
					final File dest = f;
					GeneralRocketSaver saver = new GeneralRocketSaver();
					s.getHost().run(() -> {
						saver.save(dest, doc, opts);
						doc.setFile(dest);
						doc.setSaved(true);
						return null;
					});
					JsonObject o = J.obj("saved", dest.getAbsolutePath());
					o.add("warnings", Describe.warnings(saver.getWarnings()));
					return o;
				}).build());

		l.add(Command.builder("doc.close")
				.description("Close a document (unsaved changes are discarded unless 'save' is true).")
				.doc()
				.param("save", "boolean", "Save to its file first")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					if (p.bool("save", false)) {
						if (doc.getFile() == null) {
							throw AutomationException.badParam("Document has no file; use doc.save with a path first");
						}
						new GeneralRocketSaver().save(doc.getFile(), doc, doc.getDefaultStorageOptions());
					}
					String id = s.idOf(doc);
					s.getHost().close(doc);
					return J.obj("closed", id);
				}).build());

		l.add(Command.builder("doc.undo")
				.description("Undo the last change in a document.")
				.doc()
				.param("steps", "integer", "Number of steps (default 1)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					int n = p.integer("steps", 1);
					List<String> done = new ArrayList<>();
					for (int i = 0; i < n && doc.isUndoAvailable(); i++) {
						done.add(doc.getUndoDescription());
						doc.undo();
					}
					return J.obj("undone", done, "undo", doc.isUndoAvailable() ? doc.getUndoDescription() : null,
							"redo", doc.isRedoAvailable() ? doc.getRedoDescription() : null);
				}).build());

		l.add(Command.builder("doc.redo")
				.description("Redo the last undone change in a document.")
				.doc()
				.param("steps", "integer", "Number of steps (default 1)")
				.handler((p, s) -> {
					OpenRocketDocument doc = s.document(p);
					int n = p.integer("steps", 1);
					List<String> done = new ArrayList<>();
					for (int i = 0; i < n && doc.isRedoAvailable(); i++) {
						done.add(doc.getRedoDescription());
						doc.redo();
					}
					return J.obj("redone", done, "undo", doc.isUndoAvailable() ? doc.getUndoDescription() : null,
							"redo", doc.isRedoAvailable() ? doc.getRedoDescription() : null);
				}).build());

		return l;
	}
}
