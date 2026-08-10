# 0001 — Ops are data, and the backend is swappable

Status: accepted, 2026-08-10

## Context

orgstar is meant to end up where yamlscript is: a core, a shared
library, bindings in several languages. It starts somewhere else —
Emacs answers every question, through cljbang-org in a running Emacs —
and the two ends have to be the same API, or the native backend arrives
as a rewrite rather than a swap.

Two API shapes were on the table:

1. Specter-like: selectors that navigate, and mutation through them.
2. Clojure-like: read a file into one big structure, print it back.

Neither survives contact with the actual first client
(`aly-infra/bb/work.clj`), which needs `org/keywords`,
`org/set-todo!`, `org/set-keyword!` and `ql/select` — reads *and*
writes, on many files, without paying a round trip each.

## Decision

**An op is data.** `[:set-todo! file selector "DONE"]`. The public
functions are one op each; `run!` takes a vector of them and runs them
together, in order, in one crossing.

**Reads return data; nothing returned is a handle.** No cursor into a
buffer ever leaves the backend.

**Writes edit the buffer and stop.** `save!` is the separate step that
touches disk, so a script that dies halfway leaves the file alone.

**Selectors are references, not searches**, and are cljbang-org's:
a title, a `:custom-id`, a title-and-level, or a heading map a query
returned. Searching is `select` with an org-ql sexp.

**The backend boundary is where shapes are fixed.** The Emacs backend
translates cljbang-org's answers into orgstar's — today that is file
keywords, downcased and split — so a native backend has a contract to
meet rather than a convention to guess at.

## Why not Specter

Because of where this is going. The FFI boundary carries strings and
JSON: a Python binding can send `[:set-todo!, file, {...}, "DONE"]` and
cannot send a navigator, a closure or a transform function. A selector
DSL rich enough to be worth the name would be a Clojure-only feature in
a library whose reason to exist is not being Clojure-only.

Nothing stops a Specter-flavoured layer later, in Clojure, over these
ops. It would be sugar, and it would compile to the same data.

## Why not one big structure

`(parse file)` returning the whole document is a fine query and a bad
edit: writing it back means orgstar re-emitting org, and org's own
commands stop being involved. That is the thing worth keeping about the
Emacs backend — `set-todo!` is `org-todo`, so a repeating task
re-schedules itself and the logbook gets its entry. A whole-document
read may still arrive as a query; it will not be how edits are made.

## Consequences

- The op vector is the wire format, early, and JSON-shaped by
  construction. The CLI prints exactly what a binding will receive.
- Batching is explicit and available: `run!` over many ops, or a
  collection of files to a query, is one round trip.
- `:tags` is a set and does not survive JSON as one. Left alone in v0;
  a `parse`-level shape decision to make before the bindings exist.
- The native backend has to reproduce Emacs's answers, not just parse
  org. Where it cannot (transclusion, org-ql, babel), the op belongs to
  the Emacs backend and says so.
