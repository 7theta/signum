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
  (:require [signum.interceptors :refer [->interceptor] :as interceptors]
            [utilis.fn :refer [fsafe]])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(def ^:dynamic *context* {})

(declare handlers signals reset-subscriptions! dispose-subscription! signal-interceptor)

(defn reg-sub
  ([query-id inputs-fn computation-fn]
   (reg-sub query-id nil inputs-fn computation-fn))
  ([query-id interceptors inputs-fn computation-fn]
   (swap! handlers assoc query-id
          {:sub {:inputs-fn inputs-fn
                 :computation-fn computation-fn}
           :queue (vec (concat interceptors [signal-interceptor]))
           :stack []})
   (reset-subscriptions! query-id)
   query-id))

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
    (when-let [registration (get @signals signal)]
      (if (= 1 (:count registration))
        (dispose-subscription! signal)
        (swap! signals update-in [signal :count] dec)))))

(defn make-signal
  [f & {:keys [on-dispose]}]
  (locking signals
    (let [signal (f)]
      (when on-dispose
        (swap! signals assoc signal {:count 1
                                     :dispose-fn (fn []
                                                   (swap! signals dissoc signal)
                                                   (on-dispose))}))
      signal)))

(defn interceptors
  [query-id]
  (get-in @handlers [query-id :queue]))

;;; Private

(defonce ^:private handlers (atom {}))
(defonce ^:private signals (atom {}))
(defonce ^:private subscriptions (atom {}))

(defn- resolve-inputs
  [[query-id & _ :as query-v]]
  (let [inputs-fn (get-in @handlers [query-id :sub :inputs-fn])]
    (try
      (inputs-fn query-v)
      (catch #?(:clj ExceptionInfo :cljs js/Error) e
        (throw (ex-info (str ":signum.subs/error Invalid input in " (pr-str query-v))
                        {:query query-v
                         :input-query (:query (ex-data e))}))))))

(defn- create-subscription!
  [[query-id & _ :as query-v] output-signal]
  (let [inputs (resolve-inputs query-v)
        reset-signal! (fn []
                        (let [computation-fn (get-in @handlers [query-id :sub :computation-fn])
                              inputs (if (seqable? inputs) (map deref inputs) @inputs)]
                          #?(:clj (if (instance? clojure.lang.Agent output-signal)
                                    (send output-signal (fn [_] (computation-fn inputs query-v)))
                                    (reset! output-signal (computation-fn inputs query-v)))
                             :cljs (reset! output-signal (computation-fn inputs query-v)))))
        watches (doall
                 (map-indexed
                  (fn [i input]
                    (let [watch-key (keyword (str query-v "-" i))]
                      (add-watch input watch-key
                                 (fn [_ _ old-state new-state]
                                   (when-not (= old-state new-state)
                                     (reset-signal!))))
                      [input watch-key])) (if (seqable? inputs) inputs [inputs])))]
    (reset-signal!)
    (swap! subscriptions assoc query-v {:signal output-signal
                                        :context *context*})
    (make-signal (constantly output-signal)
                 :on-dispose (fn []
                               (swap! subscriptions dissoc query-v)
                               (doseq [[input-signal watch-key] watches]
                                 (remove-watch input-signal watch-key)
                                 (dispose input-signal))))))

(defn- dispose-subscription!
  [signal]
  (when-let [dispose-fn (get-in @signals [signal :dispose-fn])]
    (dispose-fn)))

(defn- reset-subscriptions!
  [query-id]
  (locking signals
    (doseq [[query-v {:keys [signal context]}] (filter (fn [[query-v {:keys [signal context]}]]
                                                         (= query-id (first query-v))) @subscriptions)]
      (binding [*context* context]
        (let [count (:count (get @signals signal))]
          (dispose-subscription! signal)
          (create-subscription! query-v signal)
          (swap! signals assoc-in [signal :count] count))))))

(defn- signal
  [[query-id & _ :as query-v]]
  (if-let [signal (get-in @subscriptions [query-v :signal])]
    (try
      ;; Need to ensure that the inputs can also be resolved in `*context*`
      (let [inputs (resolve-inputs query-v)]
        (if (seqable? inputs) (doall (map deref inputs)) @inputs))
      (swap! signals update-in [signal :count] inc)
      signal
      (catch #?(:clj ExceptionInfo :cljs js/Error) _
        (throw (ex-info (str ":signum.subs/error Invalid inputs in" (pr-str query-v)) {:query query-v}))))
    (create-subscription! query-v
                          #?(:clj (agent nil :error-mode :continue
                                         :error-handler (fn [a e] (println "\n" :signum.subs/error (pr-str query-v) "\n" e)))
                             :cljs (atom nil)))))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before #(assoc-in % [:effects :signal] (signal (get-in % [:coeffects :query-v])))))
