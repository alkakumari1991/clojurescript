;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.repl.node
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cljs.util :as util]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.closure :as closure]
            [clojure.data.json :as json])
  (:import java.net.Socket
           java.lang.StringBuilder
           [java.io File BufferedReader BufferedWriter]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn socket [host port]
  (let [socket (Socket. host port)
        in     (io/reader socket)
        out    (io/writer socket)]
    {:socket socket :in in :out out}))

(defn close-socket [s]
  (.close (:in s))
  (.close (:out s))
  (.close (:socket s)))

(defn write [^BufferedWriter out ^String js]
  (.write out js)
  (.write out (int 0)) ;; terminator
  (.flush out))

(defn read-response [^BufferedReader in]
  (let [sb (StringBuilder.)]
    (loop [sb sb c (.read in)]
      (cond
       (= c 1) (let [ret (str sb)]
                 (print ret)
                 (recur (StringBuilder.) (.read in)))
       (= c 0) (str sb)
       :else (do
               (.append sb (char c))
               (recur sb (.read in)))))))

(defn node-eval
  "Evaluate a JavaScript string in the Node REPL process."
  [repl-env js]
  (let [{:keys [in out]} @(:socket repl-env)]
    ;; escape backslash for Node.js under Windows
    (write out js)
    (let [result (json/read-str
                   (read-response in) :key-fn keyword)]
      (condp = (:status result)
        "success"
        {:status :success
         :value (:value result)}

        "exception"
        {:status :exception
         :value (:value result)}))))

(defn load-javascript
  "Load a Closure JavaScript file into the Node REPL process."
  [repl-env provides url]
  (node-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn seq->js-array [v]
  (str "[" (apply str (interpose ", " (map pr-str v))) "]"))

(defn platform-path [v]
  (str "path.join.apply(null, " (seq->js-array v) ")"))

(defn setup
  ([repl-env] (setup repl-env nil))
  ([repl-env opts]
    (let [output-dir (io/file (:output-dir opts))
          _    (.mkdirs output-dir)
          of   (io/file output-dir "node_repl.js")
          _   (spit of
                (string/replace (slurp (io/resource "cljs/repl/node_repl.js"))
                  "var PORT = 5001;"
                  (str "var PORT = " (:port repl-env) ";")))
          bldr (ProcessBuilder. (into-array [(get opts :node-command "node")]))
          _    (-> bldr
                 (.redirectInput of)
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT))
          proc (.start bldr)
          env  (ana/empty-env)
          core (io/resource "cljs/core.cljs")
          ;; represent paths as vectors so we can emit JS arrays, this is to
          ;; paper over Windows issues with minimum hassle - David
          path (.getPath (.getCanonicalFile output-dir))
          [fc & cs] (rest (util/path-seq path)) ;; remove leading empty string
          root (.substring path 0 (+ (.indexOf path fc) (count fc)))
          root-path (vec (cons root cs))
          rewrite-path (conj root-path "goog")]
      ;; TODO: temporary hack, should wait till we can read the start string
      ;; from the process - David
      (Thread/sleep 300)
      (reset! (:socket repl-env)
        (socket (:host repl-env) (:port repl-env)))
      (reset! (:proc repl-env) proc)
      ;; compile cljs.core & its dependencies, goog/base.js must be available
      ;; for bootstrap to load, use new closure/compile as it can handle
      ;; resources in JARs
      (let [core-js (closure/compile core
                      (assoc opts
                        :output-file
                        (closure/src-file->target-file core)))
            deps    (closure/add-dependencies opts core-js)]
        ;; output unoptimized code and the deps file
        ;; for all compiled namespaces
        (apply closure/output-unoptimized
          (assoc opts
            :output-to (.getPath (io/file output-dir "node_repl_deps.js")))
          deps))
      ;; bootstrap, replace __dirname as __dirname won't be set
      ;; properly due to how we are running it - David
      (node-eval repl-env
        (-> (slurp (io/resource "cljs/bootstrap_node.js"))
          (string/replace "path.resolve(__dirname, '..', 'base.js')"
            (platform-path (conj rewrite-path "bootstrap" ".." "base.js")))
          (string/replace
            "path.join(\".\", \"..\", src)"
            (str "path.join(" (platform-path rewrite-path) ", src)"))
          (string/replace
            "var CLJS_ROOT = \".\";"
            (str "var CLJS_ROOT = " (platform-path root-path) ";"))))
      ;; load the deps file so we can goog.require cljs.core etc.
      (node-eval repl-env
        (str "require("
             (platform-path (conj root-path "node_repl_deps.js"))
             ")"))
      ;; monkey-patch isProvided_ to avoid useless warnings - David
      (node-eval repl-env
        (str "goog.isProvided_ = function(x) { return false; };"))
      ;; monkey-patch goog.require, skip all the loaded checks
      (repl/evaluate-form repl-env env "<cljs repl>"
        '(set! (.-require js/goog)
           (fn [name]
             (js/CLOSURE_IMPORT_SCRIPT
               (aget (.. js/goog -dependencies_ -nameToPath) name)))))
      ;; load cljs.core, setup printing
      (repl/evaluate-form repl-env env "<cljs repl>"
        '(do
           (.require js/goog "cljs.core")
           (enable-console-print!)))
      ;; redef goog.require to track loaded libs
      (repl/evaluate-form repl-env env "<cljs repl>"
        '(do
           (set! *loaded-libs* #{"cljs.core"})
           (set! (.-require js/goog)
             (fn [name reload]
               (when (or (not (contains? *loaded-libs* name)) reload)
                 (set! *loaded-libs* (conj (or *loaded-libs* #{}) name))
                 (js/CLOSURE_IMPORT_SCRIPT
                   (aget (.. js/goog -dependencies_ -nameToPath) name))))))))))

(defrecord NodeEnv [host port socket proc]
  repl/IReplEnvOptions
  (-repl-options [this]
    {:target :nodejs})
  repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts))
  (-evaluate [this filename line js]
    (node-eval this js))
  (-load [this provides url]
    (load-javascript this provides url))
  (-tear-down [this]
    (.destroy ^Process @proc)
    (close-socket @socket)))

(defn repl-env* [options]
  (let [{:keys [host port]}
        (merge
          {:host "localhost"
           :port (+ 49000 (rand-int 10000))}
          options)]
    (NodeEnv. host port (atom nil) (atom nil))))

(defn repl-env
  [& {:as options}]
  (repl-env* options))

