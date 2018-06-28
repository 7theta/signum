;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.subs)

(defonce ^:private handlers (atom {}))
(defonce ^:private query->signal (atom {}))
(defonce ^:private signal->query (atom {}))

(defn reg-sub
  [query-id inputs-fn computation-fn]
  (swap! handlers assoc query-id {:inputs-fn inputs-fn
                                  :computation-fn computation-fn}))

(defn subscribe
  [query-v]
  (locking query->signal
    (if-let [cached (get-in @query->signal [query-v :output])]
      (do (swap! query->signal update-in [query-v :count] inc)
          cached)
      (let [{:keys [inputs-fn computation-fn]} (get @handlers (first query-v))
            inputs (inputs-fn query-v)
            output (atom nil)
            watches (->> inputs
                         (map-indexed
                          (fn [i input]
                            (let [watch-key (keyword (str query-v i))]
                              (add-watch input watch-key
                                         (fn [_ _ old-state new-state]
                                           (when-not (= old-state new-state)
                                             (reset! output (computation-fn (map deref inputs))))))
                              [input watch-key])))
                         doall)]
        (reset! output (computation-fn (map deref inputs)))
        (swap! query->signal assoc query-v {:count 1 :watches watches :output output})
        (swap! signal->query assoc output query-v)
        output))))

(defn dispose
  [query-v]
  (locking query->signal
    (when-let [{:keys [count watches output]} (get @query->signal query-v)]
      (if (= 1 count)
        (do (doseq [[signal watch-key] watches]
              (remove-watch signal watch-key)
              (dispose (get @signal->query signal)))
            (swap! query->signal dissoc query-v)
            (swap! signal->query dissoc output))
        (swap! query->signal update-in [query-v :count] dec)))))
