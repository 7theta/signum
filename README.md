# com.7theta/signum

[![Current Version](https://img.shields.io/clojars/v/com.7theta/signum.svg)](https://clojars.org/com.7theta/signum)
[![GitHub license](https://img.shields.io/github/license/7theta/signum.svg)](LICENSE)
[![Dependencies Status](https://jarkeeper.com/7theta/signum/status.svg)](https://jarkeeper.com/7theta/signum)

A Signal is a value that changes over time and is implemented by `signum.atom/atom`

A Reaction is a mechanism for wrapping computation around one or
more Signals, that runs every time one of the referenced Signals
changes. The output of a Reaction is also a Signal. 

Signum introduces the concept of a Subscription (similar to the one
provided by re-frame) that formalizes the use of Signals and Reactions.

These basic building blocks and be used to create sophisticated graphs
that update in real time as values change.

## Usage

The core abstraction provided is a Subscription. A Subscription is used
to manage the lifecycle of external Signals as well as wrap the input
Signals with computation.

Signals can either be obtained from other Subscriptions or via
de-referencing externally allocated `signum.atom/atom`s from the
Reaction graph. 

A Subscription is declared via `signum.subs/reg-sub`. `reg-sub` is
available in several arities. The core of a Subscription is a
`query-id` and a computation function. Additional arities support the
specification of init and dispose functions as well.

The following example declares a basic Reaction graph.

```clojure

(require '[signum.atom :as s])
(require '[signum.subs :refer [reg-sub subscribe]])

(reg-sub
 :api.example/my-counter
 (fn [query-v]
   (let [counter (s/atom 0)
         counter-loop (future
                        (loop []
                          (swap! counter inc)
                          (Thread/sleep 1000)
                          (recur)))]
     {:counter counter
      :counter-loop counter-loop}))
 (fn [{:keys [counter-loop]} _query-v]
   (future-cancel counter-loop))
 (fn [{:keys [counter]} _query-v]
   @counter))

(reg-sub
 :api.example/auto-increment-string
 (fn [[_ some-text]]
   (let [value @(subscribe [:api.example/my-counter])]
     {:value value
      :str (str some-text "-" value)})))
```

The Subscription for `:api.example/my-counter` declares init, dispose
and computation functions, where is the Subscription for
`:api.example/auto-increment-string` only declares a computation
function.

The init and dispose functions are used to manage resources outside of
the Subscription graph. In the case of `:api.example/my-counter` the
init function is allocating a counter and a future that will increment
it every second. The dispose function is used to shut these down. The
value returned from the init funtion is passed into the computation
and dispose functions along with the query vector. 

If there are no init and dispose functions in a `reg-sub`, the
computation function will only be passed in the query-vector.

The `subscribe` function is used within
`:api.example/auto-increment-string` to reference the Signal for
`:api.example/my-counter`.

Any `signum.atom/atom` dereferenced within a computation function will
cause the computation function to automatically run if the atom changes.

### Implementation details

The Signal returned from a subscribe receives it's value
asynchronously and it is important to watch it so that the updated
values can be received. 

The first time an `add-watch` is called, the upstream Subscription
graph gets instantiated. When the last `remove-watch` is called, the
upstream Subscription graph is disposed.

If an `add-watch` is never called, the Subscription graph will never
be instantiated and the value will always be nil. This will also cause
the output-signal to be retained forever.

This is only important to keep track of if you're implementing a
library on top of Signum. No special handling is required if using
`com.7theta/via`, `com.7theta/servo` etc.

## Copyright and License

Copyright © 2020 7theta

Distributed under the Eclipse Public License.


