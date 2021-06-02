;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.events
  (:refer-clojure :exclude [namespace])
  (:require [signum.interceptors :as interceptors]
            [signum.fx :as fx]
            #?(:clj [metrics.gauges :as gauges])
            #?(:clj [metrics.counters :as counters])
            #?(:clj [metrics.timers :as timers])))

(defonce handlers (atom {}))

(declare handler-interceptor)

(defn reg-event
  ([id handler]
   (reg-event id nil handler))
  ([id interceptors handler]
   (swap! handlers assoc id {:queue (concat interceptors [(handler-interceptor id handler) #'fx/interceptor])
                             :stack []
                             :ns *ns*
                             #?@(:clj [:counter (counters/counter ["signum.events/handler-fn" "counter" (str id)])
                                       :timer (timers/timer ["signum.events/handler-fn" "timer" (str id)])])})
   id))

(defn dispatch
  ([query-v] (dispatch nil query-v))
  ([coeffects [id & _ :as query-v]]
   (if-let [{:keys [timer counter] :as handler-context} (get @handlers id)]
     (#?@(:clj [timers/time! timer
                (counters/inc! counter)]
          :cljs [identity])
      (interceptors/run
        (assoc (update handler-context :coeffects merge coeffects)
               :event query-v)))
     (throw (ex-info ":signum/events unhandled dispatch"
                     {:query-v query-v})))))

(defn namespace
  [id]
  (:ns (get @handlers id)))

(defn event?
  [event-id]
  (contains? @handlers event-id))

(defn events
  []
  (keys @handlers))


;;; Private

(defonce ^:private running (atom #{}))

#?(:clj (gauges/gauge-fn ["signum" "events" "registered"] #(count @handlers)))
#?(:clj (gauges/gauge-fn ["signum" "events" "running"] #(deref running)))

(defn- handler-interceptor
  [id handler-fn]
  (interceptors/->interceptor
   :id id
   :before (fn [{:keys [coeffects event] :as context}]
             (swap! running conj id)
             (let [effects (handler-fn coeffects event)]
               (swap! running disj id)
               (update context :effects merge effects)))))
