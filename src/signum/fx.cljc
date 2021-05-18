(ns signum.fx
  (:require [signum.interceptors :as interceptors]))

(defonce effect-handlers (atom {}))

(declare run-effects)

(def interceptor
  (interceptors/->interceptor
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
