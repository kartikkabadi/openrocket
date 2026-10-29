package info.openrocket.core.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import info.openrocket.core.document.OpenRocketDocument;

/**
 * Keeps documents in memory for CLI / MCP use without a GUI.
 */
public class HeadlessDocumentHost implements DocumentHost {
	private final List<OpenRocketDocument> docs = new ArrayList<>();
	private OpenRocketDocument active;

	@Override
	public synchronized List<OpenRocketDocument> documents() {
		return new ArrayList<>(docs);
	}

	@Override
	public synchronized void add(OpenRocketDocument doc) {
		if (!docs.contains(doc)) {
			docs.add(doc);
		}
		active = doc;
	}

	@Override
	public synchronized void close(OpenRocketDocument doc) {
		docs.remove(doc);
		if (active == doc) {
			active = docs.isEmpty() ? null : docs.get(docs.size() - 1);
		}
	}

	@Override
	public synchronized OpenRocketDocument active() {
		return active;
	}

	@Override
	public synchronized void activate(OpenRocketDocument doc) {
		active = doc;
	}

	@Override
	public synchronized <T> T run(Callable<T> task) throws Exception {
		return task.call();
	}

	@Override
	public boolean isGui() {
		return false;
	}
}
