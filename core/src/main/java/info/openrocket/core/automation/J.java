package info.openrocket.core.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small helpers for building JSON results. */
public final class J {
	private J() {
	}

	/** Build an object from alternating keys and values (values go through {@link ValueCodec}). */
	public static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			o.add(String.valueOf(kv[i]), ValueCodec.toJson(kv[i + 1]));
		}
		return o;
	}

	public static JsonArray arr(Iterable<?> items) {
		JsonArray a = new JsonArray();
		for (Object x : items) {
			a.add(ValueCodec.toJson(x));
		}
		return a;
	}

	public static JsonElement ok() {
		return obj("ok", true);
	}
}
