package info.openrocket.core.automation.protocol;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutionException;

import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;

import org.slf4j.LoggerFactory;

/** Converts exceptions into the error objects returned to clients. */
public final class Errors {
	private Errors() {
	}

	public static Throwable unwrap(Throwable e) {
		Throwable t = e;
		while ((t instanceof InvocationTargetException || t instanceof ExecutionException
				|| (t instanceof RuntimeException && t.getClass() == RuntimeException.class && t.getMessage() != null
						&& t.getCause() != null && t.getMessage().equals(t.getCause().toString())))
				&& t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	public static JsonObject toJson(Throwable e) {
		Throwable t = unwrap(e);
		JsonObject o = new JsonObject();
		if (t instanceof AutomationException ae) {
			o.addProperty("code", ae.getCode());
			o.addProperty("message", ae.getMessage());
		} else {
			LoggerFactory.getLogger(Errors.class).warn("Automation command failed", t);
			o.addProperty("code", "internal_error");
			String msg = t.getMessage();
			o.addProperty("message", t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
		}
		return o;
	}
}
