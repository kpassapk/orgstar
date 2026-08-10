(ns orgstar.emacs
  "The Emacs backend: org read and written by Emacs itself.

  Nothing here parses org.  An op is translated to a cljbang form, the
  forms of one call go over to a running Emacs as a single program, and
  what comes back is org-mode's own answer -- org-element for the
  reading, `org-todo' and friends for the writing.

  The translation is the whole backend, and it is deliberately the only
  place that knows cljbang-org exists: `orgstar.core' speaks ops, a
  native backend will speak the same ops, and the shapes returned here
  are orgstar's, not cljbang's.  Where the two differ -- file keywords
  are the one case in v0 -- the difference is repaired here, on the way
  out.

  Transport is `emacsclient', so the Emacs is the one the user is
  sitting in: an edit lands in the buffer they have open, and
  `:save!' is what puts it on disk.  A batch Emacs behind the pod
  (pod-kpassapk-emacs) is the other transport worth having and is not
  written yet."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def ^:dynamic *socket*
  "The Emacs server socket to talk to, or nil for emacsclient's default."
  nil)

;;; Transport

(defn- emacsclient
  "Run CODE, a string of cljbang Clojure, in the Emacs server.

  cljbang reads a file rather than a string, so the program goes through
  a temp file; the value comes back as whatever the program printed with
  `pr-str', which emacsclient prints again as an elisp string -- hence
  the two reads."
  [code]
  (let [f (fs/create-temp-file {:prefix "orgstar" :suffix ".clj"})]
    (try
      (spit (fs/file f) code)
      (let [args (cond-> ["emacsclient"]
                   *socket* (conj "-s" *socket*)
                   :always  (conj "--eval" (str "(cljbang-load-file " (pr-str (str f)) ")")))
            {:keys [out err exit]} (apply p/shell {:out :string :err :string :continue true} args)
            out (str/trim out)]
        (when (or (not (zero? exit)) (str/starts-with? out "*ERROR*"))
          (throw (ex-info (str "orgstar: emacs: " (if (str/blank? out) (str/trim err) out))
                          {:exit exit :code code})))
        (when (str/starts-with? out "\"")
          (edn/read-string (edn/read-string out))))
      (finally (fs/delete-if-exists f)))))

(defn available?
  "True when there is an Emacs server answering with cljbang-org loaded."
  []
  (let [{:keys [out exit]}
        (p/shell {:out :string :err :string :continue true}
                 "emacsclient" "--eval"
                 "(and (fboundp 'cljbang-load-file) (featurep 'cljbang-org) t)")]
    (and (zero? exit) (= "t" (str/trim out)))))

;;; Ops to cljbang forms

(defn- kw-name
  "KEY as org writes it: upcased, the shape cljbang-org keys keywords by."
  [key]
  (keyword (str/upper-case (name key))))

(defn- files-form
  "A form running BODY-FN over one file or over a collection of them.

  One file answers with its own result; a collection answers with a map
  from file to result, in a single round trip -- the reason a caller
  with forty task files does not pay forty of them.  It is `mapv' and
  not `for' because cljbang's reader does not bind seq-exprs."
  [file body-fn]
  (if (coll? file)
    (list 'into {} (list 'mapv (list 'fn '[f] ['f (body-fn 'f)]) (mapv str file)))
    (body-fn (str file))))

(defmulti ^:private form
  "The cljbang form for OP."
  first)

(defmethod form :keywords [[_ file]]
  (files-form file (fn [f] (list 'org/keywords f))))

(defmethod form :headings [[_ file opts]]
  (files-form file (fn [f] (if opts (list 'org/headings f opts) (list 'org/headings f)))))

(defmethod form :select [[_ file query opts]]
  (files-form file (fn [f] (if opts
                             (list 'ql/select f (list 'quote query) opts)
                             (list 'ql/select f (list 'quote query))))))

(defmethod form :set-todo! [[_ file selector state]]
  (list 'org/set-todo! (str file) selector state))

(defmethod form :set-keyword! [[_ file key value]]
  (list 'org/set-keyword! (str file) (kw-name key) value))

(defmethod form :set-property! [[_ file selector key value]]
  (list 'org/set-property! (str file) selector (kw-name key) value))

(defmethod form :set-tags! [[_ file selector tags]]
  (list 'org/set-tags! (str file) selector (vec tags)))

(defmethod form :schedule! [[_ file selector time]]
  (list 'org/schedule! (str file) selector time))

(defmethod form :deadline! [[_ file selector time]]
  (list 'org/deadline! (str file) selector time))

(defmethod form :save! [[_ file]]
  (list 'org/save! (str file)))

(defmethod form :revert! [[_ file]]
  (list 'org/revert! (str file)))

(defmethod form :default [[kind :as op]]
  (throw (ex-info (str "orgstar: no such op: " kind) {:op op})))

;;; cljbang shapes to orgstar shapes

(defn- keywords->orgstar
  "cljbang-org's keyword map as orgstar's.

  cljbang keys by the upcased name and joins a keyword written twice
  with a newline, which is lossless and asks the caller to split.
  orgstar downcases the key and hands back the values already apart:
  one string when the file said it once, a vector when it said it more
  than once.  A keyword's value cannot hold a newline, so the split is
  the file's own line breaks and nothing else.

  Which keywords are the file's own is cljbang-org's answer and stays
  there: a `#+name:' is affiliated to the block below it, org-element
  says so structurally, and a denylist of affiliated names here would be
  a second copy of org's own table, drifting."
  [m]
  (reduce-kv (fn [acc k v]
               (let [ls (str/split (str v) #"\n")]
                 (assoc acc (keyword (str/lower-case (name k)))
                        (if (= 1 (count ls)) (first ls) (vec ls)))))
             {} (or m {})))

(defn- per-file
  "Apply F to a single result, or to each value of a file-keyed map."
  [file result f]
  (if (coll? file)
    (reduce-kv (fn [acc k v] (assoc acc k (f v))) {} (or result {}))
    (f result)))

(defmulti ^:private decode
  "The op's reply in orgstar's shapes."
  (fn [op _result] (first op)))

(defmethod decode :keywords [[_ file] result]
  (per-file file result keywords->orgstar))

(defmethod decode :default [_ result] result)

;;; Running

(defn run
  "Run OPS in Emacs, in order, in one round trip; a vector of results.

  Every op is an effect as far as Emacs is concerned -- a query opens
  the file, a setter edits the buffer visiting it -- so the order is the
  order given, and a `:save!' at the end of the vector is what reaches
  disk.  An op that throws inside Emacs stops the program there: the ops
  before it have already run, and their buffers are left modified and
  unsaved rather than half-written to disk."
  [ops]
  (when (seq ops)
    (let [code (str "(require '[cljbang.org :as org] '[cljbang.org.ql :as ql])\n"
                    "(pr-str " (pr-str (mapv form ops)) ")\n")
          results (emacsclient code)]
      (mapv decode ops results))))
