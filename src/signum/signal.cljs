;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.signal
  (:require [utilis.fn :refer [fsafe]]
            [utilis.js :as j]
            [utilis.string :as ust]))

(def ^:dynamic *tracker* nil)

(declare pr-signal)

(defprotocol IWatchWatchers
  (add-watcher-watch [signal key watch-fn])
  (remove-watcher-watch [signal key]))

(defprotocol IAlter
  (-alter! [signal fun args]))

(defn alter! [signal fun & args] (-alter! signal fun args))

(deftype Signal [backend watches meta-map]
  IEquiv
  (-equiv [this other] (identical? this other))

  IDeref
  (-deref [this]
    ((fsafe *tracker*) :deref this)
    (let [value (clojure.core/deref backend)]
      (cond-> value (instance? js/Error value) throw)))

  IAlter
  (-alter!
    [signal fun args]
    (swap! backend
           (fn [old-value]
             (try
               (apply fun old-value args)
               (catch js/Error e e))))
    signal)




  IWatchable
  (-add-watch
    [this watch-key watch-fn]
    (swap! watches assoc watch-key watch-fn)
    (add-watch backend watch-key
               (fn [_key _ref old-value new-value]
                 (watch-fn watch-key this old-value new-value)))
    this)
  (-remove-watch
    [this watch-key]
    (swap! watches dissoc watch-key)
    (remove-watch backend watch-key)
    this)

  IWithMeta
  (-with-meta [_ meta-map] (Signal. backend watches meta-map))

  IMeta
  (-meta [_] meta-map)

  IHash
  (-hash [this] (goog/getUid this))

  IPrintWithWriter
  (-pr-writer [this w opts] (write-all w (pr-signal this)))

  IWatchWatchers
  (add-watcher-watch [this watch-key watch-fn]
    (add-watch watches watch-key (fn [key _ref old-value new-value]
                                   (watch-fn key this old-value new-value))))
  (remove-watcher-watch [this watch-key]
    (remove-watch watches watch-key)))

(defn signal
  [state]
  (let [s (Signal. (atom state) (atom {}) nil)]
    ((fsafe *tracker*) :create s)
    s))


;;; Private

(defn- pr-signal
  [signal]
  (ust/format "#<signum/Signal@0x%x: %s>" (hash signal)
              (try (-> signal deref pr-str)
                   (catch js/Error e (.message e)))))
