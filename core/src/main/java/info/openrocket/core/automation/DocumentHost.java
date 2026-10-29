package info.openrocket.core.automation;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import info.openrocket.core.document.OpenRocketDocument;

/**
 * Where an automation session's documents live. The headless host keeps them in memory; the GUI
 * host maps them to open windows so that every change shows up live in the application.
 */
public interface DocumentHost {

	/** All open documents, in a stable order. */
	List<OpenRocketDocument> documents();

	/** Register a newly created or loaded document (the GUI host opens a window for it). */
	void add(OpenRocketDocument doc);

	/** Close a document. */
	void close(OpenRocketDocument doc);

	/** The document the user is working on, or null. */
	OpenRocketDocument active();

	/** Make a document the active one. */
	void activate(OpenRocketDocument doc);

	/** Run a task on the thread that owns the documents. */
	<T> T run(Callable<T> task) throws Exception;

	/** Whether a GUI is attached. */
	boolean isGui();

	/** Load a design file (the GUI host may do this through its own loader). */
	default OpenRocketDocument load(File file) throws Exception {
		info.openrocket.core.file.GeneralRocketLoader loader = new info.openrocket.core.file.GeneralRocketLoader(file);
		OpenRocketDocument doc = loader.load();
		doc.setFile(file);
		doc.setSaved(true);
		return doc;
	}
}
