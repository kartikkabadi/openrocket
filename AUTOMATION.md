# OpenRocket automation: CLI and MCP

Everything OpenRocket does can be driven programmatically, both headless and against the running GUI.
All of it goes through one command engine (`core/.../automation`), exposed four ways:

| Interface | Start with | Use it for |
|---|---|---|
| CLI | `bin/openrocket cli <command> --param value` | scripts, CI, one-off queries |
| MCP server (stdio) | `bin/openrocket mcp` | AI agents (Claude Code, Claude Desktop, …) |
| GUI + automation server | `bin/openrocket gui` | watching/controlling the real app; `cli --connect`, `mcp --connect` |
| Headless server | `bin/openrocket serve` | a long-lived session shared by many CLI calls |

`bin/openrocket` runs the jar from `./gradlew shadowJar -x test` with Java 17 or 21 (`JAVA_HOME` or
`OPENROCKET_JAVA_HOME`). The jar works the same way: `java -jar OpenRocket.jar cli ...`.

## Commands

`bin/openrocket cli help` lists every command; `bin/openrocket cli help <command>` shows its parameters.

| Group | Commands |
|---|---|
| doc | `new`, `open`, `examples`, `list`, `info`, `activate`, `save`, `close`, `undo`, `redo` |
| component | `tree`, `get`, `set`, `add`, `remove`, `move`, `duplicate`, `types`, `preset`, `fin_points` |
| config | `list`, `create`, `remove`, `select`, `rename`, `set_motor`, `remove_motor`, `set_stages`, `set_deployment`, `set_separation` |
| sim | `list`, `get`, `create`, `set`, `remove`, `duplicate`, `run`, `events`, `data`, `data_types`, `export_csv`, `extensions`, `add_extension`, `remove_extension` |
| analysis | `stability` (CG/CP/margin/mass), `mass`, `aero` (per-component drag/CNα), `sweep` |
| motor / preset / material | `motor.search`, `motor.get` (thrust curve), `preset.search`, `material.list`, `material.add` |
| export | `rocksim`, `rasaero`, `obj` (`.ork` is `doc.save`) |
| pref / units | `pref.get`, `pref.set`, `units.list`, `units.set_preferred`, `units.convert` |
| object | `get`, `set`, `call`, `methods`: any getter, setter or public method of any model object |
| script | `eval`: JavaScript (GraalJS) with the whole Java API |
| gui (GUI only) | `windows`, `tree`, `find`, `inspect`, `click`, `set_value`, `key`, `menus`, `menu`, `screenshot`, `render`, `view`, `tab`, `selection`, `edit_component`, `close_window`, `focus` |

The `object.*`, `script.eval` and `gui.*` commands are the escape hatches. Anything without its own
command can still be reached through the model (`object.*`, `script.eval`) or the user interface
(`gui.*`: every menu item, dialog, field and button).

### Conventions

- **Units**: results are SI (m, kg, s, rad, N). Inputs accept SI numbers or strings with units:
  `"30 cm"`, `"2.5 in"`, `"5 deg"`, `"10 mph"`, `"12 g"`.
- **Documents**: `doc` is an id from `doc.list`, a file path (opened on demand), or omitted for the active document.
- **Components**: an id, a unique name, a name path (`"Sustainer/Body tube/Fins"`), `"Type#n"` (e.g. `"FinSet#0"`),
  or `"rocket"`. `component.get` lists every property with its type, allowed values and whether it can be written.
- **Simulations**: a 0-based index or a name. **Flight configurations**: an id, a name, an index, or `selected`.
- **Undo**: editing commands add undo steps like GUI edits do (`doc.undo`, or Edit → Undo in the GUI).
- **object targets**: `rocket`, `document`, `preferences`, `component:<ref>`, `sim:<ref>`, `sim-options:<ref>`,
  `sim-extension:<sim>:<i>`, `config:<ref>`, `motor-config:<mount>@<config>`, `deployment:<device>@<config>`,
  `separation:<stage>@<config>`, `var:<name>`.

## CLI

```bash
bin/openrocket cli doc.examples
bin/openrocket cli sim.run --doc "my rocket.ork"
bin/openrocket cli component.set --doc rocket.ork --component "Nose cone" --properties '{"length":"12 cm"}' --save
bin/openrocket cli motor.search --impulseClass C --diameter "18 mm"
```

Parameter values that parse as JSON are passed as JSON; anything else is a string. `--json '{...}'` or
`--json @file.json` passes all parameters at once. `--save` writes changed documents back to their files.
`--connect` operates a running GUI or server instead of a private headless session. Results go to
stdout as JSON; errors go to stderr, and the exit status is 1.

Scripts (`cli run file`, or `cli run -` from stdin) and the REPL (`cli repl`) take one command per line,
as `command key=value ...` or `command {json}`:

```
doc.new name="Demo"
component.add type=NoseCone properties={"length":"10 cm","aftRadius":"1.2 cm"}
component.add type=BodyTube name=Body properties={"length":"30 cm","outerRadius":"1.2 cm"}
component.add type=TrapezoidFinSet parent=Body properties={"finCount":3,"height":"4 cm"}
component.add type=InnerTube parent=Body name=Mount properties={"outerRadius":"9.5 mm","axialMethod":"BOTTOM"}
component.add type=Parachute parent=Body properties={"diameter":"30 cm"}
config.set_motor mount=Mount motor=C6 manufacturer=Estes delay=5
analysis.stability
sim.create
sim.run sim=0
doc.save path=demo.ork
```

## MCP

Headless (the default): an in-memory session, with no window.

```json
{ "mcpServers": { "openrocket": { "command": "/path/to/openrocket/bin/openrocket", "args": ["mcp"] } } }
```

- `["mcp", "--gui"]` starts the full GUI in the same process. The agent's changes appear live, and the `gui_*`
  tools can screenshot, click and operate any dialog. Screenshots and renders come back as image content.
- `["mcp", "--connect"]` attaches to an OpenRocket started with `bin/openrocket gui` or `bin/openrocket serve`.

For Claude Code: `claude mcp add openrocket -- /path/to/openrocket/bin/openrocket mcp --gui`.

Tool names are the command names with `_` in place of `.` (`sim_run`, `component_set`, ...).

## GUI automation server

`bin/openrocket gui` starts the normal application plus a localhost server. It writes its port and a
random token to `~/Library/Application Support/OpenRocket/automation.json` (macOS; `%APPDATA%\OpenRocket` on
Windows, `~/.openrocket` on Linux). The file is readable only by your user. `--no-prompt` skips startup dialogs (welcome,
update checks) that would otherwise wait for a click. When an automation command opens a modal dialog,
the call returns a `blocked_by_dialog` error naming it. Operate the dialog with `gui.tree` / `gui.set_value` /
`gui.click`. Model commands keep working while it is open.

The socket protocol is newline-delimited JSON: `{"method":"auth","token":...}`, then
`{"id":1,"method":"call","command":"sim.run","params":{...}}` or `{"method":"list"}`.

Logging goes to stderr at WARN level (`OPENROCKET_LOG_LEVEL=DEBUG` for more), so stdout stays clean for
results and MCP messages.
