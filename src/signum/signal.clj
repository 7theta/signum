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
                                        go-loop <! >!! alts!]]
            [utilis.exception :refer [throw-if]]
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
    (let [value-ch (chan (sliding-buffer 1))]
      (swap! watches assoc watch-key
             {:watch-fn watch-fn
              :watch-key watch-key
              ;; :value-ch value-ch
              #_:value-loop #_(go-loop [old-value @backend]
                                (when-let [new-value (<! value-ch)]
                                  (try
                                    (watch-fn watch-key this old-value (first new-value))
                                    (catch Exception e
                                      (log/info "exception in add-watch" watch-key e)
                                      (remove-watch this watch-key)))
                                  (recur (first new-value))))}))
    this)
  (removeWatch
    [this watch-key]
    ((fsafe close!) (get-in @watches [watch-key :value-ch]))
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
  (send (.backend signal)
        (fn [v]
          (let [new-value (apply fun v args)]
            (doseq [{:keys [value-ch watch-key watch-fn]} (vals @(.watches signal))]
              (watch-fn watch-key signal v new-value)
              #_(>!! value-ch [new-value]))
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
