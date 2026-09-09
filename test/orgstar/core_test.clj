(ns orgstar.core-test
  "Tests against a real Emacs, because a real Emacs is the backend.

  The reads run on test/fixtures/sample.org.  The writes run on a copy
  in a temp directory, and read it back through `revert!': the Emacs
  behind the pod holds the buffer the writes edited, so reading a
  written file without reverting would be reading the writes rather than
  the file."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [orgstar.core :as org]
   [orgstar.emacs :as emacs]))

(def sample (str (fs/absolutize "test/fixtures/sample.org")))

(use-fixtures :once
  (fn [f]
    (if (emacs/available?)
      (f)
      (println "SKIP: the pod's Emacs did not start; see the message above."))))

(deftest keywords-test
  (when (emacs/available?)
    (let [kws (org/keywords sample)]
      (testing "keys are downcased, a single value is a string"
        (is (= "Test server" (:title kws)))
        (is (= ":infra:" (:filetags kws))))
      (testing "a keyword written twice comes back as a vector, in file order"
        (is (= [".. (project)" "/ssh:app@example: (server)"] (:target kws))))
      (testing "an affiliated keyword belongs to the block below it, not the file"
        (is (nil? (:name kws)))
        (is (nil? (:caption kws))))
      (testing "the file's own keywords, and only those"
        (is (= #{:title :filetags :target} (set (keys kws))))))))

(deftest keywords-many-files-test
  (when (emacs/available?)
    (is (= {sample "Test server"}
           (update-vals (org/keywords [sample]) :title)))))

(deftest select-test
  (when (emacs/available?)
    (let [hs (org/select sample '(level 1))]
      (is (= ["Quadlets" "Networking"] (mapv :title hs)))
      (is (= "TODO" (:todo (first hs)))))
    (is (= ["Quadlets" "Firewall"]
           (mapv :title (org/select sample '(todo "TODO")))))))

(deftest headings-test
  (when (emacs/available?)
    (let [hs (org/headings sample)]
      (is (= ["Quadlets" "Networking" "Firewall"] (mapv :title hs)))
      (is (= [1 1 2] (mapv :level hs)))
      (is (= "quadlets" (get-in (vec hs) [0 :properties :CUSTOM_ID]))))))

(deftest headings-line-range-test
  (when (emacs/available?)
    (let [hs (org/headings sample)]
      (testing "the span is the subtree's, in lines, inclusive at both ends"
        (is (= [["Quadlets" 6 18] ["Networking" 19 20] ["Firewall" 20 20]]
               (mapv (juxt :title :line-start :line-end) hs))))
      (testing "the lines are what a caller reads the section back with"
        (let [lines (vec (str/split-lines (slurp sample)))
              {:keys [line-start line-end]} (first hs)]
          (is (= "* TODO Quadlets" (nth lines (dec line-start))))
          (is (= "" (nth lines (dec line-end))))
          (is (some #(str/includes? % "Rootless containers")
                    (subvec lines (dec line-start) line-end))))))))

(deftest src-blocks-test
  (when (emacs/available?)
    (let [[b :as bs] (org/src-blocks sample)]
      (is (= 1 (count bs)))
      (testing "the block is data: its language, its name and its code"
        (is (= "sh" (:language b)))
        (is (= "not-a-keyword" (:name b)))
        (is (= "echo hi" (:body b))))
      (testing "the span frames the block, from #+begin_src to #+end_src"
        (is (= [15 17] [(:line-start b) (:line-end b)])))
      (testing ":headers carries the resolved header args"
        (is (= "no" (get-in b [:headers :tangle] "no")))))))

(deftest src-blocks-under-test
  (when (emacs/available?)
    (is (= 1 (count (org/src-blocks sample {:under "Quadlets"}))))
    (is (= [] (vec (org/src-blocks sample {:under "Networking"}))))))

(deftest write-test
  (when (emacs/available?)
    (let [dir  (fs/create-temp-dir {:prefix "orgstar-test"})
          file (str (fs/path dir "task.org"))]
      (try
        (fs/copy sample file)
        (testing "several edits and the save that persists them, in one round trip"
          (is (= [1 1 file]
                 (org/run! [[:set-todo! file {:title "Quadlets" :level 1} "DONE"]
                            [:set-keyword! file :filetags ":infra:archive:"]
                            [:save! file]]))))
        (testing "the edits are on disk and read back through the same API"
          (org/revert! file)
          (is (= ":infra:archive:" (:filetags (org/keywords file))))
          (is (= "DONE" (:todo (first (org/select file '(level 1)))))))
        (testing "a keyword set to a vector writes one line each"
          (org/run! [[:set-keyword! file :target ["a" "b" "c"]] [:save! file]])
          (org/revert! file)
          (is (= ["a" "b" "c"] (:target (org/keywords file)))))
        (finally
          (fs/delete-tree dir))))))

(def runbook (str (fs/absolutize "test/fixtures/runbook.org")))

(deftest runnables-test
  (when (emacs/available?)
    (testing "src blocks and call lines share one index"
      (is (= [["check" :src 0] ["fail" :src 1] [nil :call 2]]
             (->> (concat (org/src-blocks runbook) (org/call-blocks runbook))
                  (sort-by :index)
                  (mapv (juxt :name :type :index))))))))

(deftest drawers-test
  (when (emacs/available?)
    (let [[d :as ds] (org/drawers runbook)]
      (is (= 1 (count ds)))
      (is (= "OPERATOR" (:name d)))
      (is (= "Continue if the instance is the right one." (:body d)))
      (testing "the drawer's heading is the one whose span holds it"
        (is (= "Check"
               (->> (org/headings runbook)
                    (filter #(<= (:begin %) (:begin d) (:end %)))
                    last :title)))))))

(deftest examples-test
  (when (emacs/available?)
    (is (= [["input-instance" "aly-andina" "Instance to deploy" :fixed-width]]
           (mapv (juxt :name :value :caption :type) (org/examples runbook))))))

(deftest execute-test
  (when (emacs/available?)
    (let [dir  (fs/create-temp-dir {:prefix "orgstar-test"})
          file (str (fs/path dir "runbook.org"))]
      (try
        (fs/copy runbook file)
        (testing "a clean block: value, exit 0"
          (is (= {:value "checking aly-andina\n" :exit 0
                  :stdout "checking aly-andina\n" :stderr nil}
                 (org/execute! file "check"))))
        (testing "an input replaces what the file names, without editing it"
          (is (= "checking aly-norte\n"
                 (:value (org/execute! file "check" {:inputs {"input-instance" "aly-norte"}}))))
          (is (= "checking aly-norte\n"
                 (:value (org/execute! file {:index 2} {:inputs {"input-instance" "aly-norte"}})))))
        (testing "a failing block is a result, not a throw"
          (is (= {:exit 3 :stdout "partial\n" :stderr "oops\n"}
                 (select-keys (org/execute! file "fail") [:exit :stdout :stderr]))))
        (testing "a block that cannot run throws"
          (is (thrown? Exception (org/execute! file "no-such-block"))))
        (testing "results were in the buffer; revert! leaves the file as it was"
          (org/revert! file)
          (is (= (slurp runbook) (slurp file))))
        (finally
          (fs/delete-tree dir))))))

(deftest configure-after-start-test
  (when (emacs/available?)
    (is (thrown-with-msg? Exception #"configure! after"
                          (emacs/configure! {:packages ['ob-shell]})))))
