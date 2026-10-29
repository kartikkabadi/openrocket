package info.openrocket.swing.automation;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.Describe;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.Params;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.configdialog.ComponentConfigDialog;
import info.openrocket.swing.gui.main.BasicFrame;
import info.openrocket.swing.gui.scalefigure.RocketPanel;

/**
 * Commands that operate the Swing user interface itself: windows, menus, any widget in any dialog,
 * selection, views and screenshots. Together with the model commands this makes everything the user
 * can do in the GUI scriptable.
 *
 * Widgets are addressed by ids of the form {@code w<window>/<i>/<j>/...} (child indices from the
 * window down), as reported by gui.tree and gui.find.
 */
public final class GuiCommands {
	private GuiCommands() {
	}

	private static final Map<Window, Integer> WINDOW_IDS = new WeakHashMap<>();
	private static int nextWindowId = 0;

	// ------------------------------------------------------------------ windows and ids

	static synchronized int windowId(Window w) {
		return WINDOW_IDS.computeIfAbsent(w, k -> nextWindowId++);
	}

	static String title(Window w) {
		if (w instanceof Frame f) {
			return f.getTitle();
		}
		if (w instanceof Dialog d) {
			return d.getTitle();
		}
		return "";
	}

	static List<Window> windows() {
		List<Window> l = new ArrayList<>();
		for (Window w : Window.getWindows()) {
			if (w.isShowing()) {
				l.add(w);
			}
		}
		return l;
	}

	static Window window(String ref) {
		List<Window> ws = windows();
		if (ref == null || ref.isBlank() || ref.equals("active")) {
			Window f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
			if (f != null && f.isShowing()) {
				return f;
			}
			// Topmost: the most recently opened showing dialog, else the last frame
			for (int i = ws.size() - 1; i >= 0; i--) {
				if (ws.get(i) instanceof Dialog) {
					return ws.get(i);
				}
			}
			if (!ws.isEmpty()) {
				return ws.get(ws.size() - 1);
			}
			throw AutomationException.notFound("No window is showing");
		}
		String r = ref.startsWith("w") ? ref.substring(1) : ref;
		try {
			int id = Integer.parseInt(r);
			for (Window w : ws) {
				if (windowId(w) == id) {
					return w;
				}
			}
		} catch (NumberFormatException ignored) {
		}
		for (Window w : ws) {
			if (ref.equalsIgnoreCase(title(w))) {
				return w;
			}
		}
		for (Window w : ws) {
			if (title(w) != null && title(w).toLowerCase(Locale.ROOT).contains(ref.toLowerCase(Locale.ROOT))) {
				return w;
			}
		}
		throw AutomationException.notFound("No window '" + ref + "'. Use gui.windows.");
	}

	static String id(Component c) {
		List<Integer> path = new ArrayList<>();
		Component cur = c;
		while (!(cur instanceof Window)) {
			Container parent = cur.getParent();
			if (parent == null) {
				// popup menu items: route through the invoker
				if (cur instanceof JPopupMenu pm && pm.getInvoker() != null) {
					return id(pm.getInvoker()) + "/popup";
				}
				return null;
			}
			path.add(0, indexOf(parent, cur));
			cur = parent;
		}
		StringBuilder sb = new StringBuilder("w").append(windowId((Window) cur));
		for (int i : path) {
			sb.append('/').append(i);
		}
		return sb.toString();
	}

	private static int indexOf(Container parent, Component c) {
		Component[] ch = parent.getComponents();
		for (int i = 0; i < ch.length; i++) {
			if (ch[i] == c) {
				return i;
			}
		}
		return -1;
	}

	static Component component(String id) {
		if (id == null) {
			throw AutomationException.badParam("Pass a widget id (from gui.tree / gui.find)");
		}
		String[] parts = id.split("/");
		Component cur = window(parts[0]);
		for (int i = 1; i < parts.length; i++) {
			if (!(cur instanceof Container ct)) {
				throw AutomationException.notFound("Widget " + id + " no longer exists");
			}
			int idx = Integer.parseInt(parts[i]);
			if (idx < 0 || idx >= ct.getComponentCount()) {
				throw AutomationException.notFound("Widget " + id + " no longer exists (the window changed; re-run gui.tree)");
			}
			cur = ct.getComponent(idx);
		}
		return cur;
	}

	// ------------------------------------------------------------------ describing widgets

	static String text(Component c) {
		if (c instanceof AbstractButton b) {
			return b.getText();
		}
		if (c instanceof JLabel l) {
			return l.getText();
		}
		if (c instanceof JTextComponent t) {
			return t.getText();
		}
		if (c instanceof JComboBox<?> cb) {
			return cb.getSelectedItem() == null ? null : cb.getSelectedItem().toString();
		}
		if (c instanceof JSpinner sp) {
			return String.valueOf(sp.getValue());
		}
		if (c instanceof JProgressBar pb) {
			return pb.getString();
		}
		return null;
	}

	private static String clean(String s) {
		if (s == null) {
			return null;
		}
		String t = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		return t.isEmpty() ? null : t;
	}

	static boolean interactive(Component c) {
		return c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox
				|| c instanceof JSpinner || c instanceof JSlider || c instanceof JTabbedPane || c instanceof JTable
				|| c instanceof JTree || c instanceof JList || c instanceof JMenuBar;
	}

	/** The JLabel that describes a widget: labelFor, else the nearest preceding label in the same container. */
	static String label(Component c) {
		Container p = c.getParent();
		if (p == null) {
			return null;
		}
		for (Component sib : p.getComponents()) {
			if (sib instanceof JLabel l && l.getLabelFor() == c) {
				return clean(l.getText());
			}
		}
		int idx = indexOf(p, c);
		for (int i = idx - 1; i >= 0 && i >= idx - 3; i--) {
			Component sib = p.getComponent(i);
			if (sib instanceof JLabel l && l.isShowing() && clean(l.getText()) != null) {
				return clean(l.getText());
			}
			if (interactive(sib)) {
				break;
			}
		}
		return null;
	}

	static JsonObject describe(Component c, boolean detail) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id(c));
		o.addProperty("type", c.getClass().getSimpleName().isEmpty() ? c.getClass().getSuperclass().getSimpleName()
				: c.getClass().getSimpleName());
		String t = clean(text(c));
		if (t != null) {
			o.addProperty("text", t.length() > 200 ? t.substring(0, 200) + "…" : t);
		}
		if (c.getName() != null) {
			o.addProperty("name", c.getName());
		}
		if (!(c instanceof JLabel) && interactive(c) && !(c instanceof AbstractButton && t != null)
				&& !(c instanceof JSlider)) {
			String l = label(c);
			if (l != null) {
				o.addProperty("label", l);
			}
		}
		if (c instanceof JComponent jc && clean(jc.getToolTipText()) != null) {
			String tip = clean(jc.getToolTipText());
			o.addProperty("tooltip", tip.length() > 160 ? tip.substring(0, 160) + "…" : tip);
		}
		if (!c.isEnabled()) {
			o.addProperty("enabled", false);
		}
		if (c instanceof JToggleButton tb) {
			o.addProperty("selected", tb.isSelected());
		}
		if (c instanceof JTabbedPane tp) {
			JsonArray tabs = new JsonArray();
			for (int i = 0; i < tp.getTabCount(); i++) {
				tabs.add(clean(tp.getTitleAt(i)));
			}
			o.add("tabs", tabs);
			o.addProperty("selectedTab", tp.getSelectedIndex());
		}
		if (detail && c instanceof JComboBox<?> cb) {
			JsonArray items = new JsonArray();
			for (int i = 0; i < cb.getItemCount(); i++) {
				items.add(String.valueOf(cb.getItemAt(i)));
			}
			o.add("items", items);
		}
		if (c instanceof JTable tbl) {
			o.addProperty("rows", tbl.getRowCount());
			JsonArray cols = new JsonArray();
			for (int i = 0; i < tbl.getColumnCount(); i++) {
				cols.add(clean(tbl.getColumnName(i)));
			}
			o.add("columns", cols);
			if (detail) {
				JsonArray rows = new JsonArray();
				for (int r = 0; r < Math.min(tbl.getRowCount(), 100); r++) {
					JsonArray row = new JsonArray();
					for (int k = 0; k < tbl.getColumnCount(); k++) {
						Object v = tbl.getValueAt(r, k);
						row.add(v == null ? null : clean(v.toString()));
					}
					rows.add(row);
				}
				o.add("data", rows);
			}
			o.add("selectedRows", J.arr(intList(tbl.getSelectedRows())));
		}
		if (c instanceof JList<?> list) {
			o.addProperty("size", list.getModel().getSize());
			if (detail) {
				JsonArray items = new JsonArray();
				for (int i = 0; i < Math.min(list.getModel().getSize(), 200); i++) {
					items.add(String.valueOf(list.getModel().getElementAt(i)));
				}
				o.add("items", items);
			}
			o.add("selected", J.arr(intList(list.getSelectedIndices())));
		}
		if (c instanceof JTree tree && detail) {
			JsonArray rows = new JsonArray();
			for (int i = 0; i < tree.getRowCount(); i++) {
				TreePath tp = tree.getPathForRow(i);
				boolean leaf = tree.getModel().isLeaf(tp.getLastPathComponent());
				rows.add(J.obj("row", i, "depth", tp.getPathCount() - 1, "text",
						String.valueOf(tp.getLastPathComponent()), "selected", tree.isRowSelected(i),
						"leaf", leaf, "expanded", !leaf && tree.isExpanded(i)));
			}
			o.add("rows", rows);
		}
		if (c instanceof JMenu m) {
			o.addProperty("menuItems", m.getItemCount());
		}
		if (detail) {
			Rectangle b = c.getBounds();
			o.add("bounds", J.obj("x", b.x, "y", b.y, "width", b.width, "height", b.height));
		}
		return o;
	}

	private static List<Integer> intList(int[] a) {
		List<Integer> l = new ArrayList<>();
		for (int i : a) {
			l.add(i);
		}
		return l;
	}

	private static void walk(Component c, int depth, boolean all, JsonArray out, String filter) {
		if (!c.isVisible()) {
			return;
		}
		boolean include = all || interactive(c) || (c instanceof JLabel && clean(((JLabel) c).getText()) != null);
		if (include && (filter == null || matches(c, filter))) {
			out.add(describe(c, false));
		}
		if (depth == 0 || c instanceof JComboBox || c instanceof JSpinner || c instanceof JTable
				|| c instanceof JTree || c instanceof JList) {
			return;
		}
		if (c instanceof JTabbedPane tp) {
			// Only the visible tab's content
			Component sel = tp.getSelectedComponent();
			if (sel != null) {
				walk(sel, depth - 1, all, out, filter);
			}
			return;
		}
		if (c instanceof Container ct) {
			for (Component ch : ct.getComponents()) {
				walk(ch, depth - 1, all, out, filter);
			}
		}
	}

	private static boolean matches(Component c, String filter) {
		String f = filter.toLowerCase(Locale.ROOT);
		for (String s : new String[] { clean(text(c)), c.getName(), c.getClass().getSimpleName(),
				c instanceof JComponent jc ? clean(jc.getToolTipText()) : null, interactive(c) ? label(c) : null }) {
			if (s != null && s.toLowerCase(Locale.ROOT).contains(f)) {
				return true;
			}
		}
		if (c instanceof JTabbedPane tp) {
			for (int i = 0; i < tp.getTabCount(); i++) {
				if (tp.getTitleAt(i) != null && tp.getTitleAt(i).toLowerCase(Locale.ROOT).contains(f)) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ input

	static void mouseClick(Component c, int x, int y, int clicks, boolean right) {
		int button = right ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
		int mods = right ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
		long now = System.currentTimeMillis();
		for (int i = 1; i <= clicks; i++) {
			c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, mods, x, y, i, right, button));
			c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, mods, x, y, i, right, button));
			c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, mods, x, y, i, false, button));
		}
	}

	static void click(Component c, Params p) {
		int clicks = p.integer("clicks", 1);
		boolean right = p.bool("right", false);
		if (p.has("row") && (c instanceof JTable || c instanceof JList || c instanceof JTree)) {
			int row = p.integer("row");
			Rectangle r;
			if (c instanceof JTable t) {
				r = t.getCellRect(row, p.integer("column", 0), true);
				t.setRowSelectionInterval(row, row);
			} else if (c instanceof JList<?> l) {
				r = l.getCellBounds(row, row);
				l.setSelectedIndex(row);
			} else {
				JTree t = (JTree) c;
				if (p.has("expand")) {
					if (p.bool("expand", true)) {
						t.expandRow(row);
					} else {
						t.collapseRow(row);
					}
				}
				r = t.getRowBounds(row);
				t.setSelectionRow(row);
			}
			if (r == null) {
				throw AutomationException.badParam("Row " + row + " is not visible");
			}
			mouseClick(c, r.x + r.width / 2, r.y + r.height / 2, clicks, right);
			return;
		}
		if (c instanceof JTabbedPane tp && p.has("tab")) {
			tp.setSelectedIndex(tabIndex(tp, p.get("tab")));
			return;
		}
		if (c instanceof AbstractButton b && !right && clicks == 1 && !p.has("x")) {
			if (!b.isEnabled()) {
				throw AutomationException.badParam("Button '" + clean(b.getText()) + "' is disabled");
			}
			b.doClick(0);
			return;
		}
		int x = p.integer("x", c.getWidth() / 2);
		int y = p.integer("y", c.getHeight() / 2);
		mouseClick(c, x, y, clicks, right);
	}

	static int tabIndex(JTabbedPane tp, JsonElement tab) {
		if (tab.isJsonPrimitive() && tab.getAsJsonPrimitive().isNumber()) {
			return tab.getAsInt();
		}
		String want = tab.getAsString();
		for (int i = 0; i < tp.getTabCount(); i++) {
			if (want.equalsIgnoreCase(clean(tp.getTitleAt(i)))) {
				return i;
			}
		}
		for (int i = 0; i < tp.getTabCount(); i++) {
			String t = clean(tp.getTitleAt(i));
			if (t != null && t.toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT))) {
				return i;
			}
		}
		throw AutomationException.notFound("No tab '" + want + "'");
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static void setValue(Component c, JsonElement value, Params p) throws Exception {
		if (c instanceof JSpinner sp) {
			JComponent editor = sp.getEditor();
			if (sp.getModel() instanceof DoubleModel.ValueSpinnerModel vm && value.isJsonPrimitive()
					&& value.getAsString().matches(".*\\d.*[\\p{L}°%‰µ].*")) {
				// The spinner's own parser takes expressions in the displayed unit only; convert "15 mm" etc. via SI
				DoubleModel dm = vm.getDoubleModel();
				dm.setValue(ValueCodec.parseQuantity(value.getAsString(), dm.getUnitGroup()));
			} else if (editor instanceof JSpinner.DefaultEditor de) {
				// Typing into the editor goes through the spinner's formatter (units, expressions)
				de.getTextField().setText(value.getAsString());
				de.getTextField().commitEdit();
			} else {
				sp.setValue(value.getAsDouble());
			}
		} else if (c instanceof javax.swing.JFormattedTextField ft) {
			ft.setText(value.getAsString());
			ft.commitEdit();
			ft.postActionEvent();
		} else if (c instanceof javax.swing.JTextField tf) {
			tf.setText(value.getAsString());
			tf.postActionEvent();
			// Many OpenRocket fields commit on focus loss
			for (var l : tf.getFocusListeners()) {
				l.focusLost(new java.awt.event.FocusEvent(tf, java.awt.event.FocusEvent.FOCUS_LOST));
			}
		} else if (c instanceof JTextComponent t) {
			t.setText(value.getAsString());
			for (var l : t.getFocusListeners()) {
				l.focusLost(new java.awt.event.FocusEvent(t, java.awt.event.FocusEvent.FOCUS_LOST));
			}
		} else if (c instanceof JComboBox cb) {
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
				cb.setSelectedIndex(value.getAsInt());
				return;
			}
			String want = value.getAsString();
			for (int i = 0; i < cb.getItemCount(); i++) {
				if (String.valueOf(cb.getItemAt(i)).equalsIgnoreCase(want)) {
					cb.setSelectedIndex(i);
					return;
				}
			}
			for (int i = 0; i < cb.getItemCount(); i++) {
				if (String.valueOf(cb.getItemAt(i)).toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT))) {
					cb.setSelectedIndex(i);
					return;
				}
			}
			if (cb.isEditable()) {
				cb.setSelectedItem(want);
				return;
			}
			throw AutomationException.badParam("No item '" + want + "' in combo box");
		} else if (c instanceof JToggleButton tb) {
			boolean want = value.getAsBoolean();
			if (tb.isSelected() != want) {
				tb.doClick(0);
			}
		} else if (c instanceof JSlider s) {
			s.setValue(value.getAsInt());
		} else if (c instanceof JTabbedPane tp) {
			tp.setSelectedIndex(tabIndex(tp, value));
		} else if (c instanceof JTable t) {
			if (p.has("column")) {
				int row = p.integer("row");
				int col = p.integer("column");
				// Table models cast to their column type, so convert the JSON value to it
				Class<?> cls = t.getColumnClass(col);
				boolean number = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
				Object v;
				if (cls == Boolean.class) {
					v = value.getAsBoolean();
				} else if (cls == Integer.class) {
					v = value.getAsInt();
				} else if (Number.class.isAssignableFrom(cls) || (cls == Object.class && number)) {
					v = value.getAsDouble();
				} else {
					v = value.getAsString();
				}
				if (!t.isCellEditable(row, col)) {
					throw AutomationException.badParam("Cell " + row + "," + col + " is not editable");
				}
				t.setValueAt(v, row, col);
			} else {
				int row = value.getAsInt();
				t.setRowSelectionInterval(row, row);
			}
		} else if (c instanceof JList l) {
			l.setSelectedIndex(value.getAsInt());
		} else if (c instanceof JTree t) {
			t.setSelectionRow(value.getAsInt());
		} else {
			throw AutomationException.badParam("Cannot set a value on a " + c.getClass().getSimpleName());
		}
	}

	static void keys(Component target, String spec) {
		for (String stroke : spec.trim().split("\\s+")) {
			int mods = 0;
			String[] parts = stroke.split("\\+");
			for (int i = 0; i < parts.length - 1; i++) {
				switch (parts[i].toLowerCase(Locale.ROOT)) {
					case "ctrl", "control" -> mods |= InputEvent.CTRL_DOWN_MASK;
					case "shift" -> mods |= InputEvent.SHIFT_DOWN_MASK;
					case "alt", "option" -> mods |= InputEvent.ALT_DOWN_MASK;
					case "meta", "cmd", "command" -> mods |= InputEvent.META_DOWN_MASK;
					case "menu" -> mods |= java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
					default -> throw AutomationException.badParam("Unknown modifier " + parts[i]);
				}
			}
			String key = parts[parts.length - 1].toUpperCase(Locale.ROOT);
			int code;
			try {
				Field f = KeyEvent.class.getField("VK_" + key);
				code = f.getInt(null);
			} catch (ReflectiveOperationException e) {
				throw AutomationException.badParam("Unknown key " + key);
			}
			long now = System.currentTimeMillis();
			char ch = key.length() == 1 ? key.toLowerCase(Locale.ROOT).charAt(0) : KeyEvent.CHAR_UNDEFINED;
			target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, mods, code, ch));
			if (ch != KeyEvent.CHAR_UNDEFINED && (mods & ~InputEvent.SHIFT_DOWN_MASK) == 0) {
				target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch));
			}
			target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, mods, code, ch));
		}
	}

	static void type(Component target, String text) {
		long now = System.currentTimeMillis();
		for (char ch : text.toCharArray()) {
			target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch));
		}
	}

	// ------------------------------------------------------------------ menus

	static JMenuBar menuBar(Window w) {
		if (w instanceof JFrame f) {
			return f.getJMenuBar();
		}
		if (w instanceof JDialog d) {
			return d.getJMenuBar();
		}
		return null;
	}

	private static void menuTree(JMenuItem item, String prefix, JsonArray out) {
		String name = clean(item.getText());
		if (name == null) {
			return;
		}
		String path = prefix.isEmpty() ? name : prefix + " / " + name;
		if (item instanceof JMenu m) {
			for (var l : m.getMenuListeners()) {
				// Populate dynamic menus (e.g. recent files)
				l.menuSelected(new javax.swing.event.MenuEvent(m));
			}
			for (int i = 0; i < m.getItemCount(); i++) {
				if (m.getItem(i) != null) {
					menuTree(m.getItem(i), path, out);
				}
			}
		} else {
			JsonObject o = J.obj("path", path, "enabled", item.isEnabled());
			if (item.getAccelerator() != null) {
				o.addProperty("shortcut", item.getAccelerator().toString().replace("pressed ", ""));
			}
			if (item instanceof javax.swing.JCheckBoxMenuItem || item instanceof javax.swing.JRadioButtonMenuItem) {
				o.addProperty("selected", item.isSelected());
			}
			out.add(o);
		}
	}

	static JMenuItem findMenu(Window w, String path) {
		JMenuBar bar = menuBar(w);
		if (bar == null) {
			throw AutomationException.notFound("Window '" + title(w) + "' has no menu bar");
		}
		String[] parts = path.split("\\s*[/>]\\s*");
		JMenuItem cur = null;
		for (int i = 0; i < bar.getMenuCount(); i++) {
			JMenu m = bar.getMenu(i);
			if (m != null && same(m.getText(), parts[0])) {
				cur = m;
			}
		}
		for (int k = 1; k < parts.length && cur instanceof JMenu m; k++) {
			for (var l : m.getMenuListeners()) {
				l.menuSelected(new javax.swing.event.MenuEvent(m));
			}
			JMenuItem next = null;
			for (int i = 0; i < m.getItemCount(); i++) {
				if (m.getItem(i) != null && same(m.getItem(i).getText(), parts[k])) {
					next = m.getItem(i);
				}
			}
			cur = next;
		}
		if (cur == null) {
			throw AutomationException.notFound("No menu item '" + path + "'. Use gui.menus.");
		}
		return cur;
	}

	private static boolean same(String a, String b) {
		String x = clean(a);
		if (x == null) {
			return false;
		}
		String n = x.replace("…", "").replace("...", "").trim();
		String m = b.replace("…", "").replace("...", "").trim();
		// Labels such as "Undo (Modify Nose cone)" also match "Undo"
		return n.equalsIgnoreCase(m) || n.replaceFirst("\\s*\\(.*\\)$", "").equalsIgnoreCase(m);
	}

	// ------------------------------------------------------------------ images

	static BufferedImage paint(Component c, double scale) {
		int w = Math.max(1, (int) Math.round(c.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(c.getHeight() * scale));
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		c.printAll(g);
		g.dispose();
		return img;
	}

	static BufferedImage screen(Component c, double scale) throws AWTException {
		Point p = c.getLocationOnScreen();
		BufferedImage img = new Robot().createScreenCapture(new Rectangle(p.x, p.y, c.getWidth(), c.getHeight()));
		if (scale == 1) {
			return img;
		}
		int w = Math.max(1, (int) (img.getWidth() * scale));
		int h = Math.max(1, (int) (img.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	static JsonObject image(byte[] png, Params p) throws Exception {
		JsonObject o = new JsonObject();
		if (p.has("path")) {
			File f = new File(p.string("path"));
			java.nio.file.Files.write(f.toPath(), png);
			o.addProperty("written", f.getAbsolutePath());
		}
		if (p.bool("inline", true)) {
			o.add("_image", J.obj("mimeType", "image/png", "data", Base64.getEncoder().encodeToString(png)));
		}
		o.addProperty("bytes", png.length);
		return o;
	}

	static byte[] png(BufferedImage img) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	static RocketPanel.VIEW_TYPE viewType(String name) {
		RocketPanel.VIEW_TYPE view = RocketPanel.VIEW_TYPE.fromName(name);
		if (view == null) {
			for (RocketPanel.VIEW_TYPE v : RocketPanel.VIEW_TYPE.values()) {
				if (v.name().equalsIgnoreCase(name) && v != RocketPanel.VIEW_TYPE.SEPARATOR) {
					return v;
				}
			}
			throw AutomationException.badParam("Unknown view '" + name
					+ "'. Use SideView, TopView, BackView, Figure3D, Unfinished or Finished.");
		}
		return view;
	}

	static BasicFrame frame(AutomationSession s, Params p) throws Exception {
		OpenRocketDocument doc = s.document(p);
		BasicFrame f = GuiDocumentHost.frame(doc);
		if (f == null) {
			throw AutomationException.notFound("Document has no window");
		}
		return f;
	}

	/** Pause briefly so that events posted by an action (e.g. a dialog opening) are processed. */
	static JsonArray settle() throws Exception {
		Thread.sleep(250);
		JsonArray[] holder = new JsonArray[1];
		SwingUtilities.invokeAndWait(() -> holder[0] = listWindows());
		return holder[0];
	}

	static JsonArray listWindows() {
		JsonArray a = new JsonArray();
		Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		for (Window w : windows()) {
			JsonObject o = J.obj("id", "w" + windowId(w), "title", title(w), "type", w.getClass().getSimpleName(),
					"modal", w instanceof Dialog d && d.isModal(), "active", w == active);
			Rectangle b = w.getBounds();
			o.add("bounds", J.obj("x", b.x, "y", b.y, "width", b.width, "height", b.height));
			if (w instanceof BasicFrame bf) {
				o.addProperty("document", bf.getDocument().getRocket().getName());
			}
			a.add(o);
		}
		return a;
	}

	// ------------------------------------------------------------------ commands

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("gui.windows").readOnly()
				.description("List the open windows and dialogs (ids, titles, modality, bounds).")
				.handler((p, s) -> listWindows()).build());

		l.add(Command.builder("gui.tree").readOnly()
				.description("The widgets of a window: buttons, fields, combo boxes, checkboxes, tabs, tables, labels... "
						+ "with ids for gui.click / gui.set_value. Only the selected tab of tabbed panes is walked.")
				.param("window", "string", "Window id, title, or 'active' (default: the active / topmost window)")
				.param("root", "string", "Widget id to start from")
				.param("all", "boolean", "Include layout containers too (default: only interactive widgets and labels)")
				.param("depth", "integer", "Maximum depth (default unlimited)")
				.handler((p, s) -> {
					Component root = p.has("root") ? component(p.string("root")) : window(p.string("window", null));
					JsonArray out = new JsonArray();
					walk(root, p.integer("depth", -1), p.bool("all", false), out, null);
					Window w = root instanceof Window ww ? ww : SwingUtilities.getWindowAncestor(root);
					return J.obj("window", "w" + windowId(w), "title", title(w), "widgets", out);
				}).build());

		l.add(Command.builder("gui.find").readOnly()
				.description("Find widgets whose text, label, tooltip, name or type contains a string.")
				.required("text", "string", "Text to search for")
				.param("window", "string", "Window (default: all windows)")
				.handler((p, s) -> {
					JsonArray out = new JsonArray();
					List<Window> ws = p.has("window") ? List.of(window(p.string("window"))) : windows();
					for (Window w : ws) {
						walk(w, -1, true, out, p.string("text"));
					}
					return out;
				}).build());

		l.add(Command.builder("gui.inspect").readOnly()
				.description("Details of one widget: text, value, items of combo boxes/lists, table contents, tree rows, bounds.")
				.required("id", "string", "Widget id")
				.handler((p, s) -> describe(component(p.string("id")), true)).build());

		l.add(Command.builder("gui.click")
				.description("Click a widget: press a button / menu item / checkbox, select a tab ('tab'), click a "
						+ "table/list/tree row ('row', double-click with clicks=2), or click at x,y. Returns the windows "
						+ "afterwards so you can see dialogs it opened.")
				.required("id", "string", "Widget id")
				.param("row", "integer", "Row of a table / list / tree")
				.param("column", "integer", "Column of a table")
				.param("tab", "ref", "Tab title or index of a tabbed pane")
				.param("expand", "boolean", "With 'row' on a tree: expand (true) or collapse (false) that row first")
				.param("clicks", "integer", "Number of clicks (2 = double click)")
				.param("right", "boolean", "Right-click (context menu)")
				.param("x", "integer", "X within the widget")
				.param("y", "integer", "Y within the widget")
				.anyThread()
				.handler((p, s) -> {
					Component c = s.getHost().run(() -> component(p.string("id")));
					// invokeLater: the click may open a modal dialog that blocks until closed
					SwingUtilities.invokeLater(() -> click(c, p));
					return J.obj("windows", settle());
				}).build());

		l.add(Command.builder("gui.set_value")
				.description("Set the value of a widget: text fields (with units, e.g. \"5 cm\"), spinners, combo boxes "
						+ "(item text or index), checkboxes/toggles (true/false), sliders, tabs, table rows/cells, list "
						+ "and tree selection.")
				.required("id", "string", "Widget id")
				.required("value", "any", "New value")
				.param("row", "integer", "Table row (with 'column' to edit a cell)")
				.param("column", "integer", "Table column")
				.anyThread()
				.handler((p, s) -> {
					Component c = s.getHost().run(() -> component(p.string("id")));
					s.getHost().run(() -> {
						setValue(c, p.require("value"), p);
						return null;
					});
					return J.obj("widget", s.getHost().run(() -> describe(c, false)), "windows", settle());
				}).build());

		l.add(Command.builder("gui.key")
				.description("Send key strokes (e.g. \"ctrl+Z\", \"ENTER\", \"menu+S\" — menu = Cmd on macOS, Ctrl "
						+ "elsewhere; space separated) and/or type text into a widget (default: the focused one).")
				.param("id", "string", "Target widget (default: focus owner of the active window)")
				.param("keys", "string", "Key strokes")
				.param("text", "string", "Text to type")
				.anyThread()
				.handler((p, s) -> {
					Component c = s.getHost().run(() -> {
						if (p.has("id")) {
							return component(p.string("id"));
						}
						Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
						return f != null ? f : window(null);
					});
					SwingUtilities.invokeLater(() -> {
						if (p.has("text")) {
							type(c, p.string("text"));
						}
						if (p.has("keys")) {
							keys(c, p.string("keys"));
						}
					});
					return J.obj("windows", settle());
				}).build());

		l.add(Command.builder("gui.menus").readOnly()
				.description("All menu items of a window's menu bar, as paths for gui.menu (e.g. \"File / Save as\"), "
						+ "with shortcuts and enabled state.")
				.param("window", "string", "Window (default: the active design window)")
				.handler((p, s) -> {
					Window w = p.has("window") ? window(p.string("window")) : defaultFrame(s);
					JMenuBar bar = menuBar(w);
					JsonArray out = new JsonArray();
					if (bar != null) {
						for (int i = 0; i < bar.getMenuCount(); i++) {
							if (bar.getMenu(i) != null) {
								menuTree(bar.getMenu(i), "", out);
							}
						}
					}
					return out;
				}).build());

		l.add(Command.builder("gui.menu")
				.description("Invoke a menu item by path, e.g. \"Edit / Undo\", \"Analyze / Component analysis\", "
						+ "\"File / Export as / Wavefront OBJ\". Returns the windows afterwards (dialogs it opened).")
				.required("path", "string", "Menu path with '/' separators")
				.param("window", "string", "Window (default: the active design window)")
				.anyThread()
				.handler((p, s) -> {
					JMenuItem item = s.getHost().run(() -> {
						Window w = p.has("window") ? window(p.string("window")) : defaultFrame(s);
						return findMenu(w, p.string("path"));
					});
					if (!item.isEnabled()) {
						throw AutomationException.badParam("Menu item '" + p.string("path") + "' is disabled");
					}
					SwingUtilities.invokeLater(() -> item.doClick(0));
					return J.obj("invoked", p.string("path"), "windows", settle());
				}).build());

		l.add(Command.builder("gui.screenshot").readOnly()
				.description("Screenshot of a window or widget as PNG (returned as an image, optionally saved). "
						+ "method 'paint' renders the Swing tree (works when covered/off-screen); 'screen' captures the "
						+ "screen pixels (needed for the 3D view; may need screen-recording permission).")
				.param("window", "string", "Window (default: active)")
				.param("id", "string", "Widget id instead of a whole window")
				.param("method", "string", "paint (default) or screen")
				.param("scale", "number", "Scale factor (default 1)")
				.param("path", "string", "Also save the PNG here")
				.param("inline", "boolean", "Return the image data (default true)")
				.anyThread()
				.handler((p, s) -> {
					Component c = s.getHost().run(() -> p.has("id") ? component(p.string("id")) : window(p.string("window", null)));
					double scale = p.number("scale", 1);
					BufferedImage img = "screen".equals(p.string("method", "paint")) ? screen(c, scale)
							: s.getHost().run(() -> paint(c, scale));
					JsonObject o = image(png(img), p);
					o.addProperty("width", img.getWidth());
					o.addProperty("height", img.getHeight());
					return o;
				}).build());

		l.add(Command.builder("gui.render")
				.description("Render the rocket of a document as a PNG: side/top/back 2D figure or 3D (Figure3D, "
						+ "Unfinished, Finished) view.")
				.doc()
				.param("view", "string", "SideView (default), TopView, BackView, Figure3D, Unfinished, Finished")
				.param("width", "integer", "Image width in pixels (default 1200)")
				.param("path", "string", "Also save the PNG here")
				.param("inline", "boolean", "Return the image data (default true)")
				.anyThread()
				.handler((p, s) -> {
					RocketPanel panel = s.getHost().run(() -> frame(s, p).getRocketPanel());
					RocketPanel.VIEW_TYPE view = viewType(p.string("view", "SideView"));
					int w = p.integer("width", 1200);
					// The 3D figure can only be captured while it is the displayed view, once OpenGL is ready
					RocketPanel.VIEW_TYPE previous = s.getHost().run(panel::getCurrentViewType);
					boolean switched = view.is3d && previous != view;
					if (switched) {
						s.getHost().run(() -> {
							panel.setViewType(view);
							return null;
						});
					}
					try {
						byte[] png;
						long deadline = System.currentTimeMillis() + 10000;
						while (true) {
							png = s.getHost().run(() -> panel.createPreviewPng(view, w, w / 6, w));
							if (png != null || !view.is3d || System.currentTimeMillis() > deadline) {
								break;
							}
							Thread.sleep(200);
						}
						if (png == null) {
							throw new AutomationException(view.is3d ? "3D rendering failed (OpenGL unavailable?); "
									+ "try a 2D view" : "Rendering failed (empty rocket?)");
						}
						return image(png, p);
					} finally {
						if (switched) {
							s.getHost().run(() -> {
								panel.setViewType(previous);
								return null;
							});
						}
					}
				}).build());

		l.add(Command.builder("gui.view")
				.description("Switch the design window's rocket view (SideView, TopView, BackView, Figure3D, Unfinished, Finished).")
				.doc()
				.required("view", "string", "View type")
				.handler((p, s) -> {
					BasicFrame f = frame(s, p);
					f.getRocketPanel().setViewType(viewType(p.string("view")));
					return J.obj("view", f.getRocketPanel().getCurrentViewType().name());
				}).build());

		l.add(Command.builder("gui.tab")
				.description("Select the main tab of a design window: design, flight_configurations or simulations.")
				.doc()
				.required("tab", "string", "design, flight_configurations or simulations")
				.handler((p, s) -> {
					BasicFrame f = frame(s, p);
					String t = p.string("tab").toLowerCase(Locale.ROOT);
					int tab = t.startsWith("sim") ? BasicFrame.SIMULATION_TAB
							: t.startsWith("flight") || t.startsWith("motor") || t.startsWith("config")
									? BasicFrame.FLIGHT_CONFIGURATION_TAB : BasicFrame.DESIGN_TAB;
					f.selectTab(tab);
					return J.obj("tab", f.getSelectedTab());
				}).build());

		l.add(Command.builder("gui.selection")
				.description("Get, or set, the components (and simulations) selected in a design window.")
				.doc()
				.param("components", "array", "Component references to select")
				.param("simulations", "array", "Simulation indices or names to select")
				.handler((p, s) -> {
					BasicFrame f = frame(s, p);
					OpenRocketDocument doc = f.getDocument();
					if (p.has("components")) {
						List<RocketComponent> sel = new ArrayList<>();
						for (String ref : p.strings("components")) {
							sel.add(s.component(doc, ref));
						}
						f.setSelectedComponents(sel);
					}
					if (p.has("simulations")) {
						List<Simulation> sims = new ArrayList<>();
						for (JsonElement e : p.array("simulations")) {
							sims.add(s.simulation(doc, e));
						}
						f.getSelectionModel().setSelectedSimulations(sims.toArray(new Simulation[0]));
					}
					JsonArray comps = new JsonArray();
					f.getSelectionModel().getSelectedComponents().forEach(c -> comps.add(Describe.componentRef(c)));
					JsonArray sims = new JsonArray();
					for (Simulation sim : f.getSelectionModel().getSelectedSimulations()) {
						sims.add(sim.getName());
					}
					return J.obj("components", comps, "simulations", sims);
				}).build());

		l.add(Command.builder("gui.edit_component")
				.description("Open the component configuration dialog for a component (as double-clicking it does). "
						+ "Operate it with gui.tree / gui.set_value / gui.click.")
				.doc()
				.required("component", "string", "Component reference")
				.anyThread()
				.handler((p, s) -> {
					BasicFrame f = s.getHost().run(() -> frame(s, p));
					RocketComponent c = s.getHost().run(() -> s.component(f.getDocument(), p.string("component")));
					SwingUtilities.invokeLater(() -> {
						f.setSelectedComponent(c);
						ComponentConfigDialog.showDialog(f, f.getDocument(), c);
					});
					return J.obj("windows", settle());
				}).build());

		l.add(Command.builder("gui.close_window")
				.description("Close a dialog or window (like its close button). For design windows use doc.close.")
				.required("window", "string", "Window id or title")
				.handler((p, s) -> {
					Window w = window(p.string("window"));
					if (w instanceof BasicFrame) {
						throw AutomationException.badParam("Use doc.close for design windows");
					}
					w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
					return J.obj("windows", listWindows());
				}).build());

		l.add(Command.builder("gui.focus")
				.description("Bring a window to the front and focus it.")
				.required("window", "string", "Window id or title")
				.handler((p, s) -> {
					Window w = window(p.string("window"));
					w.toFront();
					w.requestFocus();
					return J.obj("window", "w" + windowId(w));
				}).build());

		return l;
	}

	static Window defaultFrame(AutomationSession s) {
		Window a = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (a instanceof BasicFrame) {
			return a;
		}
		OpenRocketDocument doc = s.getHost().active();
		if (doc != null && GuiDocumentHost.frame(doc) != null) {
			return GuiDocumentHost.frame(doc);
		}
		for (Window w : windows()) {
			if (w instanceof JFrame f && f.getJMenuBar() != null) {
				return w;
			}
		}
		throw AutomationException.notFound("No design window is open");
	}
}
