;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.events
  (:refer-clojure :exclude [namespace])
  (:require [signum.interceptors :as interceptors]
            [signum.fx :as fx]))

(defonce handlers (atom {}))

(declare handler-interceptor)

(defn reg-event
  ([id handler]
   (reg-event id nil handler))
  ([id interceptors handler]
   (swap! handlers assoc id {:queue (concat interceptors [(handler-interceptor id handler) #'fx/interceptor])
                             :stack []
                             :ns *ns*})
   id))

(defn dispatch
  ([query-vec] (dispatch nil query-vec))
  ([coeffects [id & _ :as query-vec]]
   (if-let [handler-context (get @handlers id)]
     (interceptors/run
       (assoc (update handler-context :coeffects merge coeffects)
              :event query-vec))
     (throw (ex-info ":signum.events Unhandled dispatch"
                     {:query-vec query-vec})))))

(defn event?
  [event-id]
  (contains? @handlers event-id))

(defn namespace
  [id]
  (:ns (get @handlers id)))

;;; Implementation

(defn- handler-interceptor
  [id handler-fn]
  (interceptors/->interceptor
   :id id
   :before (fn [{:keys [coeffects event] :as context}]
             (let [effects (handler-fn coeffects event)]
               (update context :effects merge effects)))))
