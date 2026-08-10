(ns orgstar.core-test
  "Tests against a real Emacs, because a real Emacs is the backend.

  The reads run on test/fixtures/sample.org.  The writes run on a copy
  in a temp directory, and kill the buffer afterwards so a test leaves
  nothing open in the Emacs the user is sitting in."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [orgstar.core :as org]
   [orgstar.emacs :as emacs]))

(def sample (str (fs/absolutize "test/fixtures/sample.org")))

(defn- kill-buffer! [file]
  (p/shell {:out :string :err :string :continue true}
           "emacsclient" "--eval"
           (str "(let ((b (get-file-buffer " (pr-str (str file)) "))) "
                "  (when b (with-current-buffer b (set-buffer-modified-p nil)) (kill-buffer b)) t)")))

(use-fixtures :once
  (fn [f]
    (if (emacs/available?)
      (f)
      (println "SKIP: no Emacs server with cljbang-org; start one and re-run."))))

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
          (kill-buffer! file)
          (is (= ":infra:archive:" (:filetags (org/keywords file))))
          (is (= "DONE" (:todo (first (org/select file '(level 1)))))))
        (testing "a keyword set to a vector writes one line each"
          (org/run! [[:set-keyword! file :target ["a" "b" "c"]] [:save! file]])
          (kill-buffer! file)
          (is (= ["a" "b" "c"] (:target (org/keywords file)))))
        (finally
          (kill-buffer! file)
          (fs/delete-tree dir))))))
