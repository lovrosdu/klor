# Tutorial: Execution

## Invoking Projections / Interfacing with Klor

So far we've been running all of our choreographies in a single Clojure process with the help of the Klor simulator.
In practice we will want to distribute the execution of a choreography over multiple processes, physical nodes or even a combination thereof.
In those cases, we need the ability to independently invoke the projections of a choreography as well as customize the transport mechanism that the roles use to communicate.

---

Klor aims to be very flexible and impose minimal restrictions on the way projected code is deployed and executed in practice, as well as which particular communication mechanism is used between roles.

Once a Klor choreography has been written, there has to be a way to integrate it with existing Clojure code.

The way to interface with a choreography is to "play one of its roles", i.e. invoke one of its projections.
Note that a single Clojure process might play one or multiple roles of a choreography, either concurrently (e.g. on multiple threads) or at different points in time.
For that reason, when a choreography is defined using `defchor`, all of its projections become available as plain Clojure functions within the running Clojure process.

Playing a role is done with Klor's `(play-role <conf> <chor> <arg>*)` function which sets up a minimal amount of runtime state and then invokes the desired projection of `<chor>`, passing to it all given `<arg>` as arguments.
Note that the projection for a role accepts only as many arguments as there are choreography parameters with that role.
Therefore, the number of arguments provided to `play-role` depends on which role is played.

The most important parameter is `<conf>`, called the **role configuration**.
It is a map that describes which role to play and how to communicate with other roles.
Its structure is the following:

```clojure
{:role <role>
 :send <send-fn>
 :recv <recv-fn>
 :locators {<role-1> <loc-1> ... <role-n> <loc-n>}}
```

`<role>` is an unqualified symbol that names the role to play.

`<send-fn>`, `<recv-fn>`, `<choose-fn>` and `<offer-fn>` together form the **transport** that defines how values are actually communicated to/from other roles.
When a role has to send or receive a normal value, Klor will call into one of these two functions:

- `<send-fn>`: its parameter list is `(<loc> <value>)`; it should send the given `<value>` and return; its result is ignored.
- `<recv-fn>`: its parameter list is `(<loc>)`; it should receive a value and return it as its result.

`<choose-fn>` and `<offer-fn>` are  just like `<send-fn>` and `<recv-fn>`, respectively, except that they are used when communicating labels.
`:choose` and `:offer` can be left out, in which case they default to the values of `:send` and `:recv`.

All transport functions accept a **locator** as their first argument, which is an arbitrary Clojure value representing the role to send to/receive from.
It should contain all of the necessary information for the send and receive functions to be able to carry out their effects.
The values of locators are supplied via the `:locators` map, mapping role names (as they appear in the choreography's role list) to locators.
A locator should be provided for any `<role-i>` that communicates with the role being played, but can otherwise be left out.
If a locator is left out but ends up being required by a transport function, the symbol naming the role will be used as the locator.
Practically speaking, locators will most often be mechanisms such as `core.async` channels, TCP sockets, etc.

If a choreography invokes another choreography via the `dance` operator, Klor will make sure to properly "thread" the role configuration for you, taking into account the way the choreography was instantiated.
Any communication actions performed by the invoked choreography will use the same transport functions and locators provided at the top.
Therefore, the Clojure user only ever has to worry about the "top-level view" and getting the initial call to `play-role` right.

---

The fundamental way to interact with a projection of a Klor choreography from Clojure is Klor's `play-role` function.
This is the "Clojure-calls-Klor" direction of integration.

To use `play-role` and provide arguments to a projection the user must know the projection's signature, which is derived from the choreography's signature in an **erasure style**.

As a rough guideline, for the projection at `r`:

- parameters that do not mention `r` at all are erased from its parameter list,
- parameters of agreement type are kept and assumed to be arbitrary Clojure values.

Projections of choreographies that take parameters of tuple and choreography types cannot be invoked directly using `play-role` so as to reduce the exposure of implementation details and possibility of user mistakes.
Instead, the user can create a "wrapper choreography" that takes only parameters of agreement type and constructs the necessary tuples and choreographies to pass to the choreography of interest.
Because this is done within the confines of the type system it is guaranteed that the constructed values follow Klor's usual expectations.

For example, the two-buyer choreography `buy-book-2` cannot be invoked with `play-role` directly as it takes another choreography as a parameter:

```clojure
(defchor buy-book-2 [B1 B2 S] (-> B1 S (-> B1 B1 | B2) B1)
  [order catalog decide]
  ...)

;; Forbidden
(play-role buy-book-2 {:role 'B1} <order> <catalog> <???>)
```

However, we can create a wrapper choreography with only parameters of agreement type that we can invoke using `play-role`:

```clojure
(defchor buy-book-2-main [B1 B2 S] (-> B1 S B1) [order catalog]
  (buy-book-2 [B1 B2 S] order catalog (chor (-> B1 B1) [price] ...)))

;; Allowed
(play-role buy-book-2-main {:role 'B1} <order>)
(play-role buy-book-2-main {:role 'B2})
(play-role buy-book-2-main {:role 'S} <catalog>)
```

Like in Klor v1, the second parameter to `play-role` is the **role configuration** that describes which projection to invoke and how to communicate with the other roles.

## TODO Inspecting Projections

## Customizing Transport

Using Klor's interface it is already possible to model a practical real-world example.
Here we implement the classical Buyer--Seller choreography and run it over TCP sockets.

First we define the choreography.
Two roles, `Buyer` and `Seller`, communicate for `Buyer` to buy a book from `Seller`:

- `Buyer` sends `Seller` the title of the book,
- `Seller` receives the title, looks up the price of the book and sends it to `Buyer`,
- `Buyer` receives the price and decides whether to buy the book depending on its budget,
- `Buyer` sends its decision to `Seller`, along with an address if necessary,
- `Seller` receives the decision and possibly returns the delivery date to the `Buyer`.

Assume we model the problem in Clojure using the following:

- `order` is a map `{:title <string> :budget <number> :address <string>}`,
- `catalog` is a map of `<string> <number>` pairs mapping book names to their prices,
- `ship!` is a side effectful function that, given the address, executes the book shipment and returns the delivery date.

Putting it all together, here's how the choreography might look like in Klor:

```clojure
(defn ship! [address]
  ;; Assume some side effect occurs.
  (str (java.time.LocalDate/now)))

(defchor buy-book [Buyer Seller] [Buyer/order Seller/catalog]
  (let [Seller/title (Buyer (:title order))
        Buyer/price (Seller (get catalog title :none))]
    (Buyer
     (if (and (int? price) (>= (:budget order) price))
       (select [Buyer/ok Seller]
         (let [Seller/address (Buyer (:address order))
               Buyer/date (Seller (ship! address))]
           (println "I'll get the book on" date)
           date))
       (select [Buyer/ko Seller]
         (Seller (println "Buyer changed his mind"))
         nil)))))
```

Once the choreography is in place, we can write "driver code" that will invoke the respective projections using `play-role` and configure the necessary transport functions and TCP sockets as the locators.
For a TCP connection to work one of the roles will have to act as a server, so we choose `Seller` to be the server and `Buyer` to be the client.

Klor provides the `wrap-sockets` utility which produces a role configuration containing `:send` and `:recv` transport functions that perform their communication assuming the locators are standard Java NIO `SocketChannel` objects.
For serializing and deserializing Clojure values, the functions use the [Nippy](https://github.com/taoensso/nippy) serialization library.

The drivers also use a few more utilities -- `with-server`, `with-accept` and `with-client`, all part of Klor -- which are convenience macros that deal with the boilerplate of setting up a server socket, accepting connections from clients, and connecting a client to a server.

The drivers are `run-seller` and `run-buyer`:

```clojure
(def port 1337)

(defn run-seller [catalog & {:keys [host port forever log] :or
                               {host "0.0.0.0" port port
                                forever false log :dynamic}}]
  (let [catalog (or catalog {"To Mock A Mockingbird" 50})]
    (with-server [ssc :host host :port port]
      (loop []
        (println "Listening on" (str (. ssc (getLocalAddress))))
        (with-accept [ssc sc]
          (println "Got client" (str (. sc (getRemoteAddress))))
          (play-role (wrap-sockets {:role 'Seller} {'Buyer sc} :log log)
                     buy-book catalog))
        (when forever (recur))))))

(defn run-buyer [order & {:keys [host port log]
                          :or {host "127.0.0.1" port port log :dynamic}}]
  (let [order (merge {:title "To Mock A Mockingbird"
                      :budget 50
                      :address "Some Address 123"}
                     order)]
    (with-client [sc :host host :port port]
      (println "Connected to" (str (. sc (getRemoteAddress))))
      (play-role (wrap-sockets {:role 'Buyer} {'Seller sc} :log log)
                 buy-book order))))
```

Note that the drivers come with some hardcoded data for the purposes of the example.
In particular, the catalog used by `run-seller` defaults to the map `{"To Mock A Mockingbird" 50}` if `nil` is given.
Similarly, the order provided to `run-buyer` order is merged with the default map `{:title "To Mock A Mockingbird" :budget 50 :address "Some Address 123"}`.

Now we can run the `Seller` on a separate thread with some logging enabled: `(run-seller nil :forever true :log true)`.
This will start the server and run in a loop forever, accepting one `Buyer` at a time.

From the REPL (even from a different Clojure process) we can now connect individual clients by using `run-buyer`.
A few example runs and their trace logs follow.
Lines with arrows pointing to the right (`-->`) are receives, while those with arrows pointing to the left (`<--`) are sends.

The default order:

```
klor> (run-buyer nil)

Connected to /127.0.0.1:1337
Got client /127.0.0.1:42320
/127.0.0.1:42320 --> "To Mock A Mockingbird"
/127.0.0.1:42320 <-- 50
/127.0.0.1:42320 --> ok
/127.0.0.1:42320 --> "Some Address 123"
/127.0.0.1:42320 <-- "2024-02-20"
I'll get the book on 2024-02-20
```

When the budget is too low:

```
klor> (run-buyer {:budget 49})

Connected to /127.0.0.1:1337
Got client /127.0.0.1:56662
/127.0.0.1:56662 --> "To Mock A Mockingbird"
/127.0.0.1:56662 <-- 50
/127.0.0.1:56662 --> ko
Buyer changed his mind
```

When the book is not found in the catalog:

```
klor> (run-buyer {:title "Weird"})

Got client /127.0.0.1:50442
Connected to /127.0.0.1:1337
/127.0.0.1:50442 --> "Weird"
/127.0.0.1:50442 <-- :none
/127.0.0.1:50442 --> ko
Buyer changed his mind
```
