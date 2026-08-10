(ns orgstar.cli
  "orgstar from a shell.

  The commands are the API's queries and nothing else: the point of a
  CLI this early is that the data crossing it is the same data a
  binding in another language will get, so a shape that reads badly as
  JSON here reads badly there too."
  (:require
   [babashka.cli :as cli]
   [cheshire.core :as json]
   [clojure.pprint :as pp]
   [clojure.edn :as edn]
   [orgstar.core :as org]))

(def ^:private spec
  {:json {:desc "Print JSON instead of EDN" :coerce :boolean}
   :body {:desc "Include each heading's own text as :body" :coerce :boolean}})

(defn- emit [{:keys [json]} value]
  (if json
    (println (json/generate-string value {:pretty true}))
    (pp/pprint value)))

(defn- usage []
  (println "Usage: orgstar <command> [args] [--json]")
  (println)
  (println "  keywords <file>...          the file's #+KEYWORD: lines")
  (println "  headings <file>... [--body] every heading, as data")
  (println "  select <query> <file>...    headings matching an org-ql sexp")
  (println)
  (println "Several files answer as a map from file to result, in one round trip."))

(defn- files [args] (if (= 1 (count args)) (first args) (vec args)))

(defn -main [& argv]
  (let [{:keys [args opts]} (cli/parse-args argv {:spec spec})
        [cmd & more] args
        hopts (when (:body opts) {:body? true})]
    (case cmd
      "keywords" (emit opts (org/keywords (files more)))
      "headings" (emit opts (if hopts
                              (org/headings (files more) hopts)
                              (org/headings (files more))))
      "select"   (let [[query & fs] more]
                   (emit opts (if hopts
                                (org/select (files fs) (edn/read-string query) hopts)
                                (org/select (files fs) (edn/read-string query)))))
      (do (usage) (System/exit (if cmd 1 0))))))
