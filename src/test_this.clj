(ns test-this
  (:use [clojure.core.incubator :only [seqable?]]
        [clojure.tools.namespace :only [find-namespaces-in-dir]]
        [clojure.string :only [join]]
        [clojure.java.io :only [file]]
        [lazytest.reload :only [reload]]
        [lazytest.tracker :only [tracker]])
  (:require [clojure.test :as cljtest]))

(defn get-test-namespaces [base-dir ns-filter]
  (filter ns-filter
          (find-namespaces-in-dir base-dir)))

(defn my-test-all-vars
  [test-filter ns]
  (let [once-fixture-fn (cljtest/join-fixtures (:clojure.test/once-fixtures (meta ns)))
        each-fixture-fn (cljtest/join-fixtures (:clojure.test/each-fixtures (meta ns)))]
    (once-fixture-fn
     (fn []
       (doseq [v (filter test-filter (vals (ns-interns ns)))]
         (when (:test (meta v))
           (each-fixture-fn (fn [] (cljtest/test-var v)))))))))

(defn do-run-tests [namespaces test-filter]
  (doseq [n namespaces]
    (require n))
  (with-redefs [cljtest/test-all-vars (partial my-test-all-vars test-filter)]
    (apply cljtest/run-tests namespaces)))

(defn run [{test-filter :test-filter ns-filter :namespace-filter
            before :before after :after dir :test-dir
            :or {test-filter (constantly true) ns-filter (constantly true)
                 dir "test" before (fn []) after identity}}]
  (before)
  (let [dir (file dir)
        nss (get-test-namespaces dir ns-filter)]
    (after (do-run-tests nss test-filter))))

(defn has-meta? [key] #(-> % meta (get key)))

(defn match-namespaces [& nss]
  (let [f (fn f [ns]
            (cond
              (symbol? ns) #(= ns (ns-name %))
              (= :all ns) (constantly true)
              (keyword? ns) (has-meta? ns)
              (isa? (class ns) java.util.regex.Pattern)
              #(re-matches ns (str (ns-name %)))
              (fn? ns) ns
              (seqable? ns) (fn [n] (every? #(% n) (map f ns)))
              :else (throw (IllegalArgumentException.
                             (str "Invalid namespace definition: " ns)))))]
    {:namespace-filter (fn [n] (some #(% n) (map f nss)))}))

(defn match-tests [& ts]
  (let [f (fn f [v]
            (cond
              (symbol? v) #(= (.sym %) v)
              (= :all v) (constantly true)
              (keyword? v) (has-meta? v)
              (fn? v) v
              (isa? (class v) java.util.regex.Pattern) #(re-matches v (str (.sym %)))
              (seqable? v) (fn [va] (every? #(% va) (map f v)))
              :else (throw (IllegalArgumentException. "Invalid tests definition"))))]
  {:test-filter (fn [n] (some #(% n) (map f ts)))}))

(defonce main-tracker (atom nil))

(defn init-tracker [watch-dirs]
  (swap! main-tracker #(or (and % (= (:dirs %) (set watch-dirs)) %)
                           {:dirs (set watch-dirs)
                            :tracker (tracker (map file watch-dirs) 0)})))

(defn changed-namespaces []
  ((:tracker @main-tracker)))

(defn reload-changed [& watch-dirs]
  (init-tracker watch-dirs)
  (fn []
    (when-let [n (changed-namespaces)]
      (println (format "Reloading: %s" (join ", " n)))
      (apply reload n))))

(defn test-this [& {:keys [namespaces tests reload-dirs reload? before after]
                    :or {reload-dirs ["src" "test"] before (fn []) after (fn [_])}
                    :as options}]
  (let [nss (if namespaces
              (apply match-namespaces (flatten [namespaces]))
              {})
        tests (if tests
                (apply match-tests (flatten [tests]))
                {})
        reload-dirs (flatten [reload-dirs])
        reload? (not (false? reload?))
        hooks {:before (if reload?
                         #(do
                            (before)
                            ((apply reload-changed reload-dirs)))
                         before)
               :after after}]
  (run (merge hooks nss tests))))
