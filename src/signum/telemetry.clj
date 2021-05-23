;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.telemetry
  (:require [signum.subs :as subs]
            [metrics.core :as metrics]
            [metrics.gauges :as gauges]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [metrics.timers :as timers]))

(defn metrics
  ([]
   (reduce (fn [query-v->metrics query-v]
             (assoc query-v->metrics query-v (metrics query-v)))
           {:subs {:registered (gauges/value (gauges/gauge ["signum" "subs" "registered"]))
                   :subscribed (gauges/value (gauges/gauge ["signum" "subs" "subscribed"]))
                   :active (gauges/value (gauges/gauge ["signum" "subs" "active"]))}}
           (subs/subs)))
  ([query-v]
   {:count (counters/value (counters/counter ["signum.subs/compute-fn" "counter" (str query-v)]))
    :timings (timers/percentiles (timers/timer ["signum.subs/compute-fn" "timer" (str query-v)]))}))
