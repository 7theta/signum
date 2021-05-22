;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.signal
  (:require [utilis.exception :refer [throw-if]]
            [utilis.fn :refer [fsafe]]
            [utilis.string :as ust]
            [clojure.tools.logging :as log])
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
    (swap! watches assoc watch-key
           {:watch-fn watch-fn
            :watch-key watch-key})
    this)
  (removeWatch
    [this watch-key]
    (swap! watches dissoc watch-key)
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
  [^Signal signal fun & args]
  (send-off (.backend signal)
            (fn [old-value]
              (let [new-value (apply fun old-value args)]
                (doseq [{:keys [watch-key watch-fn]} (vals @(.watches signal))]
                  (watch-fn watch-key signal old-value new-value))
                new-value)))
  signal)

(defmacro with-tracking
  [tracker-fn & body]
  `(binding [*tracker* ~tracker-fn]
     ~@body))


;;; Private

(defn- pr-signal
  [signal]
  (ust/format "#<signum/Signal@0x%x: %s>" (hash signal)
              (try (-> signal deref pr-str)
                   (catch Throwable e (.getMessage e)))))
