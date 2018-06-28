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
  (:require [signum.interceptors :refer [->interceptor] :as interceptors]))

(declare handlers query->signal signal->query
         signal-interceptor run-interceptors)

(defn reg-sub
  ([query-id inputs-fn computation-fn]
   (reg-sub query-id nil inputs-fn computation-fn))
  ([query-id interceptors inputs-fn computation-fn]
   (swap! handlers assoc query-id
          {:sub {:inputs-fn inputs-fn
                 :computation-fn computation-fn}
           :queue (-> [] (concat interceptors) (concat [signal-interceptor]))
           :stack []})))

(defn subscribe
  [[query-id & _ :as query-v] & [context]]
  (if-let [handler-context (get @handlers query-id)]
    (-> (merge context handler-context)
        (assoc ::query-v query-v)
        interceptors/run
        (get-in [:effects ::signal]))
    nil))

(defn dispose
  [signal]
  (locking query->signal
    (when-let [query-v (get @signal->query signal)]
      (when-let [{:keys [count watches output]} (get @query->signal query-v)]
        (if (= 1 count)
          (do (doseq [[signal watch-key] watches]
                (remove-watch signal watch-key)
                (dispose (get @signal->query signal)))
              (swap! query->signal dissoc query-v)
              (swap! signal->query dissoc output))
          (swap! query->signal update-in [query-v :count] dec))))))

;;; Private

(defonce ^:private handlers (atom {}))
(defonce ^:private query->signal (atom {}))
(defonce ^:private signal->query (atom {}))

(defn- signal
  [[query-id & _ :as query-v]]
  (locking query->signal
    (if-let [cached (get-in @query->signal [query-v :output])]
      (do (swap! query->signal update-in [query-v :count] inc)
          cached)
      (let [{:keys [inputs-fn computation-fn]} (get-in @handlers [query-id :sub])
            inputs (inputs-fn query-v)
            output (atom nil)
            reset-output! #(reset! output (computation-fn (map deref inputs) query-v))
            watches (->> inputs
                         (map-indexed
                          (fn [i input]
                            (let [watch-key (keyword (str query-v i))]
                              (add-watch input watch-key
                                         (fn [_ _ old-state new-state]
                                           (when-not (= old-state new-state)
                                             (reset-output!))))
                              [input watch-key])))
                         doall)]
        (reset-output!)
        (swap! query->signal assoc query-v {:count 1 :watches watches :output output})
        (swap! signal->query assoc output query-v)
        output))))

(def ^:private signal-interceptor
  (->interceptor
   :id :signum.subs/signal-interceptor
   :before
   (fn [context]
     (let [query-v (get context ::query-v)]
       (assoc-in context [:effects ::signal] (signal query-v))))))
