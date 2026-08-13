(ns orgstar.core
  (:refer-clojure :exclude [run!])
  (:require
   [orgstar.emacs :as emacs]))

(def ^:dynamic *backend*
  :emacs)

(defn run!
  "Run OPS, a vector of ops, in order and together; a vector of results.

  (run! [[:set-todo! f {:title t :level 1} \"DONE\"]
         [:set-keyword! f :filetags \":archive:\"]
         [:save! f]])"
  [ops]
  (case *backend*
    :emacs (emacs/run ops)
    (throw (ex-info (str "orgstar: no such backend: " *backend*) {:backend *backend*}))))

(defn- run1 [op] (first (run! [op])))

;;; Queries

(defn keywords
  "The `#+KEYWORD:' lines of FILE as a map, keyed by downcased name.

  (keywords \"server.org\")
  ;=> {:title \"Test server\"}

  Excludes `#+name:' or `#+caption:', which look like keywords but are not.

  FILE may be a collection, and then the answer is a map from file to
  its keywords, read in one round trip."
  [file]
  (run1 [:keywords file]))

(defn headings
  "Every heading in FILE as a vector of heading maps."
  ([file] (run1 [:headings file]))
  ([file opts] (run1 [:headings file opts])))

(defn src-blocks
  "Every src block in FILE as a vector of block maps."
  ([file] (run1 [:src-blocks file]))
  ([file opts] (run1 [:src-blocks file opts])))

(defn select
  "Headings in FILE matching QUERY, an org-ql sexp, as heading maps.

  (select f '(and (level 1) (todo \"TODO\")))

  FILE may be a collection, and then the answer is a map from file to
  its matches -- one round trip for the lot, which is the point of
  passing them together."
  ([file query] (run1 [:select file query]))
  ([file query opts] (run1 [:select file query opts])))

;;; Effects

(defn set-todo!
  "Set the TODO state of every heading in FILE matching SELECTOR; the count.

  STATE is one of the file's own keywords as a string, or nil to leave
  the heading with no state.  This is `org-todo' doing it, so a
  repeating task rolls its SCHEDULED stamp forward and the logbook gets
  what it is owed.  Edits the buffer only; `save!' persists."
  [file selector state]
  (run1 [:set-todo! file selector state]))

(defn set-keyword!
  "Set the `#+KEY:' lines of FILE to VALUE; the number of lines written.

  VALUE is a string, or a vector of them for a keyword written on
  several lines, or nil to remove it.  The keyword replaces: every
  existing `#+KEY:' line gives way to the new ones.  Adding a value is
  `conj' on what `keywords' returned.  Edits the buffer only; `save!'
  persists."
  [file key value]
  (run1 [:set-keyword! file key value]))

(defn set-property!
  "Set property KEY to VALUE on every heading in FILE matching SELECTOR; the count."
  [file selector key value]
  (run1 [:set-property! file selector key value]))

(defn set-tags!
  "Set the tags of every heading in FILE matching SELECTOR; the count."
  [file selector tags]
  (run1 [:set-tags! file selector tags]))

(defn schedule!
  "Set SCHEDULED on every heading in FILE matching SELECTOR; the count.
  TIME is anything `org-schedule' reads, or nil to remove the line."
  [file selector time]
  (run1 [:schedule! file selector time]))

(defn deadline!
  "Set DEADLINE on every heading in FILE matching SELECTOR; the count.
  TIME is anything `org-deadline' reads, or nil to remove the line."
  [file selector time]
  (run1 [:deadline! file selector time]))

(defn save!
  "Save FILE's buffer if modified; the file name.  The only step that touches disk."
  [file]
  (run1 [:save! file]))

(defn revert!
  "Reload FILE from disk, throwing the buffer's edits away; the file name."
  [file]
  (run1 [:revert! file]))
