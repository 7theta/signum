;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns signum.atom
  (:refer-clojure :exclude [atom Atom])
  (:require [utilis.fn :refer [fsafe]]
            #?@(:cljs
                [[goog.string :as gstring]
                 [goog.string.format]]))
  #?(:clj (:import [clojure.lang IAtom IAtom2 IRef IDeref ARef IObj IMeta])))

(def ^:dynamic *tracker* nil)

(declare pr-atom)

(deftype Atom [backend meta-map]

  IAtom
  #?@(:clj
      [(swap [_ f] (swap! backend f))
       (swap [_ f arg] (swap! backend f arg))
       (swap [_ f arg1 arg2] (swap! backend f arg1 arg2))
       (swap [_ f arg1 arg2 more] (swap! backend f arg1 arg2 more))
       (compareAndSet [_ oldv newv] (compare-and-set! backend oldv newv))
       (reset [this new-value]
              (reset! backend new-value))])

  #?@(:cljs
      [IEquiv
       (-equiv [this other] (identical? this other))

       ISwap
       (-swap! [_ f] (swap! backend f))
       (-swap! [_ f arg] (swap! backend f arg))
       (-swap! [_ f arg1 arg2] (swap! backend f arg1 arg2))
       (-swap! [_ f arg1 arg2 more] (swap! backend f arg1 arg2 more))

       IReset
       (-reset! [_ new-value] (reset! backend new-value))])

  IDeref
  (deref [this]
    ((fsafe *tracker*) this)
    (.deref backend))

  #?(:clj IRef :cljs IWatchable)
  (#?(:clj addWatch :cljs -add-watch) [this watch-key watch-fn]
    (add-watch backend watch-key
               (fn [key ref old-value new-value]
                 (watch-fn key this old-value new-value)))
    this)
  (#?(:clj removeWatch :cljs -remove-watch) [this watch-key]
    (remove-watch backend watch-key)
    this)

  #?(:clj IObj :cljs IWithMeta)
  (#?(:clj withMeta :cljs -with-meta) [_ meta-map] (Atom. backend meta-map))

  IMeta
  (#?(:clj meta :cljs -meta) [_]
    meta-map)

  #?@(:cljs
      [IHash
       (-hash [this] (goog/getUid this))

       IPrintWithWriter
       (-pr-writer [this w opts] (pr-atom this w))]))

#?(:clj
   (defmethod print-method Atom [^Atom a ^java.io.Writer w] (pr-atom a w)))

(defn atom
  [state]
  (let [a (Atom. (clojure.core/atom state) nil)]
    ((fsafe *tracker*) a)
    a))

#?(:clj
   (defmacro with-tracking
     [tracker-fn & body]
     `(binding [*tracker* ~tracker-fn]
        ~@body)))

;;; Implementation

(defn pr-atom
  [a w]
  (#?(:clj .write :cljs write-all)
   w (str "#<signum/Atom@" (#?(:clj format :cljs gstring/format) "0x%x" (hash a)) ": " (-> (.-backend a) deref pr-str) ">")))
