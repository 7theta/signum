;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.fx
  (:require [signum.interceptors :refer [->interceptor]]))

(defonce effect-handlers (atom {}))

(declare run-effects)

(def interceptor
  (->interceptor
   :id :signum.fx/interceptor
   :after (fn [context] (run-effects context) context)))

(defn reg-fx
  [id handler]
  (swap! effect-handlers assoc id {:handler handler})
  id)

;;; Implementation

(defn- run-effects
  [context]
  (doseq [[effect-id effect-args :as effect] (:effects context)]
    (if-let [{:keys [handler]} (get @effect-handlers effect-id)]
      (handler (:coeffects context) effect-args)
      (throw (ex-info (str ":signum.fx Unhandled effect" effect)
                      {:effect effect})))))
