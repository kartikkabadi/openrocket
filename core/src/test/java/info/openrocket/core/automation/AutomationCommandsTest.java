package info.openrocket.core.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.automation.commands.StandardCommands;
import info.openrocket.core.automation.protocol.LocalBackend;
import info.openrocket.core.automation.protocol.McpServer;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.BaseTestCase;

public class AutomationCommandsTest extends BaseTestCase {
	private AutomationSession session;

	@BeforeEach
	public void createSession() {
		session = new AutomationSession(new HeadlessDocumentHost(), StandardCommands.registry());
	}

	private JsonElement call(String command, Object... kv) throws Exception {
		return session.call(command, J.obj(kv));
	}

	private void buildRocket() throws Exception {
		call("doc.new", "name", "Test");
		call("component.add", "type", "NoseCone", "name", "Nose",
				"properties", J.obj("length", "10 cm", "aftRadius", "1.2 cm", "shapeType", "OGIVE"));
		call("component.add", "type", "BodyTube", "name", "Body",
				"properties", J.obj("length", "30 cm", "outerRadius", 0.012));
		call("component.add", "type", "fins", "parent", "Body",
				"properties", J.obj("finCount", 3, "rootChord", "6 cm", "tipChord", "3 cm", "height", "4 cm"));
	}

	@Test
	public void quantitiesAreConvertedToSI() {
		assertEquals(0.05, ValueCodec.parseQuantity("5 cm", null), 1e-12);
		assertEquals(0.0254, ValueCodec.parseQuantity("1in", UnitGroup.UNITS_LENGTH), 1e-12);
		assertEquals(Math.PI / 6, ValueCodec.parseQuantity("30 deg", UnitGroup.UNITS_ANGLE), 1e-12);
		assertEquals(0.002, ValueCodec.parseQuantity("2 g", UnitGroup.UNITS_MASS), 1e-12);
		assertThrows(AutomationException.class, () -> ValueCodec.parseQuantity("5 bananas", null));
		assertEquals(1600.0, ValueCodec.parseQuantity("1.6 g/cm3", UnitGroup.UNITS_DENSITY_BULK), 1e-9);
		assertEquals(9.81, ValueCodec.parseQuantity("9.81 m/s^2", null), 1e-12);
		assertEquals(30.0, ValueCodec.parseQuantity("30 deg", UnitGroup.UNITS_LATITUDE), 1e-12);
		assertEquals(-80.6, ValueCodec.parseQuantity("80.6 W", UnitGroup.UNITS_LONGITUDE), 1e-12);
		assertEquals(UnitGroup.UNITS_LENGTH, BeanAccess.unitHint("sweep"));
		assertEquals(UnitGroup.UNITS_ANGLE, BeanAccess.unitHint("sweepAngle"));
		assertEquals(UnitGroup.UNITS_LATITUDE, BeanAccess.unitHint("launchLatitude"));
	}

	@Test
	public void buildEditAndUndo() throws Exception {
		buildRocket();
		JsonObject tree = call("component.tree").getAsJsonObject();
		JsonObject stage = tree.getAsJsonArray("children").get(0).getAsJsonObject();
		assertEquals(2, stage.getAsJsonArray("children").size());

		JsonObject body = call("component.get", "component", "Body", "names", J.arr(java.util.List.of("length")))
				.getAsJsonObject();
		assertEquals(0.3, body.getAsJsonObject("properties").getAsJsonObject("length").get("value").getAsDouble(), 1e-9);

		call("component.set", "component", "Body", "properties", J.obj("length", "40 cm", "material", "Fiberglass"));
		JsonObject props = call("component.get", "component", "Body").getAsJsonObject().getAsJsonObject("properties");
		assertEquals(0.4, props.getAsJsonObject("length").get("value").getAsDouble(), 1e-9);
		assertEquals("Fiberglass", props.getAsJsonObject("material").getAsJsonObject("value").get("name").getAsString());

		call("doc.undo");
		props = call("component.get", "component", "Body").getAsJsonObject().getAsJsonObject("properties");
		assertEquals(0.3, props.getAsJsonObject("length").get("value").getAsDouble(), 1e-9);
	}

	@Test
	public void componentReferences() throws Exception {
		buildRocket();
		JsonObject fins = call("component.get", "component", "FinSet#0").getAsJsonObject();
		assertEquals("TrapezoidFinSet", fins.get("type").getAsString());
		JsonObject byPath = call("component.get", "component", "Sustainer/Body").getAsJsonObject();
		assertEquals("Body", byPath.get("name").getAsString());
		JsonObject byId = call("component.get", "component", fins.get("id").getAsString()).getAsJsonObject();
		assertEquals(fins.get("id"), byId.get("id"));
		assertThrows(AutomationException.class, () -> call("component.get", "component", "No such thing"));

		call("component.add", "type", "InnerTube", "parent", "Body", "name", "Mount");
		call("component.set", "component", "Mount", "properties", J.obj("clusterConfiguration", "3-ring"));
		JsonObject mount = call("component.get", "component", "Mount", "names", List.of("clusterConfiguration"))
				.getAsJsonObject();
		assertEquals("3-ring", mount.getAsJsonObject("properties").getAsJsonObject("clusterConfiguration")
				.get("value").getAsString());
	}

	@Test
	public void incompatiblePlacementIsRejected() throws Exception {
		buildRocket();
		AutomationException e = assertThrows(AutomationException.class,
				() -> call("component.add", "type", "TrapezoidFinSet", "parent", "rocket"));
		assertTrue(e.getMessage().contains("cannot be placed"));
	}

	@Test
	public void stabilityAndGenericAccess() throws Exception {
		buildRocket();
		JsonObject st = call("analysis.stability").getAsJsonObject();
		assertTrue(st.get("cp").getAsDouble() > st.get("cg").getAsDouble(), "fins should put CP behind CG");
		assertEquals(0.4, st.get("length").getAsDouble(), 1e-9);

		JsonObject r = call("object.call", "target", "component:Body", "method", "getOuterRadius").getAsJsonObject();
		assertEquals(0.012, r.get("result").getAsDouble(), 1e-12);

		call("object.set", "target", "rocket", "properties", J.obj("designer", "CI"));
		JsonObject info = call("doc.info").getAsJsonObject();
		assertEquals("CI", info.getAsJsonObject("rocket").get("designer").getAsString());
	}

	@Test
	public void simulationOptions() throws Exception {
		buildRocket();
		call("sim.create", "name", "Windy", "options", J.obj("windSpeedAverage", "5 m/s", "launchRodLength", "1.5 m"));
		JsonObject sim = call("sim.get", "sim", "Windy").getAsJsonObject();
		assertEquals(5.0, sim.getAsJsonObject("options").get("windSpeedAverage").getAsDouble(), 1e-9);
		assertEquals(1.5, sim.getAsJsonObject("options").get("launchRodLength").getAsDouble(), 1e-9);
		call("sim.set", "sim", "Windy", "options", J.obj("launchLatitude", "32.99 deg", "launchLongitude", "106.97 W"));
		sim = call("sim.get", "sim", "Windy").getAsJsonObject();
		assertEquals(32.99, sim.getAsJsonObject("options").get("launchLatitude").getAsDouble(), 1e-9);
		assertEquals(-106.97, sim.getAsJsonObject("options").get("launchLongitude").getAsDouble(), 1e-9);
	}

	@Test
	public void mcpHandshakeAndToolCall() throws Exception {
		String input = String.join("\n",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"doc_new\",\"arguments\":{\"name\":\"M\"}}}",
				"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"component_get\",\"arguments\":{\"component\":\"x\"}}}",
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}") + "\n";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new McpServer(new LocalBackend(session), new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).run();
		String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
		assertEquals(5, lines.length, "notifications get no response");

		JsonObject init = JsonParser.parseString(lines[0]).getAsJsonObject().getAsJsonObject("result");
		assertEquals("2025-06-18", init.get("protocolVersion").getAsString());

		JsonArray tools = JsonParser.parseString(lines[1]).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
		assertTrue(tools.size() > 50);
		java.util.Map<String, Boolean> readOnly = new java.util.HashMap<>();
		for (JsonElement t : tools) {
			String name = t.getAsJsonObject().get("name").getAsString();
			assertTrue(name.matches("[a-zA-Z0-9_-]+"));
			readOnly.put(name, t.getAsJsonObject().getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
		}
		assertTrue(readOnly.get("sim_list"));
		assertFalse(readOnly.get("doc_save"));
		assertFalse(readOnly.get("pref_set"));
		assertFalse(readOnly.get("export_obj"));

		JsonObject ok = JsonParser.parseString(lines[2]).getAsJsonObject().getAsJsonObject("result");
		assertFalse(ok.get("isError").getAsBoolean());
		JsonObject err = JsonParser.parseString(lines[3]).getAsJsonObject().getAsJsonObject("result");
		assertTrue(err.get("isError").getAsBoolean());
		assertEquals(-32601, JsonParser.parseString(lines[4]).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
	}
}
