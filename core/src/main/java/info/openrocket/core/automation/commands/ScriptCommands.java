package info.openrocket.core.automation.commands;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;

import com.google.gson.JsonParser;

import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.J;
import info.openrocket.core.automation.ValueCodec;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.scripting.GraalJSScriptEngineFactory;
import info.openrocket.core.startup.Application;

/**
 * Run JavaScript with full access to the OpenRocket Java API. Anything the application can do is
 * reachable from here, including features that have no dedicated command.
 */
public final class ScriptCommands {
	private ScriptCommands() {
	}

	/** Bridge object exposed to scripts as {@code or}. */
	public static final class Bridge {
		private final AutomationSession session;

		Bridge(AutomationSession session) {
			this.session = session;
		}

		/** Run an automation command; params and result are JSON strings. */
		public String call(String command, String paramsJson) throws Exception {
			var params = paramsJson == null || paramsJson.isBlank() ? new com.google.gson.JsonObject()
					: JsonParser.parseString(paramsJson).getAsJsonObject();
			return session.getRegistry().get(command) == null ? null
					: session.getRegistry().get(command).getHandler().run(new info.openrocket.core.automation.Params(params),
							session).toString();
		}

		public OpenRocketDocument doc(String ref) throws Exception {
			return session.document(ref);
		}

		public Object component(String docRef, String ref) throws Exception {
			return session.component(session.document(docRef), ref);
		}

		public java.util.Map<String, Object> vars() {
			return session.getVariables();
		}

		public Class<?> type(String name) throws ClassNotFoundException {
			return Class.forName(name);
		}
	}

	private static final String PRELUDE = """
			var call = function(name, params) {
			  var r = or.call(name, JSON.stringify(params || {}));
			  return r == null ? null : JSON.parse(r);
			};
			""";

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();

		l.add(Command.builder("script.eval")
				.description("Evaluate JavaScript (GraalJS) with full access to the OpenRocket Java API. Globals: "
						+ "'doc' (active document or null), 'rocket', 'session', 'Application', 'vars' (persistent map), "
						+ "'or' (bridge: or.doc(ref), or.component(docRef, ref), or.type(className)), "
						+ "'call(name, params)' (run any automation command, returns parsed JSON), "
						+ "'print(...)' (captured output), and Java.type('fully.qualified.Class'). "
						+ "The value of the last expression is returned.")
				.required("code", "string", "JavaScript source")
				.doc()
				.mutating()
				.handler((p, s) -> {
					ScriptEngine engine = new GraalJSScriptEngineFactory().getScriptEngine();
					StringWriter out = new StringWriter();
					engine.getContext().setWriter(new PrintWriter(out, true));
					engine.getContext().setErrorWriter(new PrintWriter(out, true));
					Bindings b = engine.getBindings(ScriptContext.ENGINE_SCOPE);
					OpenRocketDocument doc = null;
					try {
						doc = s.document(p);
					} catch (RuntimeException ignored) {
					}
					b.put("doc", doc);
					b.put("rocket", doc == null ? null : doc.getRocket());
					b.put("session", s);
					b.put("Application", Application.class);
					b.put("vars", s.getVariables());
					b.put("or", new Bridge(s));
					engine.eval(PRELUDE);
					if (doc != null) {
						doc.addUndoPosition("Script");
						s.markDirty(doc);
					}
					Object r = engine.eval(p.string("code"));
					return J.obj("result", ValueCodec.toJson(r), "output", out.toString());
				}).build());

		return l;
	}
}
