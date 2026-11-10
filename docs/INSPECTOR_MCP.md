# QaLens inspector MCP

Select a real element in the Android QA app, write what should change and hand the request to your
coding agent. The agent can check the current app, see where the element sits in the Compose tree
and point back to it in the desktop workbench. Source edits and rebuilds happen in the agent's
app workspace using that team's tools.

## Pair, select and share

1. Use an authorized adb device with a QaLens QA build from this revision or newer. The SDK
   supplies an inspection-policy revision on its authenticated bridge. Older SDKs can still use
   the existing desktop tools. The developer prompt and MCP require the policy revision.
2. In the QaLens checkout, run `python3 tools/local-bridge/server.py --gui` and open the printed
   loopback URL. Choose the phone and app in **Landing**, click **Connect** and approve on Android.
3. Click **Start mirror → Inspect**, then click the element, or select it in **Semantics tree**.
   Open **Ask agent**, choose **Investigate a bug**, **Review the design** or **Request a UI change**,
   and write the request. Click **Review prompt** to see the exact text and element context.
4. Use **Copy prompt** to paste the reviewed request into any agent. For MCP, open **Agent access**,
   enable it for the phone session and add the config below. Review the request again if access
   changed, then click **Share with MCP**.
5. Tell the agent “Use QaLens to read the latest shared request and verify the target.” It can call
   `qalens_list_requests` and `qalens_get_request`. The MCP inbox is pulled by the agent on request.
   Use a configured direct handoff if you want the desktop button to submit to your harness.

## Connect an MCP client

Use **Agent access → Copy MCP configuration**. It includes the absolute paths for your actual
workbench and its access file. Register it as a local stdio server in a client on the same computer.

```json
{
  "mcpServers": {
    "qalens": {
      "command": "/absolute/path/to/python3",
      "args": [
        "/absolute/path/to/QaLens/tools/local-bridge/mcp_server.py",
        "--access-file",
        "/absolute/path/to/qalens-bridge-data/mcp-8765.json"
      ]
    }
  }
}
```

For Codex CLI, use the same executable and arguments with
`codex mcp add qalens -- /absolute/path/to/python3 /absolute/path/to/mcp_server.py --access-file /absolute/path/to/mcp-8765.json`.
Restart or reconnect your client after enabling access again. A client that already used one
credential cannot silently switch to a new phone session. Use a fixed workbench port and data
directory if you want the configuration paths to stay the same across restarts.

| Tool | Use |
| --- | --- |
| `qalens_screen` | Get the paired package, screen, route, viewport, selection ID and a fresh snapshot ID. |
| `qalens_tree` | Search tags, labels, roles and actions with `query`. Page via `snapshotId`, `offset` and `limit`. |
| `qalens_element` | Read attributes, ancestry and immediate children from `snapshotId` and `nodeId`. |
| `qalens_highlight` | Reference a live matching element in Landing. Pass `snapshotId`, `nodeId` and optionally `note`. |
| `qalens_list_requests` | Find reviewed requests shared in this session. |
| `qalens_get_request` | Read the shared prompt and its captured context by `requestId`. |
| `qalens_screenshot` | Capture an SDK-masked PNG of the app window with optional screenshot access enabled. |
| `qalens_diagnostics` | Read a bounded sample of recent logs or network metadata with optional diagnostic access enabled. Pass `kind` and optionally `query` or `limit`. |

Snapshot IDs expire after two minutes or eviction. Refresh before using a stale node ID.
Highlights last up to 20 seconds. Click **Show element** to inspect the reference after the
workbench checks it against a fresh tree. The agent can point to elements while the developer
keeps control of phone input.

## Send directly to your harness

Configure this only when your harness has a trusted local queue, CLI or adapter that accepts a
developer request. Design Studio can send into its own conversation using its session binding.
From QaLens you can paste the prompt into that conversation, connect a harness that supports
stdio MCP, or provide an adapter for your harness's submission mechanism.

```json
{
  "name": "My harness",
  "argv": ["/absolute/path/to/python3", "/absolute/path/to/enqueue_qalens.py"]
}
```

Save this JSON somewhere you control and start
`python3 tools/local-bridge/server.py --gui --agent-handoff /path/to/agent-handoff.json`.
With MCP access on and a reviewed prompt, click **Send to My harness**. QaLens publishes the same
request to the local MCP inbox and invokes the configured argv. The adapter receives a single
`qalens.agent-request` v1 JSON object on stdin with `id`, `task`, `createdAtMillis`, `context`
and `prompt`. Use `id` to deduplicate in your queue. Exit 0 after accepting the request.
The desktop gives the process eight seconds and reports whether the adapter accepted it.
The agent's eventual response and any file edits are handled by the harness. QaLens starts the
configured executable directly, and the prompt and MCP clients cannot change its argv.

## Access and evidence

- **Agent access** starts off. It is scoped to the approved app connection and the SDK inspection
  policy. Revoke access from the workbench or stop the phone inspector to end it. A new phone
  connection or a change to the SDK configuration requires a new opt-in.
- The workbench and MCP transport are local. Use a private workbench data directory. The per-port
  access file carries a scoped credential for these tools. Keep it out of commits and logs.
  Only enable access for a client and adapter you trust with the displayed data.
- Visible Compose labels and tags follow the app's redaction policy. The agent projection excludes
  editable values and replaces editable labels. Diagnostic access also excludes network query
  strings, credentials in URLs, headers, body previews and raw error strings. Redacted logs can
  still contain app data selected for logging by the host.
- Screenshot and diagnostic access each require a separate checkbox. The screenshot uses the
  app-window capture path with privacy masks. It does not stream the scrcpy display to the agent.
- Live trees, references and shared requests are bounded in desktop memory. A copied prompt or
  delivered request is a separate copy under the recipient's control. Match source files in the
  host workspace using the route, tag and ancestry, and verify the resulting UI in a fresh capture.

Use the [QaLens agent skill](../skills/qalens/SKILL.md) with a harness that supports agent skills.
Place the `skills/qalens` directory in the client's configured skill directory, such as the
app repository's `.agents/skills/qalens` for Codex. SDK integration steps live in the
[host-agent runbook](AI_INTEGRATION.md). Pairing recovery and capture tools live in the
[desktop guide](../tools/local-bridge/README.md).
