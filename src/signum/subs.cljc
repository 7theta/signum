;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.subs
  (:require [signum.atom :as s]
            [signum.interceptors :refer [->interceptor] :as interceptors]
            [utilis.fn :refer [fsafe debounce]]
            [clojure.set :as set])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(def ^:dynamic *context* {})

(declare handlers signals reset-subscriptions! retain! release! signal-interceptor)

(defn reg-sub
  [query-id & args]
  (when-not (fn? (last args))
    (throw (ex-info "Computation fn must be provided" {:query-id query-id
                                                       :args args})))
  (let [interceptors (when (vector? (first args)) (first args))]
    (swap! handlers assoc query-id
           {:init-fn (let [init-fn (last (butlast args))]
                       (when (fn? init-fn) init-fn))
            :computation-fn (last args)
            :queue (vec (concat interceptors [signal-interceptor]))
            :stack []}))
  (reset-subscriptions! query-id)
  query-id)

(defn subscribe
  [[query-id & _ :as query-v]]
  (locking signals
    (if-let [handler-context (get @handlers query-id)]
      (-> (merge *context* handler-context)
          (assoc-in [:coeffects :query-v] query-v)
          interceptors/run
          (get-in [:effects :signal]))
      (throw (ex-info (str "Invalid query " (pr-str query-v)) {:query query-v})))))

(defn dispose
  [signal]
  (locking signals
    (release! signal)))

(defn track-signal
  [signal & {:keys [on-dispose]}]
  (when on-dispose
    (locking signals
      (swap! signals assoc signal {:count 0
                                   :dispose-fn (fn []
                                                 (swap! signals dissoc signal)
                                                 (on-dispose))})))
  signal)

(defn interceptors
  [query-id]
  (get-in @handlers [query-id :queue]))

;;; Private

(defonce ^:private handlers (atom {}))
(defonce ^:private signals (atom {}))
(defonce ^:private subscriptions (atom {}))
(def ^:private ^:dynamic *implicit-subs* nil)

(defn- retain!
  [signal]
  (swap! signals update-in [signal :count] (fsafe inc))
  signal)

(defn- release!
  [signal]
  (when-let [registration (get @signals signal)]
    (if (= 1 (:count registration))
      (when-let [dispose-fn (:dispose-fn registration)]
        (dispose-fn))
      (swap! signals update-in [signal :count] (fsafe dec))))
  nil)

(defn- create-subscription!
  [[query-id & _ :as query-v] output-signal]
  (let [{:keys [init-fn computation-fn]} (get @handlers query-id)
        init-watched (atom #{})
        compute-watched (atom #{})
        subscribe (fn [previously-watched watched reaction-fn a]
                    (when-not (or (contains? previously-watched a)
                                  (contains? @watched a))
                      (retain! a)
                      (let [reaction-fn (debounce reaction-fn 200)]
                        (add-watch a (str query-v) (fn [_ _ old-value new-value]
                                                     (when (not= old-value new-value)
                                                       (reaction-fn)))))
                      (swap! watched conj a)))
        dispose (fn [watched]
                  (doseq [watch watched]
                    (remove-watch watch (str query-v))
                    (release! watch)))
        init-context (atom ::not-initialized)
        run-reaction (fn run-reaction []
                       (try
                         (when-not (and init-fn (= ::not-initialized @init-context))
                           (let [watched (atom #{})]
                             (binding [*implicit-subs* true]
                               (s/with-tracking (partial subscribe @compute-watched watched run-reaction)
                                 (dispose (set/difference @compute-watched @watched))
                                 (reset! compute-watched @watched)
                                 (reset! output-signal (if init-fn
                                                         (computation-fn @init-context query-v)
                                                         (computation-fn query-v)))))))
                         (catch Exception e
                           (println ":signum.subs/subscribe" (pr-str query-v) "error")
                           (throw e))))]
    (swap! subscriptions assoc query-v {:signal output-signal :context *context*})
    (when init-fn
      (s/with-tracking (partial subscribe @init-watched init-watched run-reaction)
        (reset! init-context (init-fn query-v))))
    (run-reaction)
    (track-signal output-signal
                  :on-dispose (fn []
                                (dispose (concat @init-watched @compute-watched))
                                (swap! subscriptions dissoc query-v)))))

(defn- reset-subscriptions!
  [query-id]
  (locking signals
    (doseq [[query-v {:keys [signal context]}] (filter (fn [[query-v {:keys [signal context]}]]
                                                         (= query-id (first query-v))) @subscriptions)]
      (binding [*context* context]
        (let [{:keys [count dispose-fn]} (get @signals signal)]
          (when dispose-fn (dispose-fn))
          (create-subscription! query-v signal)
          (swap! signals assoc-in [signal :count] count))))))

(defn- signal
  [[query-id & _ :as query-v]]
  (cond-> (or (get-in @subscriptions [query-v :signal])
              (create-subscription! query-v (s/atom nil)))
    (not *implicit-subs*) retain!))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before #(assoc-in % [:effects :signal] (signal (get-in % [:coeffects :query-v])))))
