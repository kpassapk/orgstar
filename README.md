# orgstar (org-*)

universal [Emacs] org-mode.

Many targets, many backends:

Targets
- clojure / babashka
- CLI
- Python (planned)

Like [yamlstar][], the aim is to have a single shared library and 
bindings for whatever language wants org. 

Nobody knows org like emacs - so emacs 
itself is one of many selectable and delectable backends.

Status: alpha, v0. One backend (`:emacs`), a handful of ops.

[yamlstar]: https://yamlstar.org
[cljbang-org]: https://github.com/kpassapk/cljbang-org

## Backends

### Pod backend

The pod backend uses [pod-kpassapk-emacs][] to drive an
Emacs instance. On the first op the pod installs
[cljbang-org][] and [org-ql][] into that Emacs with `use-package!`.

[pod-kpassapk-emacs]: https://github.com/kpassapk/pod-kpassapk-emacs

Set `ORGSTAR_POD` to a pod binary to run against a local build instead
of the released one.

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
| `src-blocks` | `[:src-blocks file opts]` | every src block as a map |
| `call-blocks` | `[:call-blocks file opts]` | every `#+call:` line as a map |
| `drawers` | `[:drawers file opts]` | every `:NAME:` … `:END:` drawer as a map |
| `examples` | `[:examples file opts]` | fixed-width runs and example blocks as maps |
| `set-todo!` | `[:set-todo! file sel state]` | set TODO state; the count |
| `set-keyword!` | `[:set-keyword! file key value]` | write `#+KEY:` lines; the count |
| `set-property!` | `[:set-property! file sel key value]` | set a property; the count |
| `set-tags!` | `[:set-tags! file sel tags]` | set tags; the count |
| `schedule!` | `[:schedule! file sel time]` | set SCHEDULED; the count |
| `deadline!` | `[:deadline! file sel time]` | set DEADLINE; the count |
| `execute!` | `[:execute! file sel opts]` | run a block; `{:value :exit :stdout :stderr}` |
| `save!` | `[:save! file]` | write the buffer to disk |
| `revert!` | `[:revert! file]` | throw the buffer's edits away |

`file` may be a collection for the queries.

### Running blocks

`execute!` runs a src block or a `#+call:` line and answers with a map
whatever the exit code: a block that exits non-zero is a result to
decide about, not an error. `{:inputs {"input-instance" "aly-andina"}}`
binds values for the references the run resolves — a `:var` naming an
example or a block, a call line's arguments — in place of what the file
names, and the file is never edited.

The babel backends the blocks need, and anything that advises babel,
are declared before the first op:

```clojure
(require '[orgstar.emacs :as emacs])
(emacs/configure! {:packages ['ob-shell
                              '(devops :vc (:url "https://github.com/kpassapk/devops.el"))]})
```

Set `ORGSTAR_CLJBANG_ORG` to a cljbang-org checkout to run against it
instead of the pinned release.


## Tests

```
bb test
```

They run against a real Emacs, because a real Emacs is the backend.
Writes go to a copy in a temp directory. With no Emacs to be found, the
run says so and passes.

