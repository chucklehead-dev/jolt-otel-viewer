# Portable Plotje rendering spike

Status: planning spike. This is not yet a compatibility claim for Plotje.

Reference baseline: `scicloj/plotje` commit
`2e48024dbdf05c0374f8a9b3aaa7051d87a8a63f` (version 0.10.1).

## Goal

Compile one explicitly bounded, Plotje-compatible leaf pose to either:

1. deterministic static SVG that requires no JavaScript; or
2. plain Vega-Lite EDN/JSON that an optional external browser asset renders.

The compiler must not require Tablecloth, dtype-next, Kindly, Clay, Membrane,
Java2D, or notebook machinery. Those integrations may remain optional users of
the same pose vocabulary on runtimes that support them.

The observability viewer is a consumer, not the owner, of this compiler. It
continues to work without charts and without JavaScript.

## Boundary

The portable input is a single-leaf subset of Plotje's pose shape, not a
second plotting DSL and not the full live Plotje constructor API:

```clojure
{:data [{:bucket "10:00" :p95-ms 31.0 :service "api"}
        {:bucket "10:01" :p95-ms 44.0 :service "api"}]
 :mapping {:x :bucket :y :p95-ms :color :service}
 :layers [{:layer-type :line}
          {:layer-type :point}]
 :opts {:title "p95 latency" :width 720 :height 240}}
```

Portable data normalizes to a bounded columnar value with declared column
order and types. An authoring adapter may accept row maps or a map of equally
sized columns, but it must define sparse-row filling, exact keyword/string key
identity, empty and all-missing columns, stable categorical order, and type
inference before either renderer runs. Values are nil, booleans, finite
numbers, strings, or keywords. Temporal values normalize to one documented
epoch unit; runtime-specific date objects do not cross the boundary.

Mappings support Plotje's plain, `{:column ...}`, `{:value ...}`, and
`{:from ...}` spellings. Plain values are classified against the effective
layer data, including strict keyword-versus-string column identity. Mapping
inheritance must preserve Plotje's scale-map merge, `:scale true`, `:scale
false`, scale-only child, source replacement, and explicit-nil cancellation
rules. Layers may override data and mappings as Plotje leaf poses do.

V1 rejects non-empty `:poses`, `:layout`, `:share-scales`, facet options, and
unattached templates before normalization. It does not claim to reproduce
`pj/pose`, `pj/lay-*`, promotion, DFS-last layer placement, or other live
constructor behavior. A separate JVM adapter may use full Plotje to construct
and characterize a pose before lowering supported leaves to this boundary.

Unknown layer types, options, mappings, non-finite values, malformed columns,
and target-specific unsupported features fail with path-bearing `ex-data`.
They must not disappear silently.

## Pipeline

```text
supported leaf pose + canonical bounded data
            |
            v
 validate, normalize, resolve inheritance
            |
            v
 target-neutral semantic plot
      +-----+------------------+
      |                        |
      v                        v
 local scales/layout      Vega-Lite compiler
      |                        |
      v                        v
 SVG string/hiccup         EDN/JSON map
```

Semantic normalization is shared. Pixel geometry is not: the SVG target owns
its small layout engine, while Vega-Lite remains free to plan its own scene.

Proposed public surface:

```clojure
(plotje.portable/validate pose)
(plotje.portable/capabilities :svg)
(plotje.portable/capabilities :vega-lite)

(plotje.portable/render pose {:target :svg})
;; => {:media-type "image/svg+xml" :body "<svg ...>...</svg>"}

(plotje.portable/render pose {:target :vega-lite})
;; => {:media-type "application/vnd.vegalite.v6+json" :body {...}}
```

Backend capability queries allow callers to select a target without relying
on exceptions. Validation still fails closed if a selected target cannot
preserve the requested semantics.

## First shared subset

- Layers: `:point`, `:line`, and `:area`. `:bar` is accepted only with explicit
  x and y mappings, `:stat :identity`, and `:position :identity`.
- Aesthetics: `:x`, `:y`, `:color`, `:size`, `:shape`, `:alpha`, and tooltip
  fields where the selected backend supports them.
- Scales: linear, log, categorical, and explicit temporal.
- Composition: ordered layers in one leaf panel. Composite poses and facets
  are rejected rather than flattened or ignored.
- Chrome: title, dimensions, axes, ticks, labels, legend, and a small
  CSS-variable-based theme.
- Positions: explicit identity only; stack and dodge follow only after shared
  semantic tests exist.
- Statistics: explicit identity only in the first proof. chDB/ClickHouse supplies
  already aggregated observability rows. Shared bin/count transforms may be
  added later; Vega-Lite-only aggregation would create backend drift.

Plotje's x-only `:bar` count behavior, `:tile`/`:bin2d`, `:rule-h`, and
`:rule-v` are deferred until their exact registry, stat, mapping, and position
contracts have characterization tests.

The direct SVG renderer emits escaped XML, stable element order, deterministic
IDs derived from the input, a bounded element count, `role="img"`, and an
accessible title/description. It emits no script, foreign object, event
handler, remote reference, or caller-supplied raw markup.

The Vega-Lite target emits data, marks, encodings, scales, layers, and chrome
as plain values. It emits no JavaScript expression. The host owns the pinned
Vega/Vega-Lite browser assets and may render the map from an external,
strict-CSP-compatible enhancement script. A chart retains its static SVG or
tabular fallback if those assets are absent or fail.

## Proof fixtures

The same three literal poses drive both targets:

1. latency time series: line plus point, grouped by service;
2. error volume: categorical x plus numeric y bars grouped by severity, with
   explicit identity stat and position; and
3. duration distribution: externally pre-binned x/y bars with explicit
   identity stat and position.

Tests should cover:

- portable leaf normalization equivalence on JVM Clojure and Jolt;
- all four mapping spellings, keyword/string collisions, layer-owned data,
  scale-map inheritance, scale true/false, and nil cancellation;
- explicit rejection of flat/nested composites, layouts, shared scales,
  facets, unattached templates, and inferred stats/positions;
- row/column authoring equivalence, sparse rows, empty/all-missing columns,
  key collisions, stable category order, and temporal normalization;
- deterministic SVG and deterministic Vega-Lite maps;
- column/mapping validation and path-bearing failures;
- XML and JSON text escaping, including hostile labels;
- finite scale domains, monotonic coordinates, and zero-width domains;
- hard row, column, layer, facet, dimension, and SVG-element limits;
- no `NaN`, infinity, script, event attribute, or external reference in SVG;
- both backends preserving layer order, grouping, domains, and labels; and
- Hegel-generated row sets and malformed poses with replayable seeds.

Golden tests should assert semantic structure, not every formatting byte. A
small number of full SVG fixtures may guard deterministic serialization.

## Viewer integration

The viewer receives bounded chart declarations from its host:

```clojure
{:charts [{:id :latency
           :title "p95 latency"
           :pose latency-pose
           :fallback-rows rows}]}
```

An optional adapter renders the SVG before Selmer receives the model. The
template treats only the renderer-produced SVG artifact as safe; titles,
captions, labels, and fallback cells remain normally escaped.

For progressive enhancement, the page keeps the SVG baseline and associates a
Vega-Lite artifact with the figure. The external viewer asset may activate it
in place. It must not use inline code, `eval`, `new Function`, remote CDN
scripts, or instrument viewer/receiver traffic and thereby create a telemetry
feedback loop.

Initial observability templates are latency over time, error rate or error
volume, span-duration distribution, and top attribute values. Their queries
stay in the host/query layer; the chart compiler never opens a database.

## Implementation slices

1. Characterize the three Plotje leaf poses plus mapping inheritance and
   explicit rejection cases at the reference commit; freeze portable normalized
   fixtures containing resolved sources, types, groups, domains, layer order,
   and labels.
2. Implement pure canonical-data validation, exact supported mapping
   inheritance, capability reporting, and the line/point/area/bar semantic
   subset on both Jolt and JVM Clojure.
3. Implement deterministic SVG with axes and categorical color, then run the
   shared and Hegel gates.
4. Implement Vega-Lite EDN output and a CSP-safe opt-in viewer enhancement
   against locally served pinned assets.
5. Integrate the three proof fixtures into an example page that displays the
   static and enhanced forms side by side.
6. Add Plotje's count bars, rule-h/rule-v, tile/bin2d, shared transforms,
   composites/facets, stack/dodge, and constructor compatibility only behind
   cross-target semantic tests.

The spike should begin as a narrow module or branch against Plotje's live pose
contract. A permanent fork, separate incompatible DSL, release, push, or PR is
not part of this plan.
