(ns signum.atom
  (:refer-clojure :exclude [atom Atom])
  (:require [utilis.fn :refer [apply-kw]]
            #?@(:cljs
                [[goog.string :as gstring]
                 [goog.string.format]]))
  #?(:clj (:import [clojure.lang IAtom IAtom2 IRef IDeref ARef IObj IMeta])))

(def ^:dynamic *deref-tracker* nil)

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
    (when  *deref-tracker* (swap! *deref-tracker* conj this))
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
  (Atom. (clojure.core/atom state) nil))

#?(:clj
   (defmacro with-tracking
     [inputs & body]
     `(binding [*deref-tracker* (clojure.core/atom #{})]
        (let [~inputs *deref-tracker*]
          ~@body))))

;;; Implementation

(defn pr-atom
  [a w]
  (#?(:clj .write :cljs write-all)
   w (str "#<signum/Atom@" (#?(:clj format :cljs gstring/format) "0x%x" (hash a)) ": "(-> (.-backend a) deref pr-str) ">")))
