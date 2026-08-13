(ns orgstar.cli
  (:require
   [babashka.cli :as cli]
   [orgstar.core :as org]
   [orgstar.emacs :as emacs]
   [cheshire.core :as json]
   [clojure.pprint :as pp]))

(defn- emit [{:keys [json]} value]
  (if json
    (println (json/generate-string value {:pretty true}))
    (pp/pprint value)))

(defn keywords [opts]
  (org/keywords opts))

(defn headings [{:as opts :keys [file]}]
  (emit opts (org/headings file)))

(defn select [{:as opts :keys [file query]}]
  (org/select file query))

(defn update-elisp [_]
  (emacs/upgrade-packages))

(def tree
  {:cmd
   {"get"
    {:cmd
     {"keywords"
      {:desc "Get a file's #+KEYWORD: lines"
       :exec-fn keywords
       :args->opts [:file]
       :spec {:file {:require true}}}
      "headings"
      {:desc "Get every heading as data"
       :exec-fn headings
       :args->opts [:file]}
      "select"
      {:desc "headings matching an org-ql sexp"
       :exec-fn select
       :args->opts [:file]
       :spec {:query {:desc "org-ql query"
                      :require true}}}}}
    "elisp"
    {:cmd 
     {"update"
      {:desc "Update elisp"
       :exec-fn update-elisp}}}}})

(defn -main [& args]
  (cli/dispatch tree args {:prog "orgstar" :help true}))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
