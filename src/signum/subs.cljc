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
  (:require [signum.interceptors :refer [->interceptor] :as interceptors])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(declare handlers signals reset-subscriptions! dispose-subscription! signal-interceptor)

(defn reg-sub
  ([query-id inputs-fn computation-fn]
   (reg-sub query-id nil inputs-fn computation-fn))
  ([query-id interceptors inputs-fn computation-fn]
   (swap! handlers assoc query-id
          {:sub {:inputs-fn inputs-fn
                 :computation-fn computation-fn}
           :queue (-> [] (concat interceptors) (concat [signal-interceptor]))
           :stack []})
   (reset-subscriptions! query-id)))

(defn subscribe
  [[query-id & _ :as query-v] & {:keys [context]}]
  (locking signals
    (if-let [handler-context (get @handlers query-id)]
      (-> (merge context handler-context)
          (assoc ::query-v query-v)
          interceptors/run
          (get-in [:effects ::signal]))
      (throw (ex-info (str "Invalid query " (pr-str query-v)) {:query query-v})))))

(defn dispose
  [signal]
  (locking signals
    (when-let [registration (get @signals signal)]
      (if (= 1 (:count registration))
        (dispose-subscription! signal)
        (swap! signals update-in [signal :count] dec)))))

(defn make-signal
  [f & {:keys [on-dispose]}]
  (let [signal (f)]
    (swap! signals assoc signal {:count 1
                                 :dispose-fn (fn []
                                               (swap! signals dissoc signal)
                                               (when on-dispose (on-dispose)))})
    signal))

;;; Private

(defonce ^:private handlers (atom {}))
(defonce ^:private signals (atom {}))
(defonce ^:private subscriptions (atom {}))

(defn- create-subscription!
  [[query-id & _ :as query-v] output-signal]
  (let [{:keys [inputs-fn computation-fn]} (get-in @handlers [query-id :sub])
        inputs (try
                 (inputs-fn query-v)
                 (catch #?(:clj ExceptionInfo :cljs js/Error) e
                   (throw (ex-info (str "Invalid input in " (pr-str query-v))
                                   {:query query-v
                                    :input-query (:query (ex-data e))}))))
        reset-signal! #(reset! output-signal (computation-fn (if (seqable? inputs) (map deref inputs) @inputs) query-v))
        watches (doall (map-indexed
                        (fn [i input]
                          (let [watch-key (keyword (str query-v "-" i))]
                            (add-watch input watch-key
                                       (fn [_ _ old-state new-state]
                                         (when-not (= old-state new-state)
                                           (reset-signal!))))
                            [input watch-key])) (if (seqable? inputs) inputs [inputs])))]
    (reset-signal!)
    (swap! subscriptions assoc query-v output-signal)
    (make-signal
     (fn [] output-signal)
     :on-dispose (fn []
                   (swap! subscriptions dissoc query-v)
                   (doseq [[input-signal watch-key] watches]
                     (remove-watch input-signal watch-key)
                     (dispose input-signal))))))

(defn- dispose-subscription!
  [signal]
  (when-let [dispose-fn (get-in @signals [signal :dispose-fn])] (dispose-fn)))

(defn- reset-subscriptions!
  [query-id]
  (doseq [[query-v signal] (filter (fn [[query-v signal]]
                                     (= query-id (first query-v))) @subscriptions)]
    (let [count (:count (get @signals signal))]
      (dispose-subscription! signal)
      (create-subscription! query-v signal)
      (swap! signals assoc-in [signal :count] count))))

(defn- signal
  [[query-id & _ :as query-v]]
  (if-let [signal (get @subscriptions query-v)]
    (do (swap! signals update-in [signal :count] inc)
        signal)
    (create-subscription! query-v (atom {}))))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before #(assoc-in % [:effects ::signal] (signal (get % ::query-v)))))
