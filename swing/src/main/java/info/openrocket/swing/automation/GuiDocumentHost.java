package info.openrocket.swing.automation;

import java.awt.Dialog;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.SwingUtilities;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.DocumentHost;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.swing.gui.main.BasicFrame;
import info.openrocket.swing.gui.main.MRUDesignFile;

/**
 * Documents are the designs open in GUI windows; all work happens on the Swing event thread so that
 * the windows update live.
 */
public class GuiDocumentHost implements DocumentHost {
	/** How long to wait for a command before reporting that a modal dialog is blocking it. */
	private static final long DIALOG_GRACE_MS = 750;
	private static final long TIMEOUT_MS = Long.getLong("openrocket.automation.timeout", 600_000);

	private OpenRocketDocument active;

	@Override
	public List<OpenRocketDocument> documents() {
		List<OpenRocketDocument> l = new ArrayList<>();
		for (BasicFrame f : BasicFrame.getAllFrames()) {
			l.add(f.getDocument());
		}
		return l;
	}

	public static BasicFrame frame(OpenRocketDocument doc) {
		for (BasicFrame f : BasicFrame.getAllFrames()) {
			if (f.getDocument() == doc) {
				return f;
			}
		}
		return null;
	}

	@Override
	public void add(OpenRocketDocument doc) {
		if (frame(doc) == null) {
			BasicFrame startup = BasicFrame.getStartupFrame();
			BasicFrame f = new BasicFrame(doc);
			f.setVisible(true);
			// Like File → Open: replace the untouched startup window
			if (startup != null && startup != f && BasicFrame.getAllFrames().size() == 2
					&& startup.getDocument().isSaved() && startup.getDocument().getFile() == null
					&& !startup.getDocument().isUndoAvailable()) {
				startup.close(true);
				BasicFrame.setStartupFrame(null);
			}
			if (doc.getFile() != null) {
				MRUDesignFile.getInstance().addFile(doc.getFile().getAbsolutePath());
			}
		}
		active = doc;
	}

	@Override
	public void close(OpenRocketDocument doc) {
		BasicFrame f = frame(doc);
		if (f != null) {
			f.close(true);
		}
		if (active == doc) {
			active = null;
		}
	}

	@Override
	public OpenRocketDocument active() {
		for (BasicFrame f : BasicFrame.getAllFrames()) {
			if (f.isFocused()) {
				return f.getDocument();
			}
		}
		if (active != null && frame(active) != null) {
			return active;
		}
		List<BasicFrame> frames = BasicFrame.getAllFrames();
		return frames.isEmpty() ? null : frames.get(frames.size() - 1).getDocument();
	}

	@Override
	public void activate(OpenRocketDocument doc) {
		active = doc;
		BasicFrame f = frame(doc);
		if (f != null) {
			f.toFront();
			f.requestFocus();
		}
	}

	@Override
	public OpenRocketDocument load(File file) throws Exception {
		return DocumentHost.super.load(file);
	}

	@Override
	public boolean isGui() {
		return true;
	}

	@Override
	public <T> T run(Callable<T> task) throws Exception {
		if (SwingUtilities.isEventDispatchThread()) {
			return task.call();
		}
		Map<Window, Boolean> before = new IdentityHashMap<>();
		for (Window w : Window.getWindows()) {
			if (w.isShowing()) {
				before.put(w, true);
			}
		}
		CompletableFuture<T> f = new CompletableFuture<>();
		SwingUtilities.invokeLater(() -> {
			try {
				f.complete(task.call());
			} catch (Throwable e) {
				f.completeExceptionally(e);
			}
		});
		long start = System.currentTimeMillis();
		while (true) {
			try {
				return f.get(100, TimeUnit.MILLISECONDS);
			} catch (ExecutionException e) {
				Throwable c = e.getCause();
				if (c instanceof Exception ex) {
					throw ex;
				}
				throw new RuntimeException(c);
			} catch (TimeoutException e) {
				long elapsed = System.currentTimeMillis() - start;
				if (elapsed > DIALOG_GRACE_MS) {
					Window blocker = newModalDialog(before);
					if (blocker != null) {
						throw new AutomationException("blocked_by_dialog", "The command opened a modal dialog '"
								+ GuiCommands.title(blocker) + "' and continues when it is closed. Inspect it with "
								+ "gui.windows / gui.tree and operate it with gui.click / gui.set_value.", null);
					}
				}
				if (elapsed > TIMEOUT_MS) {
					throw new AutomationException("timeout", "Timed out waiting for the GUI", null);
				}
			}
		}
	}

	private static Window newModalDialog(Map<Window, Boolean> before) {
		for (Window w : Window.getWindows()) {
			if (w.isShowing() && !before.containsKey(w) && w instanceof Dialog d && d.isModal()) {
				return w;
			}
		}
		return null;
	}
}
