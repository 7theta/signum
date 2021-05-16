(ns signum.events
  (:require [signum.interceptors :as interceptors]
            [signum.fx :as fx]))

(defonce event-handlers (atom {}))

(declare handler-interceptor)

(defn reg-event
  ([id handler]
   (reg-event id nil handler))
  ([id interceptors handler]
   (swap! event-handlers assoc id {:queue (concat interceptors [(handler-interceptor id handler) #'fx/interceptor])
                                   :stack []})
   id))

(defn dispatch
  ([query-vec] (dispatch nil query-vec))
  ([coeffects [id & _ :as query-vec]]
   (if-let [handler-context (get @event-handlers id)]
     (interceptors/run
       (assoc (update handler-context :coeffects merge coeffects)
              :event query-vec))
     (throw (ex-info ":signum.events Unhandled dispatch"
                     {:query-vec query-vec})))))

(defn event?
  [event-id]
  (contains? @event-handlers event-id))

;;; Implementation

(defn- handler-interceptor
  [id handler-fn]
  (interceptors/->interceptor
   :id id
   :before (fn [{:keys [coeffects event] :as context}]
             (let [effects (handler-fn coeffects event)]
               (update context :effects merge effects)))))
