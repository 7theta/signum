;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.subs
  (:require [signum.signal :as s]
            [signum.interceptors :refer [->interceptor] :as interceptors]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [compact]]
            [utilis.timer :as ut]
            [clojure.set :as set])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(defonce ^:dynamic *context* {})
(defonce ^:dynamic *current-sub-fn* nil)

(declare handlers reset-subscriptions! release! signal-interceptor)

(defn reg-sub
  [query-id & args]
  (when-not (fn? (last args))
    (throw (ex-info "Computation fn must be provided" {:query-id query-id :args args})))
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
             :stack []})))
  (reset-subscriptions! query-id)
  query-id)

(defn subscribe
  [[query-id & _ :as query-v]]
  (when (= ::init-fn *current-sub-fn*)
    (throw (ex-info "subscribe is not supported within the init-fn of a sub"
                    {:query-v query-v})))
  (when (= ::dispose-fn *current-sub-fn*)
    (throw (ex-info "subscribe is not supported within the dispose-fn of a sub"
                    {:query-v query-v})))
  (if-let [handler-context (get @handlers query-id)]
    (-> (merge *context* handler-context)
        (assoc-in [:coeffects :query-v] query-v)
        interceptors/run
        (get-in [:effects :signal]))
    (throw (ex-info (str "Invalid query " (pr-str query-v)) {:query query-v}))))

(defn interceptors
  [query-id]
  (get-in @handlers [query-id :queue]))

;;; Private

(defonce ^:private handlers (atom {}))      ; key: query-id
(defonce ^:private signals (atom {}))       ; key: query-v
(defonce ^:private subscriptions (atom {})) ; key: output-signal

(defn- create-subscription!
  [output-signal]
  (let [query-v (:query-v (meta output-signal))
        handlers (get @handlers (first query-v))
        {:keys [init-fn computation-fn]} handlers
        init-context (when init-fn (binding [*current-sub-fn* ::init-fn] (init-fn query-v)))
        input-signals (atom #{})
        run-reaction (fn run-reaction []
                       (binding [*current-sub-fn* ::compute-fn]
                         (try
                           (let [derefed (atom #{})]
                             (s/with-tracking (fn [reason s]
                                                (when (= :deref reason)
                                                  (when-not (or (get @input-signals s)
                                                                (get @derefed s))
                                                    (add-watch s (str query-v)
                                                               (fn [_ _ old-value new-value]
                                                                 (when (not= old-value new-value)
                                                                   (run-reaction)))))
                                                  (swap! derefed conj s)))
                               (s/alter! output-signal
                                         (constantly
                                          (if init-context
                                            (computation-fn init-context query-v)
                                            (computation-fn query-v)))))
                             (doseq [w (set/difference @input-signals @derefed)]
                               (remove-watch w (str query-v)))
                             (reset! input-signals @derefed))
                           (catch Exception e
                             (println ":signum.subs/subscribe" (pr-str query-v) "error\n" e)))))]
    (run-reaction)
    (swap! subscriptions assoc output-signal (compact
                                              {:query-v query-v
                                               :context *context*
                                               :handlers handlers
                                               :init-context init-context
                                               :input-signals input-signals}))))

(defn- dispose-subscription!
  [output-signal]
  (when-let [subscription (get @subscriptions output-signal)]
    (let [{:keys [query-v init-context handlers input-signals]} subscription]
      (doseq [w @input-signals] (remove-watch w (str query-v)))
      (when-let [dispose-fn (:dispose-fn handlers)]
        (binding [*current-sub-fn* ::dispose-fn]
          (dispose-fn init-context query-v)))
      (swap! subscriptions dissoc output-signal))))

(defn- reset-subscriptions!
  [query-id]
  (doseq [[query-v output-signal] (filter (fn [[query-v _]] (= query-id (first query-v))) @signals)]
    (let [context (get-in @subscriptions [output-signal :context])]
      (dispose-subscription! output-signal)
      (binding [*context* context]
        (create-subscription! output-signal)))))

(defn- handle-watchers
  [_ output-signal _ watchers]
  (if (zero? (count watchers))
    (do
      (swap! signals dissoc (:query-v (meta output-signal)))
      (dispose-subscription! output-signal))
    (locking subscriptions
      (when-not (get @subscriptions output-signal)
        (create-subscription! output-signal)))))

(defn- signal
  [query-v]
  (locking signals
    (or (get @signals query-v)
        (let [output-signal (with-meta (s/signal nil) {:query-v query-v})]
          (s/add-watcher-watch output-signal (str query-v) handle-watchers)
          (swap! signals assoc query-v output-signal)
          output-signal))))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before #(assoc-in % [:effects :signal] (signal (get-in % [:coeffects :query-v])))))
