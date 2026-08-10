# orgstar (org-*)

universal org-mode.

Many targets, many backends.

Targets
- clojure / babashka
- CLI
- Python (planned)

Backends
- emacsclient
- emacs pod (planned)

Like [yamlstar][], the aim is to have a single shared library and 
bindings for whatever language wants org. 

Yaml is huge, org mode is huger. Nobody knows org like emacs. So emacs 
itself is one of many selectable and delectable backends.

Status: alpha, v0. One backend (`:emacs`), a handful of ops.

[yamlstar]: https://yamlstar.org
[cljbang-org]: https://github.com/kpassapk/cljbang-org

## Backends

### Emacsclient backend

Requires an Emacs server with [cljbang][] and [cljbang-org][] loaded:

```
emacsclient --eval "(and (fboundp 'cljbang-load-filen) (featurep 'cljbang-org) t)"  ;=> t
```

[cljbang]: https://github.com/borkdude/cljbang.el

## Queries

```clojure
(require '[orgstar.core :as org])

(org/keywords "server.org")
;=> {:title "Test server"
;    :filetags ":infra:"
;    :target [".. (project)" "/ssh:app@example: (server)"]}

(org/select "server.org" '(and (level 1) (todo "TODO")))
;=> [{:title "Quadlets" :level 1 :todo "TODO" :tags #{} :properties {:CUSTOM_ID "quadlets"} ...}]
```

Keys are downcased. A keyword the file writes once is a string, one it
writes twice is a vector in file order. A `#+name:` or `#+caption:`
looks the same and is not a file keyword: it belongs to the block or
table below it, and is read there.

Pass a collection of files and the answer is a map from file to result,
read in a single round trip:

```clojure
(org/select (map str (fs/glob "work" "*.org")) '(level 1))
;=> {"work/a.org" [...] "work/b.org" [...]}
```

## Ops are data

Every function is one op. `run!` takes them as a vector and runs them
together, in order, in one round trip.

```clojure
(org/run! [[:set-todo! f {:title title :level 1} "DONE"]
           [:set-keyword! f :filetags ":infra:archive:"]
           [:save! f]])
;=> [1 1 "/abs/path/f.org"]
```

Writes edit the buffer visiting the file and stop there. `save!` is the
separate step that touches disk, so a script that goes wrong halfway
leaves the file as it was.

### Selectors and queries

A **selector** names a heading you already mean: `"Quadlets"`,
`{:custom-id "quadlets"}`, `{:title "Quadlets" :level 1}`, or a heading
map a query returned.

A **query** describes headings you are looking for, and is an
[org-ql][] sexp passed to `select`. 

The two meet in the heading map:
`select` returns one, and every setter takes it as a selector.

[org-ql]: https://github.com/alphapapa/org-ql

## CLI

```
$ bb orgstar keywords notes.org
{:title "Notes"}
$ bb orgstar keywords --json notes.org
{ "title" : "Notes" }
$ bb orgstar select '(todo "TODO")' work/*.org
$ bb orgstar headings --body notes.org
```

The CLI carries only queries. It exists this early because the data
crossing it is the data the language bindings will get: a shape that
reads badly as JSON here will read badly there.

## API

| Function | Op | Does |
|---|---|---|
| `keywords` | `[:keywords file]` | the `#+KEYWORD:` lines, downcased |
| `headings` | `[:headings file opts]` | every heading as a map |
| `select` | `[:select file query opts]` | headings matching an org-ql sexp |
| `set-todo!` | `[:set-todo! file sel state]` | set TODO state; the count |
| `set-keyword!` | `[:set-keyword! file key value]` | write `#+KEY:` lines; the count |
| `set-property!` | `[:set-property! file sel key value]` | set a property; the count |
| `set-tags!` | `[:set-tags! file sel tags]` | set tags; the count |
| `schedule!` | `[:schedule! file sel time]` | set SCHEDULED; the count |
| `deadline!` | `[:deadline! file sel time]` | set DEADLINE; the count |
| `save!` | `[:save! file]` | write the buffer to disk |
| `revert!` | `[:revert! file]` | throw the buffer's edits away |

`file` may be a collection for the three queries.

## Backends

`orgstar.core/*backend*` picks who answers. `:emacs` is the only one.

Two more are wanted:

- **pod** — [pod-kpassapk-emacs][] drives a batch Emacs of its own,
  installing cljbang-org with `use-package!`. No live Emacs needed, no
  edits in the user's session.
- **native** — a Clojure parser, no Emacs at all, compiled (Glojure or
  let-go) into the shared library the bindings load. 

[pod-kpassapk-emacs]: https://github.com/kpassapk/pod-kpassapk-emacs

## Tests

```
bb test
```

They run against a real Emacs, because a real Emacs is the backend.
Writes go to a copy in a temp directory and kill the buffer afterwards.
With no Emacs server answering, the run says so and passes.
