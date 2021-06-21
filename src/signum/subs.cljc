;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.subs
  (:refer-clojure :exclude [namespace subs])
  #?(:cljs (:require-macros [signum.signal :refer [with-tracking] :as s]))
  (:require [signum.signal :refer [with-tracking] :as s]
            [signum.interceptors :refer [->interceptor] :as interceptors]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [compact]]
            [utilis.timer :as ut]
            #?(:cljs [utilis.js :as j])
            #?(:clj [metrics.gauges :as gauges])
            #?(:clj [metrics.counters :as counters])
            #?(:clj [metrics.timers :as timers])
            [clojure.set :as set])
  #?(:clj (:import [signum.signal Signal]
                   [clojure.lang ExceptionInfo])))

(defonce ^:dynamic *context* {})
(defonce ^:dynamic *current-sub-fn* ::computation-fn)
(def logging-lock #?(:clj (Object.) :cljs (js/Object.)))

(declare handlers reset-subscriptions! release! signal-interceptor)

(defn reg-sub
  [query-id & args]
  (when-not (fn? (last args))
    (throw (ex-info "computation-fn must be provided"
                    {:query-id query-id :args args})))
  (let [interceptors (when (vector? (first args)) (first args))
        fns (filter fn? args)
        [init-fn dispose-fn computation-fn] (case (count fns)
                                              3 fns
                                              2 [(first fns) nil (last fns)]
                                              1 [nil nil (last fns)])]
    (swap! handlers assoc query-id
           (compact
            {:init-fn init-fn
             :dispose-fn dispose-fn
             :computation-fn computation-fn
             :queue (vec (concat interceptors [signal-interceptor]))
             :stack []
             :ns *ns*})))
  (reset-subscriptions! query-id)
  query-id)

(defn subscribe
  [[query-id & _ :as query-v]]
  #_(when (= ::init-fn *current-sub-fn*)
      (throw (ex-info "subscribe is not supported within the init-fn of a sub"
                      {:query-v query-v})))
  #_(when (= ::dispose-fn *current-sub-fn*)
      (throw (ex-info "subscribe is not supported within the dispose-fn of a sub"
                      {:query-v query-v})))
  (if-let [handler-context (get @handlers query-id)]
    (-> (merge *context* handler-context)
        (assoc-in [:coeffects :query-v] query-v)
        interceptors/run
        (get-in [:effects :signal]))
    (throw (ex-info "invalid query" {:query query-v}))))

(defn interceptors
  [query-id]
  (get-in @handlers [query-id :queue]))

(defn namespace
  [id]
  (:ns (get @handlers id)))

(declare signals)

(defn sub?
  [id]
  (contains? @handlers id))

(defn subs
  []
  (keys @signals))


;;; Private

(defonce ^:private handlers (atom {}))      ; key: query-id
(defonce ^:private signals (atom {}))       ; key: query-v
(defonce ^:private subscriptions (atom {})) ; key: output-signal
(defonce ^:private running (atom #{}))

#?(:clj (gauges/gauge-fn ["signum" "subs" "registered"] #(count @handlers)))
#?(:clj (gauges/gauge-fn ["signum" "subs" "subscribed"] #(count @signals)))
#?(:clj (gauges/gauge-fn ["signum" "subs" "active"] #(count @subscriptions)))
#?(:clj (gauges/gauge-fn ["signum" "subs" "running"] #(deref running)))

(defn- create-subscription!
  [query-v output-signal]
  (let [#?@(:clj [compute-fn-counter (counters/counter ["signum.subs/compute-fn" "counter" (str query-v)])
                  compute-fn-timer (timers/timer ["signum.subs/compute-fn" "timer" (str query-v)])])
        handlers (get @handlers (first query-v))
        {:keys [init-fn computation-fn]} handlers
        init-context (when init-fn (binding [*current-sub-fn* ::init-fn] (init-fn query-v)))
        input-signals (atom #{})
        run-reaction (fn run-reaction []
                       (s/alter!
                        output-signal
                        (fn [_]
                          (#?@(:clj [timers/time! compute-fn-timer
                                     (swap! running conj query-v)]
                               :cljs [identity])
                           (try
                             (binding [*current-sub-fn* ::compute-fn]
                               (let [derefed (atom #{})]
                                 (with-tracking
                                   (fn [reason s]
                                     (when (= :deref reason)
                                       (when-not (or (get @input-signals s)
                                                     (get @derefed s))
                                         (add-watch s query-v
                                                    (fn [_ _ old-value new-value]
                                                      (run-reaction))))
                                       (swap! derefed conj s)))
                                   (let [value (if init-context
                                                 (computation-fn init-context query-v)
                                                 (computation-fn query-v))]
                                     (doseq [w (set/difference @input-signals @derefed)]
                                       (remove-watch w query-v))
                                     (reset! input-signals @derefed)
                                     #?@(:clj [(counters/inc! compute-fn-counter)
                                               (swap! running disj query-v)])
                                     value))))
                             (catch #?(:clj Exception :cljs js/Error) e
                               (locking logging-lock
                                 #?(:clj (println ":signum.subs/subscribe" (pr-str query-v) "error\n" e)
                                    :cljs (js/console.error (str ":signum.subs/subscribe " (pr-str query-v) " error\n") e)))))))))]
    (run-reaction)
    #?(:clj (swap! signals update query-v merge {:counter compute-fn-counter
                                                 :timer compute-fn-timer}))
    (swap! subscriptions assoc output-signal (compact
                                              {:query-v query-v
                                               :context *context*
                                               :handlers handlers
                                               :init-context init-context
                                               :input-signals input-signals}))))

(defn- dispose-subscription!
  [query-v output-signal]
  (when-let [subscription (get @subscriptions output-signal)]
    (let [{:keys [init-context handlers input-signals]} subscription]
      (doseq [w @input-signals] (remove-watch w query-v))
      (when-let [dispose-fn (:dispose-fn handlers)]
        (binding [*current-sub-fn* ::dispose-fn]
          (dispose-fn init-context query-v)))
      (swap! subscriptions dissoc output-signal))))

(defn- reset-subscriptions!
  [query-id]
  (locking signals
    (doseq [[query-v output-signal] (filter (fn [[query-v _]] (= query-id (first query-v))) @signals)]
      (let [output-signal (:signal output-signal)
            context (get-in @subscriptions [output-signal :context])]
        (dispose-subscription! query-v output-signal)
        (binding [*context* context]
          (create-subscription! query-v output-signal))))))

(defn- handle-watches
  [query-v output-signal _ _ _old-watchers watchers]
  (locking signals
    (if (zero? (count watchers))
      (dispose-subscription! query-v output-signal)
      (when-not (get @subscriptions output-signal)
        (create-subscription! query-v output-signal)))))

(defn- signal
  [query-v]
  (locking signals
    (or (get-in @signals [query-v :signal])
        (let [output-signal (s/signal nil)]
          (s/add-watcher-watch output-signal
                               query-v
                               (partial handle-watches query-v output-signal))
          (swap! signals assoc-in [query-v :signal] output-signal)
          output-signal))))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before #(assoc-in % [:effects :signal] (signal (get-in % [:coeffects :query-v])))))
