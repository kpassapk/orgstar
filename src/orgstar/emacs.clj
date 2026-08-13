(ns orgstar.emacs
  "The Emacs backend: org read and written by Emacs itself.

  Nothing here parses org.  An op is translated to a cljbang form, the
  forms of one call go over to Emacs as a single program, and what comes
  back is org-mode's own answer -- org-element for the reading,
  `org-todo' and friends for the writing.

  The translation is the whole backend, and it is deliberately the only
  place that knows cljbang-org exists: `orgstar.core' speaks ops, a
  native backend will speak the same ops, and the shapes returned here
  are orgstar's, not cljbang's.  Where the two differ -- file keywords
  are the one case in v0 -- the difference is repaired here, on the way
  out.

  Transport is pod-kpassapk-emacs, a babashka pod driving a batch Emacs
  of its own.  There is no server to start and no init file to read: the
  elisp the backend needs is installed into that Emacs on first use, so
  what a caller has to have is an `emacs' binary.  The Emacs belongs to
  the process, which is what makes `:save!' worth its own op and worth
  going in the same `run' as the edits it persists -- a buffer left
  modified is gone when the process exits."
  (:require
   [babashka.pods :as pods]
   [clojure.string :as str]))

;;; Transport

(def ^:private pod-coords
  "The released pod to load, unless $ORGSTAR_POD names a binary."
  ['kpassapk/emacs "0.4.0"])

(def ^:private packages
  "The elisp the ops need, as `use-package' declarations, in load order.

  cljbang itself is not here: the pod vendors it, being what compiles
  the Clojure it is sent.  org-ql is a package of its own and only
  `:select' wants it, but it is installed with the rest rather than on
  first use, so that the cost of a backend is paid in one place."
  ['(cljbang-org :vc (:url "https://github.com/kpassapk/cljbang-org"))
   '(org-ql :ensure t)
   '(cljbang-org-ql :after (cljbang-org org-ql))])

(defn- start!
  "Load the pod, install the packages, and hand back its `eval-clj'.

  Once per process, behind a delay: starting Emacs and installing into
  it is the whole cost of the backend, the first op pays it, and every
  op after that is a round trip to an Emacs already holding the buffers
  the earlier ops opened."
  []
  (if-let [bin (System/getenv "ORGSTAR_POD")]
    (pods/load-pod [bin])
    (apply pods/load-pod pod-coords))
  (require 'pod.kpassapk.emacs)
  (run! (resolve 'pod.kpassapk.emacs/use-package!) packages)
  (resolve 'pod.kpassapk.emacs/eval-clj))

(defonce ^:private emacs (delay (start!)))

(defn- eval-clj
  "Run CODE, a string of cljbang Clojure, in the pod's Emacs; its value.

  The last form's value comes back as EDN, already read, so the shapes
  here are the ones cljbang-org returned.  An error inside Emacs arrives
  as an `ex-info' and is rethrown carrying the program that caused it:
  the program is generated, and generated code is what a caller cannot
  see from the stack trace."
  [code]
  (try
    (@emacs code)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (str "orgstar: emacs: " (ex-message e))
                      (assoc (ex-data e) :code code)
                      e)))))

(defn available?
  "True when the pod starts and its Emacs has the packages loaded."
  []
  (try
    (boolean @emacs)
    (catch Exception _ false)))

(defn upgrade-packages []
  (let [code
        "(el! (let ((package (alist-get 'cljbang-org package-alist)))
          (package-vc-upgrade (car package))))"]
    (eval-clj code)))

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

(defmethod form :src-blocks [[_ file opts]]
  (files-form file (fn [f] (if opts (list 'org/src-blocks f opts) (list 'org/src-blocks f)))))

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
                    (pr-str (mapv form ops)) "\n")
          results (eval-clj code)]
      (mapv decode ops results))))

(comment
  (upgrade-packages)
  )
