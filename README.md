# jolt-otel-viewer

A small, host-agnostic OpenTelemetry HTML renderer for Jolt. Hosts provide
already-bounded trace summaries, logs, and trace trees; this package owns no
database, HTTP server, authentication, or exporter lifecycle.

## Install

Pin the exact commit you have reviewed:

```clojure
{:deps
 {io.github.chucklehead-dev/jolt-otel-viewer
  {:git/url "https://github.com/chucklehead-dev/jolt-otel-viewer.git"
   :git/sha "<full-commit-sha>"}}}
```

The placeholder must be replaced with a full 40-character commit SHA. This
repository does not publish mutable dependency coordinates.

The baseline output uses semantic HTML, `<details>`, ordinary links and forms,
and scoped CSS. `enhancement-script` optionally adds EventSource live-region
updates and native `<dialog>` trace previews. It is compatible with a strict
`script-src 'self'` policy: serve it from the host origin rather than inserting
it inline.

```clojure
(require '[otel.viewer :as viewer])

(viewer/render-fragment
  {:base-path "/ops/telemetry"
   :trace-filters
   {:selected {:service "app" :operation "GET" :status "error"
               :min-duration-ms "10" :window "1h"}
    :service-options [{:value "app" :label "Application"}]
    :status-options [{:value "ok" :label "OK"}
                     {:value "error" :label "Error"}]
    :window-options [{:value "15m" :label "Last 15 minutes"}
                     {:value "1h" :label "Last hour"}]}
   :summary {:traceCount 1 :spanCount 2 :logCount 0 :errorCount 0}
   :traces [{:traceId "0123456789abcdef0123456789abcdef"
             :rootSpan "GET /" :service "app" :spanCount 2 :status "ok"}]
   :logs []})
```

Public rendering surface:

- `render-fragment` and `render-page`
- `render-live-content` for server-sent patches
- `styles` and `enhancement-script` for host-owned asset routes
- `index-model`, `trace-model`, `trace-filter-model`, `normalize-base-path`, and
  `mounted-path`

Routes passed as `:work-path` or `:enhancement-path` are resolved beneath
`:base-path`. Omitting enhancement options produces a zero-JavaScript viewer.

Supplying `:trace-filters` adds a semantic GET form whose action is the mounted
index path. The host owns query parsing and storage and passes the selected
values back under `:selected`; the renderer only presents them. Supported
selected keys are `:service`, `:operation`, `:status`, `:min-duration-ms`, and
`:window`. Select controls use host-provided `{:value string :label string}`
vectors under `:service-options`, `:status-options`, and `:window-options`.
Each option vector is capped at 50 visible entries, duplicate/invalid values are
discarded, selected enumeration values must occur in the visible options, free
text is bounded, and minimum duration accepts only a non-negative decimal
string. The empty selection means all services/statuses, no duration threshold,
or any time. Ordinary trace links and the GET form remain fully functional when
the enhancement script is omitted.

Index inputs use `:summary`, `:traces`, and `:logs`; trace-detail input uses
`:trace {:traceId ... :spanTree [...] :logs [...]}`. Span children are nested
under `:children`. The package rejects malformed trace IDs and caps rendered
tree depth, but the host must still impose query/result-count and field-size
limits before rendering.

## Development and releases

Run `jolt -M:test` with Jolt v0.7.27 or newer. A release is an immutable Git
tag pointing at a commit for which the test workflow passed; consumers should
continue to pin that commit SHA even when also recording the tag.

This project is licensed under the Eclipse Public License 2.0; see `LICENSE`.
