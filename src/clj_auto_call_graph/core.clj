(ns clj-auto-call-graph.core
  (:gen-class)
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]
            [loom.graph :refer [digraph]]
            [loom.io :refer [view]]))

(defn get-analysis [paths]
  (:analysis (clj-kondo/run! {:lint paths
                              :config {:output {:analysis true}}})))

(defn get-definitions [analysis]
  (reduce (fn [acc definition]
            (assoc acc
                   (str (:ns definition) "/" (:name definition))
                   {:references #{}
                    :referenced-by #{}}))
          {}
          (:var-definitions analysis)))

(defn get-def-usage [analysis ns-filter-set]
  (let [definitions (get-definitions analysis)
        filtered-usage (filter (fn [u] (not (contains? ns-filter-set
                                                       (str (:to u)))))
                               (:var-usages analysis))]
    (reduce (fn [acc usage]
              (let [ref-to   (str (:to usage) "/" (:name usage))
                    ref-from (str (:from usage) "/" (:from-var usage))
                    acc      (if (contains? acc ref-to)
                               (update-in acc
                                          [ref-to :referenced-by]
                                          (fn [s] (conj s ref-from)))
                               acc)
                    acc      (if (contains? acc ref-from)
                               (update-in acc
                                          [ref-from :references]
                                          (fn [s] (conj s ref-to)))
                               acc)]
                acc))
            definitions
            filtered-usage)))

(defn get-reference-edges [usage-map k]
  (let [references (:references (get usage-map k))]
    (map (fn [v] [k v]) references)))

(defn get-all-reference-edges [usage-map max-depth start-node]
  (loop [current-depth 0
         current-keys  #{start-node}
         acc           '()]
    (if (>= current-depth max-depth)
      acc
      (let [all-references (->> current-keys
                                (map (fn [k] (get-reference-edges usage-map k)))
                                (apply concat))
            next-keys (map second all-references)]
        (recur (inc current-depth)
               next-keys
               (concat acc all-references))))))

(defn get-referenced-by-edges [usage-map k]
  (let [referenced-by (:referenced-by (get usage-map k))]
    (map (fn [v] [v k]) referenced-by)))

(defn get-all-referenced-by-edges [usage-map max-depth start-node]
  (loop [current-depth 0
         current-keys  #{start-node}
         acc           '()]
    (if (>= current-depth max-depth)
      acc
      (let [all-references (->> current-keys
                                (map (fn [k] (get-referenced-by-edges usage-map k)))
                                (apply concat))
            next-keys (map first all-references)]
        (recur (inc current-depth)
               next-keys
               (concat acc all-references))))))

(defn create-digraph
  [paths start-node reference-depth referenced-by-depth ns-filters]
  (let [analysis            (get-analysis paths)
        def-usage           (get-def-usage analysis ns-filters)
        reference-edges     (get-all-reference-edges def-usage
                                                     reference-depth
                                                     start-node)
        referenced-by-edges (get-all-referenced-by-edges def-usage
                                                         referenced-by-depth
                                                         start-node)
        all-edges           (concat reference-edges referenced-by-edges)
        all-nodes           (distinct (map first all-edges))]
    (apply digraph (concat all-nodes all-edges))))

(def cli-options
  [[nil "--source-paths PATHS" "Comma-separated list of paths to source files for analysis"
    :default #{}
    :default-desc ""
    :parse-fn #(set (string/split % #","))
    :validate [#(seq %) "Source path(s) must be set"]]
   [nil "--starting-node NODE" "Namespace-qualified definition of starting node. E.g my.ns/my-fn"
    :default ""
    :validate [#(not (string/blank? %)) "Starting node must be set"]]
   [nil "--namespace-filters PATHS" "Comma-separated list of namespaces to ignore"
    :default #{"clojure.core"}
    :default-desc "clojure.core"
    :parse-fn #(set (string/split % #","))]
   [nil "--reference-depth DEPTH" "Depth of graphing for what the starting node references"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be a number greater than or equal to 0"]]
   [nil "--referenced-by-depth DEPTH" "Depth of graphing for what references the starting node"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be a number greater than or equal to 0"]]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Generates a call graph using graphviz centered on a given function/variable"
        ""
        "Usage: clj-auto-call-graph [options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (println errors)
    (cond
      (:help options)
      (exit 0 (usage summary))

      errors
      (exit 1 (error-msg errors))

      :else
      options)))

(defn -main [& args]
  (let [options (validate-args args)
        {:keys [source-paths
                starting-node
                reference-depth
                referenced-by-depth
                namespace-filters]} options
        graph  (create-digraph source-paths
                               starting-node
                               reference-depth
                               referenced-by-depth
                               namespace-filters)]
    (view graph)))

