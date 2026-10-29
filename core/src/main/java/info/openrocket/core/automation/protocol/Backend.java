package info.openrocket.core.automation.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Something that executes automation commands: an in-process session or a connection to a running
 * OpenRocket instance.
 */
public interface Backend extends AutoCloseable {

	/** Descriptions of all commands: [{name, description, inputSchema, mutating}]. */
	JsonArray listCommands() throws Exception;

	/** Run a command. Throws {@link info.openrocket.core.automation.AutomationException} on failure. */
	JsonElement call(String command, JsonObject params) throws Exception;

	@Override
	default void close() {
	}
}
