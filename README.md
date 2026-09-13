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
