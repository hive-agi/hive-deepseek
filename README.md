# hive-deepseek

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-deepseek.svg)](https://clojars.org/io.github.hive-agi/hive-deepseek)
[![release](https://github.com/hive-agi/hive-deepseek/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-deepseek/actions/workflows/release.yml)

DeepSeek Harness (`dsh`) as a hive vessel.

```clojure
io.github.hive-agi/hive-deepseek {:mvn/version "RELEASE"}
```

Pin the version from the Clojars badge.

Addons describe what should appear once, as
[hive-vessel](https://github.com/hive-agi/hive-vessel) ops. `hive-deepseek` gets those ops into a
running dsh, where they show up in dsh's own UI: panels in the right
Sidebar, notices, file navigation, and events other dsh plugins can listen to.

```text
addon intent ─ translators ─▶ :ui/* primitives ─ :json dialect ─▶ native message
      │                                                              │
 hive.deepseek IAddon ── hive-vessel.executor.sse ── SSE ──▶ dsh-hive-vessel (browser plugin)
                                                                     │
                                            right Sidebar "Hive" tab, notices, file viewer
```

Two halves, one repository:

| half | path | runs in |
|------|------|---------|
| `hive.deepseek` IAddon + IVessel | `src/`, `resources/META-INF/hive-addons/` | the hive JVM |
| `dsh-hive-vessel` plugin | `dsh/` | DeepSeek Harness (host + browser) |

## The addon

`hive-deepseek.addon/addon-ctor`, manifest id `hive.deepseek`.

Config: `:deepseek/port` (7925), `:deepseek/host` (loopback),
`:deepseek/token`, `:deepseek/presenter-id` (`:deepseek`).

On initialize it starts the bridge and builds its registry: hive-vessel's
standard translators, plus the `:vessel/translators` hook of every injected
dependency. Dependencies that expose a `register-presenter!` hook
(hive-carto-flow does) get this vessel's delivery registered, so their frames
reach dsh. Neither side's code names the other.

Hooks:

| hook | returns |
|------|---------|
| `:vessel/target` | the hive-vessel target (`:vessel/id :deepseek`, `:json`) |
| `:vessel/instance` | a hive-addon `IVessel` (capability `:delivery`) |
| `:deepseek/vessel` | host-neutral descriptor with `:vessel/addon` |
| `:vessel/dispatch!` | `(fn [op-or-ops])` through the registry |
| `:vessel/register-translators!` | add translators at runtime |
| `:deepseek/bridge` | port, connected clients, retained panels, inbox |

`:delivery` accepts ops and presenter envelopes (`{:type :payload}`).

## Try it

```sh
# hive side: bridge on 7925, pushes a demo sequence once a dsh tab connects
clojure -M:dev -m hive-deepseek.demo 7925 600 /path/to/a/file

# dsh side
dsh plugin --profile web add ./dsh
dsh --profile web
```

## Every hive provider in dsh

`dsh/hive-dsh` boots dsh on the providers the swarm already uses. It reads
hive-mcp's `~/.config/hive-mcp/config.edn` (`:llm-providers` for URLs and
models, `:secrets` for where each key lives) and writes those routes as a
`llm-pi-ai` patch next to the profile. Nothing about a provider is restated in
dsh's config.

```sh
dsh/hive-dsh --dump --only venice,axon          # the patch, no keys read
dsh/hive-dsh --check                            # HTTP status per keyed provider
dsh/hive-dsh --profile hive --default venice/deepseek-v4-flash -- --no-open --port 3190
```

- **Keys.** Only `pass:` secret references are used. The wrapper runs
  `pass show` and exports each key into dsh's environment only. The patch
  names the variable (`VENICE_API_KEY`), never the value. A secret that is
  not a `pass:` reference is skipped and reported.
- **Rejected keys.** Before launch, each keyed provider is probed with its
  key, and one answering 401/403 is left out. `--no-verify` skips the probe.
  OpenRouter and Venice are probed on endpoints that need auth, because
  OpenRouter serves `/models` without it.
- **Limit of the probe.** It checks the key, not the balance. An account out
  of credit passes and fails on the first completion: axon returned HTTP 402
  on 2026-09-13.
- **Runtimes.** Planning runs in cljw (`CLJW`, default `cljw`) and starts in
  about 70 ms. The shell half exists only because cljw cannot spawn a
  process. `DSH_BIN` picks the dsh executable.

The routes are computed by `hive-deepseek.providers`, a pure `.cljc`
namespace covered by the JVM test suite.

## Tests

```sh
clojure -M:test                     # IAddon boundary with stub dependencies
(cd dsh && npm test)                # browser half, no dsh needed
e2e/run.sh /tmp/hive-deepseek-e2e   # real dsh + headless Chromium
```

`e2e/run.sh` does four things:

1. Installs `@deepseek-ai/dsh` into a scratch directory.
2. Creates an isolated profile and installs the plugin there with
   `dsh plugin add`.
3. Starts dsh and the demo hive process.
4. Drives the page in headless Chromium to create a session.

It then checks:

- the Hive tab is revealed
- panels paint hive-vessel's rendered lines with diff faces
- the error notice shows as a toast
- `json/event` is recorded
- `ui/open-file` and panel links open the file in dsh's viewer

To develop against sibling checkouts of `hive-addon` and `hive-vessel`, point
an untracked `local.deps.edn` at them and run
`clojure -Sdeps "$(cat local.deps.edn)" -M:test`.

## Releases

Every push to `main` that changes `src/`, `resources/`, `test/`, `deps.edn` or
`version.edn` runs the suite, bumps the patch version, regenerates
`CHANGELOG.md`, tags `vX.Y.Z` and deploys to Clojars through
[hive-build](https://github.com/hive-agi/hive-build). Do not bump `VERSION` by hand.

## License

MIT
