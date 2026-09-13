# dsh-hive-vessel

A [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) plugin
that makes dsh a hive vessel. hive addons push what they want shown, and dsh
shows it with its own UI:

| hive op              | in dsh                                                            |
|----------------------|-------------------------------------------------------------------|
| `ui/show-panel`      | a panel in the right Sidebar's **Hive** tab, revealed on arrival  |
| `ui/close-panel`     | the panel goes away                                               |
| `ui/notify`          | a notice (toast, plus the tab's notice list)                      |
| `ui/open-file`       | the file opens in dsh's own viewer at the line                    |
| `ui/send-to-terminal`| reported as unsupported (the browser holds no terminal)           |
| `json/event`         | Cordis event `hive-vessel/event` and DOM event `hive-vessel:event` |

Link lines in a panel open their file in dsh too. The plugin reports back to
hive (`dsh/hello`, `dsh/opened`, `dsh/open-failed`) on the bridge's reply
route.

## Install

```sh
dsh plugin --profile <profile> add /path/to/hive-deepseek/dsh
```

The bundle inserts one row:

```yaml
- id: hive-vessel
  name: dsh-hive-vessel
  config:
    url: http://127.0.0.1:7925
```

Override `url` (and add `token` if the hive side sets `:deepseek/token`) in
your profile's `cordis.patch.yml`. In the browser, `localStorage` keys
`hive-vessel.url` / `hive-vessel.token` also work.

## hive side

Mount the `hive.deepseek` addon (the Clojure half of this repository). It
runs hive-vessel's SSE bridge on port 7925 and registers the vessel.

## Shape

No build step: `lib/client.js` is written in the closure-factory form dsh's
client build emits (`window.__ModuleLoader__.load({ id, factory })`) and
requires only `react` from dsh's platform module table. `index.js` is the host
half, which only records the bridge location.

```sh
npm test   # node --test, no dsh needed
```

MIT
