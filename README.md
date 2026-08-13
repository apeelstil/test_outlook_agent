# Outlook AI Agent

Mini-agent that polls unread Outlook mail, runs each message body through an LLM tool-loop
(reminders, current date/time, search), replies to the sender, and durably marks the message
processed so it isn't answered twice.

## Architecture

Packages under `com.testtask.outlookagent`:

- `mail` — `MailChannel` (interface) + `MailProcessor` (fetch → dedup → agent → reply →
  mark-processed, one message at a time). `mail.outlook` — `OutlookMailChannel` and
  `JacobOutlookComFacade` (real Outlook via JACOB), `MockMailChannel` — Outlook-free
  implementation for tests and mock-demo.
- `agent` — `Agent`: LLM ↔ tool-call loop, capped at `agent.maxSteps` steps, never lets a bad
  tool call or LLM error escape as an exception.
- `tool` — `Tool` interface, `ToolRegistry`, and the tools (`CurrentDateTimeTool`,
  `AddReminderTool`, `FindItemsTool`).
- `llm` — `LlmClient` interface and `HttpLlmClient`, an OpenAI-compatible chat-completions
  client on `HttpURLConnection`.
- `store` — `FileSeenStore` (idempotency + pending-reply durability), `FileReminderStore` —
  plain JSON files on disk.
- `audit` — `FileAuditJournal`, append-only, SHA-256 hash-chained, no PII; `NoOpAuditJournal`
  for tests.
- `config` — `AppConfig`/`ConfigLoader` (YAML) and `EnvSecretResolver` (secret from env only).
- `app` — `ApplicationFactory`/`ProductionLauncher` (wiring), `PollingLoop`, `MockDemoRunner`,
  `Main` (the one executable entry point).

## Processing one message

`MailProcessor.processUnread()`:

1. `mailChannel.fetchUnread()`. If this throws, log `event=mail_fetch_failed` and return — no
   messages are lost, they'll show up unread on the next poll.
2. For each message, skip it if `seenStore.isSeen(msg.getId())` — dedup key is the stable
   message id (Outlook `EntryID`), never subject/body.
3. Otherwise, process the message. If `getPendingReply(msg.getId())` already has a value from
   an earlier, incomplete attempt, reuse it instead of calling the agent again — this is what
   stops a retried delivery from re-running `add_reminder` or any other side-effecting tool.
   Otherwise call `agent.run(msg.getBody())` and immediately `savePendingReply(...)` before
   attempting delivery.
4. `mailChannel.reply(msg, replyBody)`.
5. `seenStore.markSeen(msg.getId())` — clears the pending reply and adds the id to the seen
   set. Only after this point is the message considered processed.

Each message runs in its own try/catch inside the loop (`event=mail_message_processing_failed`
on failure), so one message failing at any step — agent, reply, or the seen-store write — does
not stop the rest of the batch from being processed.

### Remaining crash window

`reply()` and the durable `markSeen()` write are not one atomic operation — there's no
transactional API tying Outlook and the local seen-store together. If the process crashes
between a successful `reply()` and the following disk write, the next run will see the message
as still-pending and send another reply (the pending-reply value is reused, so no tool runs
twice, but the mail itself can go out twice). This window is small and only matters across a
crash at that exact instant; full atomicity would need a distributed transaction, which is out
of scope. This is a known, accepted limitation, not something the tests treat as a defect.

## Stack and build

Java 8, Maven, JUnit 4, SLF4J + Logback (structured `event=...` logs, no PII), YAML
(`org.yaml.snakeyaml` + `jackson-databind`), `net.sf.jacob-project:jacob:1.20` (JACOB) — the
only way this project talks to Outlook, strictly through the COM Object Model.

```bash
mvn clean test        # JACOB is excluded from the test classpath (surefire
                       # classpathDependencyExcludes) — green on a machine without Outlook/JACOB
mvn clean package      # fat-jar via maven-shade-plugin, Main-Class = com.testtask.outlookagent.app.Main
```

## Running the mock demo

No Outlook, no native JACOB runtime, no network. Uses `MockMailChannel`, a scripted `LlmClient`,
and a temp directory for storage/audit:

```bash
java -jar target/outlook-agent-1.0-SNAPSHOT.jar mock-demo
```

## Running against real Outlook

```bash
java -jar target/outlook-agent-1.0-SNAPSHOT.jar path/to/config.yaml
# no argument — defaults to ./config.yaml
```

Requires:

- Windows with Outlook already installed and running/signed in (the profile named in
  `mail.profile`);
- an environment variable holding the LLM API key, named by `llm.apiKeyEnv`;
- `jacob-1.20-x64.dll` (or `-x86.dll` for a 32-bit JVM) next to `java.exe` or on
  `-Djava.library.path`. It is **not** in the repo and **not** in the fat-jar (see below).

**Live Outlook/JACOB verification has not been run in this local environment** — it lacks the
required setup (Classic Outlook and the native JACOB runtime/DLL) for a real COM session. The
integration is covered by automated tests against a fake COM facade, not by a live run; it still
needs verification on the target Windows/Outlook environment. JACOB 1.20 itself is present as a
Java dependency (`net.sf.jacob-project:jacob:1.20`) — only the native DLL is missing here.

## YAML config

`config.example.yaml` is a template with no secrets, safe to commit. The working `config.yaml`
is gitignored.

```yaml
llm:
  endpoint: "..."      # OpenAI-compatible chat-completions URL
  model: "..."
  apiKeyEnv: "..."     # name of the env var holding the API key — not the key itself
  timeoutMs: 15000

agent:
  maxSteps: 5          # cap on tool-call steps per message

store:
  path: "./data/reminders.json"   # seen.json and audit.log live next to this (resolveSibling)

mail:
  pollSeconds: 30
  profile: "..."       # top-level Outlook store name
  folder: "..."        # folder name within that store
```

`EnvSecretResolver` reads `llm.apiKeyEnv`'s value at startup. If the variable is unset or empty,
it throws `MissingSecretException` — production fails fast at startup with a clear error instead
of an NPE later.

## Outlook / JACOB

- Access goes through `com.jacob-project:jacob:1.20` only (`Outlook.Application` →
  `Namespace("MAPI")` → `Folders` → `Items`), not MAPI/EWS/Graph directly.
- JACOB loads a native DLL on class init, so it's excluded from the test classpath
  (`surefire.classpathDependencyExcludes`) — `mvn test` stays green without Outlook installed.
- `JacobOutlookComFacade` never calls `Application.Quit` — it attaches to whatever Outlook
  session is already running. Uses `ComThread.InitSTA()`/`Release()` per JACOB's MAPI threading
  requirement.
- Outlook's own "read" flag is never touched — dedup is entirely the local seen-store's job, by
  message id.

### Vendored JACOB 1.20 jar

`vendor/maven-repo/net/sf/jacob-project/jacob/1.20/` is a minimal project-local Maven repo
(`file://` in `pom.xml`) so the dependency resolves without `mvn install:install-file`.
Provenance (release source, SHA-256, LGPL v2.1 license) is in
`vendor/maven-repo/net/sf/jacob-project/jacob/1.20/PROVENANCE.md`. The native DLL from the
official zip is deliberately **not** extracted or committed — only `jacob-1.20.jar` and
`LICENSE.TXT`.

### External `jacob-1.20-x64.dll`

Not in the repo, can't be in the fat-jar (native, JVM-bitness-specific). Get it from the
official JACOB 1.20 release
(https://github.com/freemansoft/jacob-project/releases/tag/Root_B-1_20 — same archive the
provenance file describes extracting the jar from) and place it on the target Windows machine
next to `java.exe`, or point `-Djava.library.path` at it.

## Storage

All three stores are plain files, resolved from `store.path` via `resolveSibling` (see `Main`):

- `FileReminderStore` (`store.path`, default `./data/reminders.json`) — JSON list of reminders
  (`text`, `dueIso`), read/written by `AddReminderTool`/`FindItemsTool`.
- `FileSeenStore` (`seen.json`) — processed message ids plus any pending (not-yet-delivered)
  reply bodies; restart-safe.
- `FileAuditJournal` (`audit.log`) — append-only, SHA-256 hash-chained
  (`verifyChainIntegrity()` detects any retroactive edit).

None of these files or their parent directories need to exist beforehand — each store creates
its parent directory (`Files.createDirectories`) lazily on first write.

## Logging

Structured `event=...` keys, no email body/sender/subject/tool-arguments/secrets in any of
them:

- `event=mail_fetch_failed` — `fetchUnread()` threw.
- `event=mail_message_processing_failed` — one message's processing (agent/reply/mark-seen)
  threw; the rest of the batch still runs.
- `event=agent_mail_seen ref=<sha256>` — message fully processed; `ref` is a hash of the
  message id, never the id itself.
- `event=agent_tool_call tool=<name>` — a registered tool was invoked.
- `event=llm_failed` — the LLM call threw or timed out.
- `event=poll_cycle_failed` — a whole poll cycle threw; the loop keeps running.

`FileAuditJournal` entries store only `eventKey`, `hashedMessageRef` (SHA-256 of the message
id), `toolName` (the resolved tool's own name, never an arbitrary string from the LLM), and a
timestamp.

Tool errors reaching the LLM are always generic, static strings (`"Error: invalid tool
arguments"`, `"Error: tool execution failed"`) — `Agent.run` discards the actual exception
message rather than forwarding it, so a tool that puts sensitive data in an exception message
still can't leak it into the LLM conversation (see `AgentToolErrorHandlingTest`).

## Idempotency

Keyed by the stable `msg.getId()` (Outlook `EntryID`), not message content — same text with a
different id is processed again; the same id, refetched or redelivered, is skipped via
`SeenStore.isSeen()`. The pending-reply mechanism (see "Processing one message" above) additionally
protects against replaying side-effecting tools when only the delivery step needs retrying.

## Adding a new tool

1. Implement `com.testtask.outlookagent.tool.Tool`: `getName()`, `execute(Map<String, Object>
   args)`, optionally `getDescription()`/`getParametersSchema()` (JSON Schema for the LLM's
   function-calling).
2. Validate arguments at the top of `execute()` and throw `IllegalArgumentException` on
   failure — `Agent` never forwards the exception's message to the LLM, only a fixed generic
   string, so this is safe by construction (still avoid putting anything sensitive in the
   message on principle).
3. Register it in `ApplicationFactory.create(...)` via `toolRegistry.register(new MyTool(...))`.
4. Write the TDD test first, following `AddReminderToolTest`/`FindItemsToolTest`.

## How I worked with AI

Done in Claude Code, one stage of `PLAN.md` at a time, with `/clear` between stages to avoid
carrying over context from earlier work.

1. **Plan-first** — scope and explicit out-of-scope fixed before writing code for each stage.
2. **TDD, red → green** — a failing test describing the contract, then the minimal code to pass
   it, as separate commits (see `git log`: paired `test: ...` / `fix:`/`feat: ...` commits).
3. Checked official docs/provenance before using a library API (JACOB, Jackson/SnakeYAML,
   maven-shade-plugin) rather than relying on memory.
4. Kept stages small and isolated — one stage, one slice of functionality, no reaching ahead.
5. Final stage: a self-review for secrets/PII in logs and injection via tool arguments, before
   docs and packaging.
