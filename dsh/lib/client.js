window.__ModuleLoader__.load({ id: "dsh-hive-vessel", factory: (require) => {
var module = { exports: {} }; var exports = module.exports;
/**
 * Browser half of dsh-hive-vessel: DeepSeek Harness as a hive vessel.
 *
 * Subscribes to the hive.deepseek bridge (Server-Sent Events) and applies
 * hive-vessel :json messages:
 *
 *   ui/show-panel        a panel in the right Sidebar's "Hive" tab
 *   ui/close-panel       drop that panel
 *   ui/notify            a transient notice
 *   ui/open-file         open the file in dsh's own viewer (ctx.sidebarRight)
 *   ui/send-to-terminal  reported as unsupported: the browser holds no terminal
 *   json/event           re-emitted as the Cordis event `hive-vessel/event`
 *                        and the DOM event `hive-vessel:event`, for other plugins
 *
 * Hand-written in the closure-factory shape dsh's client build emits, so the
 * package needs no build step. Externals come only from the platform module
 * table (react).
 *
 * SPDX-License-Identifier: MIT
 */
"use strict";

const React = require("react");
const h = React.createElement;

const TAB_ID = "dsh-hive-vessel";
const TAB_KIND = "hive";
const DEFAULT_URL = "http://127.0.0.1:7925";
const MAX_NOTICES = 20;
const MAX_EVENTS = 50;

// =============================================================================
// Pure: state, effects, addresses, styles
// =============================================================================

function initialState() {
  return { status: "idle", panels: {}, order: [], focus: undefined, notices: [], events: [] };
}

function withNotice(state, message, level) {
  const notices = state.notices.concat([{ message: String(message), level: level || "info" }]);
  return Object.assign({}, state, { notices: notices.slice(-MAX_NOTICES) });
}

/** The state after one bridge MESSAGE. Unknown ops leave it unchanged. */
function reduce(state, message) {
  const op = message && message.op;
  switch (op) {
    case "ui/show-panel": {
      const id = message["panel/id"];
      const doc = message.doc || {};
      const panel = { id: id, title: doc["doc/title"] || id, lines: message.lines || [], doc: doc };
      const order = state.order.indexOf(id) >= 0 ? state.order : state.order.concat([id]);
      return Object.assign({}, state, {
        panels: Object.assign({}, state.panels, { [id]: panel }), order: order, focus: id,
      });
    }
    case "ui/close-panel": {
      const id = message["panel/id"];
      const panels = Object.assign({}, state.panels);
      delete panels[id];
      const order = state.order.filter(function (x) { return x !== id; });
      return Object.assign({}, state, {
        panels: panels, order: order,
        focus: state.focus === id ? order[order.length - 1] : state.focus,
      });
    }
    case "ui/notify":
      return withNotice(state, message.message, message.level);
    case "ui/send-to-terminal":
      return withNotice(state, "hive: terminal \"" + message.terminal + "\" is not reachable from the dsh browser vessel", "warn");
    case "json/event": {
      const events = state.events.concat([{ event: message.event, data: message.data }]);
      return Object.assign({}, state, { events: events.slice(-MAX_EVENTS) });
    }
    default:
      return state;
  }
}

/** The side effects one MESSAGE asks of dsh, as data. */
function effectsOf(message) {
  switch (message && message.op) {
    case "ui/show-panel": return [{ kind: "reveal-tab" }];
    case "ui/notify": return [{ kind: "toast", message: message.message, level: message.level || "info" }];
    case "ui/send-to-terminal":
      return [{ kind: "toast", message: "hive: terminal \"" + message.terminal + "\" is not reachable from dsh", level: "warn" }];
    case "ui/open-file": return [{ kind: "open-file", file: message.file, line: message.line, column: message.column }];
    case "json/event": return [{ kind: "emit", event: message.event, data: message.data }];
    default: return [];
  }
}

function encodeSegment(segment) {
  return encodeURIComponent(segment).replace(/%3A/gi, ":");
}

function encodePath(path) {
  return path.split("/").map(encodeSegment).join("/");
}

/**
 * dsh's session-scoped `dsh-resource://file/session/<id>/<path>` address for
 * FILE, or undefined without a session. Absolute paths stay absolute inside
 * the address (the Host resolves them); dsh's file viewers claim only this
 * scope, never `file/absolute/...`.
 */
function fileAddress(file, sessionId) {
  if (!sessionId) return undefined;
  const normalized = String(file).replace(/\\/g, "/").replace(/^(?:\.\/)+/, "");
  return "dsh-resource://file/session/" + encodeSegment(sessionId) + "/" + encodePath(normalized);
}

const FACE_STYLES = {
  title: { fontWeight: 600, fontSize: "1.1em" },
  heading: { fontWeight: 600 },
  plain: {},
  muted: { opacity: 0.6 },
  info: { color: "#3b82f6" },
  success: { color: "#16a34a" },
  warn: { color: "#ca8a04" },
  error: { color: "#dc2626", fontWeight: 600 },
  added: { background: "rgba(22, 163, 74, 0.16)" },
  removed: { background: "rgba(220, 38, 38, 0.16)" },
  hunk: { color: "#8b5cf6" },
  code: { opacity: 0.9 },
  link: { textDecoration: "underline", cursor: "pointer", color: "#3b82f6" },
};

function lineStyle(face) {
  return FACE_STYLES[face] || FACE_STYLES.plain;
}

/** Milliseconds before reconnect ATTEMPT (1-based): 1 s doubling to 30 s. */
function reconnectDelay(attempt) {
  return Math.min(30000, 1000 * Math.pow(2, Math.max(0, attempt - 1)));
}

function bridgeUrl(base, path, token) {
  const url = String(base || DEFAULT_URL).replace(/\/+$/, "") + path;
  return token ? url + (url.indexOf("?") >= 0 ? "&" : "?") + "token=" + encodeURIComponent(token) : url;
}

/** Bridge location: plugin config, then localStorage, then the default. */
function resolveConfig(config, storage) {
  let stored = {};
  try {
    stored = {
      url: storage && storage.getItem("hive-vessel.url") || undefined,
      token: storage && storage.getItem("hive-vessel.token") || undefined,
    };
  } catch (_) { /* storage blocked */ }
  const c = config || {};
  return { url: c.url || stored.url || DEFAULT_URL, token: c.token || stored.token };
}

// =============================================================================
// Store
// =============================================================================

function createStore() {
  let state = initialState();
  const listeners = new Set();
  return {
    get: function () { return state; },
    set: function (next) {
      if (next === state) return;
      state = next;
      listeners.forEach(function (l) { l(); });
    },
    subscribe: function (listener) {
      listeners.add(listener);
      return function () { listeners.delete(listener); };
    },
  };
}

// =============================================================================
// Views
// =============================================================================

function PanelLines(props) {
  const panel = props.panel;
  return h("div", {
    "data-hive-panel": panel.id,
    style: { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: 12, lineHeight: 1.5, padding: "8px 12px" },
  }, panel.lines.map(function (line, i) {
    const style = Object.assign({ whiteSpace: "pre-wrap", minHeight: "1.5em" }, lineStyle(line.face));
    const attrs = { key: i, "data-face": line.face, style: style };
    if (line.file) {
      attrs.role = "link";
      attrs.tabIndex = 0;
      attrs.title = line.file + (line.line ? ":" + line.line : "");
      attrs.onClick = function () { props.onOpen(line.file, line.line); };
    }
    return h("div", attrs, line.text);
  }));
}

function HiveBody(runtime, store) {
  return function HiveBodyView(props) {
    const info = props.useTabInfo ? props.useTabInfo() : undefined;
    const state = React.useSyncExternalStore(store.subscribe, store.get);
    const [selected, setSelected] = React.useState(undefined);
    const sessionId = props.sessionId;
    const actions = info && info.tab && info.tab.actions;

    React.useEffect(function () {
      runtime.bindOpener(function (file, line) {
        const address = fileAddress(file, sessionId);
        if (!address || !actions) return false;
        actions.openResource(address, line ? { params: { line: line } } : undefined);
        return true;
      }, sessionId);
      return function () { runtime.bindOpener(undefined); };
    }, [actions, sessionId]);

    const id = selected !== undefined && state.panels[selected] ? selected : state.focus;
    const panel = id !== undefined ? state.panels[id] : undefined;

    return h("div", { "data-hive-vessel": state.status, style: { height: "100%", overflow: "auto" } },
      h("div", { style: { display: "flex", gap: 6, flexWrap: "wrap", alignItems: "center", padding: "8px 12px", borderBottom: "1px solid rgba(127,127,127,0.25)", fontSize: 12 } },
        h("span", { "data-hive-status": state.status, style: { opacity: 0.6 } }, "hive · " + state.status),
        state.order.map(function (pid) {
          return h("button", {
            key: pid, type: "button", "data-hive-panel-tab": pid,
            onClick: function () { setSelected(pid); },
            style: { font: "inherit", padding: "2px 8px", borderRadius: 6, cursor: "pointer",
              border: "1px solid rgba(127,127,127,0.35)",
              background: pid === id ? "rgba(127,127,127,0.18)" : "transparent", color: "inherit" },
          }, state.panels[pid].title);
        })),
      panel
        ? h(PanelLines, { panel: panel, onOpen: function (file, line) { runtime.open(file, line); } })
        : h("div", { style: { padding: 16, opacity: 0.6, fontSize: 13 } },
            state.status === "connected" ? "Connected to hive. No panel yet." : "Waiting for the hive bridge…"),
      state.notices.length > 0
        ? h("div", { "data-hive-notices": true, style: { padding: "8px 12px", borderTop: "1px solid rgba(127,127,127,0.25)", fontSize: 12 } },
            state.notices.slice(-5).map(function (n, i) {
              return h("div", { key: i, "data-level": n.level, style: lineStyle(n.level === "info" ? "plain" : n.level) }, n.message);
            }))
        : null);
  };
}

function HiveTitle(props) {
  const info = props.useTabInfo ? props.useTabInfo() : undefined;
  return h(React.Fragment, null, (info && info.tab && info.tab.title) || "Hive");
}

// =============================================================================
// Toasts (DOM, outside React: they must show with the Sidebar collapsed)
// =============================================================================

function createToaster(doc) {
  let host;
  function ensure() {
    if (host && host.isConnected) return host;
    host = doc.createElement("div");
    host.setAttribute("data-hive-toasts", "");
    Object.assign(host.style, { position: "fixed", right: "16px", bottom: "16px", zIndex: 2147483000,
      display: "flex", flexDirection: "column", gap: "8px", maxWidth: "min(420px, calc(100vw - 32px))", pointerEvents: "none" });
    doc.body.appendChild(host);
    return host;
  }
  return {
    show: function (message, level) {
      const el = doc.createElement("div");
      el.setAttribute("data-hive-toast", level);
      el.textContent = message;
      Object.assign(el.style, { padding: "8px 12px", borderRadius: "8px", font: "13px system-ui, sans-serif",
        background: level === "error" ? "#7f1d1d" : level === "warn" ? "#713f12" : "#1f2937",
        color: "#fff", boxShadow: "0 4px 16px rgba(0,0,0,0.25)" });
      ensure().appendChild(el);
      setTimeout(function () { el.remove(); }, 6000);
    },
    dispose: function () { if (host) host.remove(); },
  };
}

// =============================================================================
// Plugin
// =============================================================================

const inject = ["slots", "sidebarRightTabs", "sidebarRight"];

function apply(ctx, config) {
  const settings = resolveConfig(config, typeof localStorage === "undefined" ? undefined : localStorage);
  const store = createStore();
  let opener;
  let lastSessionId;
  const pendingOpens = [];

  function post(message) {
    try {
      fetch(bridgeUrl(settings.url, "/vessel/reply", settings.token), {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(message),
      }).catch(function () {});
    } catch (_) { /* no fetch */ }
  }

  function revealTab() {
    try { ctx.sidebarRight.openTab(TAB_KIND); return true; } catch (_) { return false; }
  }

  const toaster = createToaster(document);

  const runtime = {
    bindOpener: function (fn, sessionId) {
      opener = fn;
      if (sessionId) lastSessionId = sessionId;
      while (opener && pendingOpens.length > 0) {
        const p = pendingOpens.shift();
        runtime.open(p.file, p.line);
      }
    },
    open: function (file, line) {
      try {
        if (opener && opener(file, line)) { post({ op: "dsh/opened", file: file, line: line }); return; }
        const address = fileAddress(file, lastSessionId);
        if (address) {
          ctx.sidebarRight.openResource(address, line ? { params: { line: line } } : undefined);
          post({ op: "dsh/opened", file: file, line: line });
          return;
        }
        // No session known yet: the Hive body brings one when it mounts.
        pendingOpens.push({ file: file, line: line });
        revealTab();
      } catch (e) {
        toaster.show("hive: cannot open " + file + ": " + (e && e.message), "warn");
        post({ op: "dsh/open-failed", file: file, message: String(e && e.message) });
      }
    },
  };

  function perform(effect) {
    switch (effect.kind) {
      case "reveal-tab": revealTab(); break;
      case "toast": toaster.show(effect.message, effect.level); break;
      case "open-file": runtime.open(effect.file, effect.line); break;
      case "emit":
        try { ctx.emit("hive-vessel/event", { event: effect.event, data: effect.data }); } catch (_) { /* no listeners */ }
        window.dispatchEvent(new CustomEvent("hive-vessel:event", { detail: { event: effect.event, data: effect.data } }));
        break;
    }
  }

  function receive(message) {
    store.set(reduce(store.get(), message));
    effectsOf(message).forEach(perform);
  }

  ctx.effect(function () {
    return ctx.sidebarRightTabs.register({
      id: TAB_ID,
      kind: TAB_KIND,
      priority: "extension",
      title: function () { return "Hive"; },
      guide: [{ order: 90, title: function () { return "Hive"; }, description: function () { return "Panels from hive addons"; } }],
    });
  }, "dsh-hive-vessel: tab type");

  ctx.effect(function () {
    return ctx.slots.inject("sidebar.right.pane.tab", function () {
      return ctx.slots.register({ name: "sidebar.right.pane.tab", key: TAB_ID }, HiveBody(runtime, store));
    });
  }, "dsh-hive-vessel: tab body");

  ctx.effect(function () {
    return ctx.slots.inject("sidebar.right.pane.tab.title", function () {
      return ctx.slots.register({ name: "sidebar.right.pane.tab.title", key: TAB_ID }, HiveTitle);
    });
  }, "dsh-hive-vessel: tab title");

  ctx.effect(function () {
    let source;
    let timer;
    let attempt = 0;
    let disposed = false;
    const setStatus = function (status) { store.set(Object.assign({}, store.get(), { status: status })); };

    // EventSource retries on its own every few seconds forever; when hive is
    // down that floods the console. Own the retry instead: close on error and
    // reconnect with capped exponential backoff.
    function connect() {
      if (disposed) return;
      setStatus(attempt === 0 ? "connecting" : "reconnecting");
      source = new EventSource(bridgeUrl(settings.url, "/vessel/events", settings.token));
      source.onopen = function () {
        attempt = 0;
        setStatus("connected");
        post({ op: "dsh/hello", href: String(location.href).replace(/token=[^&]*/, "token=…") });
      };
      source.onerror = function () {
        source.close();
        setStatus("disconnected");
        attempt += 1;
        timer = setTimeout(connect, reconnectDelay(attempt));
      };
      source.addEventListener("vessel", function (event) {
        let message;
        try { message = JSON.parse(event.data); } catch (_) { return; }
        receive(message);
      });
    }

    connect();
    window.__hiveVessel = { store: store, receive: receive, settings: settings, post: post };
    return function () {
      disposed = true;
      clearTimeout(timer);
      if (source) source.close();
      toaster.dispose();
      delete window.__hiveVessel;
    };
  }, "dsh-hive-vessel: bridge");
}

module.exports = {
  name: "dsh-hive-vessel",
  inject: inject,
  apply: apply,
  // exposed for tests
  initialState: initialState,
  reduce: reduce,
  effectsOf: effectsOf,
  fileAddress: fileAddress,
  lineStyle: lineStyle,
  bridgeUrl: bridgeUrl,
  reconnectDelay: reconnectDelay,
  resolveConfig: resolveConfig,
  TAB_ID: TAB_ID,
  TAB_KIND: TAB_KIND,
};
return module.exports; } });
