;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.signal
  (:require [clojure.core.async :refer [chan sliding-buffer close!
                                        go-loop >!! alts!]]
            [utilis.exception :refer [throw-if]]
            [utilis.fn :refer [fsafe]]
            [utilis.string :as ust])
  (:import [clojure.lang IRef IDeref IObj IMeta]))

(def ^:dynamic *tracker* nil)

(declare pr-signal)

(deftype Signal [backend watches meta-map]

  IDeref
  (deref [this]
    ((fsafe *tracker*) :deref this)
    (throw-if (or (agent-error backend) (clojure.core/deref backend))))

  IRef
  (addWatch [this watch-key watch-fn]
    (when (empty? @watches)
      (add-watch backend :signum/signal
                 (fn [_key _ref _old-value new-value]
                   (doseq [{:keys [value-ch]} (vals @watches)]
                     (>!! value-ch [new-value])))))
    (let [stop-ch (chan)
          value-ch (chan (sliding-buffer 1))]
      (swap! watches assoc watch-key
             {:stop-ch stop-ch
              :value-ch value-ch
              :value-loop (go-loop [old-value @backend]
                            (let [[[value] channel] (alts! [stop-ch value-ch])]
                              (when (not= channel stop-ch)
                                (watch-fn watch-key this old-value value)
                                (recur value))))}))
    this)
  (removeWatch
    [this watch-key]
    (close! (get-in @watches [watch-key :stop-ch]))
    (swap! watches dissoc watch-key)
    (when (empty? @watches)
      (remove-watch backend :signum/signal))
    this)

  IObj
  (withMeta [_ meta-map] (Signal. backend watches meta-map))

  IMeta
  (meta [_] meta-map))

(defmethod print-method Signal [^Signal s w]
  (.write ^java.io.Writer w ^String (pr-signal s)))

(defn signal
  [state]
  (let [s (Signal. (agent state) (atom {}) nil)]
    ((fsafe *tracker*) :create s)
    s))

(defn alter!
  [signal fun & args]
  (apply send-off (.backend ^Signal signal) fun args)
  signal)

(defmacro with-tracking
  [tracker-fn & body]
  `(binding [*tracker* ~tracker-fn]
     ~@body))

(defn add-watcher-watch
  [signal watch-key watch-fn]
  (add-watch (.watches ^Signal signal) watch-key
             (fn [_key _ref old-value new-value]
               (watch-fn watch-key signal old-value new-value))))

(defn remove-watcher-watch
  [signal watch-key]
  (remove-watch (.watches ^Signal signal) watch-key))

(defn watches
  "Returns list of keys corresponding to watchers of the signal."
  [signal]
  @(.watches ^Signal signal))

;;; Private

(defn- pr-signal
  [signal]
  (ust/format "#<signum/Signal@0x%x: %s>" (hash signal)
              (try (-> signal deref pr-str)
                   (catch Throwable e
                     (println "ERROR PRINT" e)
                     e))))
