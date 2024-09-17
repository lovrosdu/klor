# Tutorial: Execution

## Clojure-calls-Klor

So far we've been running all of our choreographies in a single Clojure process with the help of the Klor simulator.
In practice we will want to distribute the execution of a choreography over multiple threads, processes, physical nodes or even a combination thereof.
For that purpose, we need the ability to integrate the individual projections of a choreography with Clojure code, as well as customize the mechanism that the roles use to communicate.

The main way to use a choreography from Clojure code is to "play one of its roles", i.e. invoke one of its projections.
A single Clojure process can play one or multiple roles of a choreography, though it is important to ensure that the roles of a single choreography always execute **concurrently** (e.g. by executing them on different threads).
A single process can also be involved in multiple different choreographies, either simultaneously or at different points in time.

To demonstrate the use of a Klor choreography from Clojure we will implement a toy bookseller choreography and run it over TCP sockets.
Two roles, the buyer `B` and seller `S`, communicate for `B` to buy a book from `S`:

- `B` sends `S` the title of the book,
- `S` receives the title, looks up the price of the book and sends it to `B`,
- `B` receives the price and decides whether to buy the book depending on its budget,
- `B` sends its decision to `S`, along with an address if necessary,
- `S` receives the decision and possibly returns the delivery date to the `B`.

We'll use the follow data structures:

- `order` is a map `{:title <string> :budget <number> :address <string>}`,
- `catalog` is a map of `<string> <number>` pairs mapping book names to their prices,
- `ship!` is a side effectful function that, given the address, executes the book shipment and returns the delivery date.

Here's how the choreography might look like in Klor:

```clojure
(defn ship! [address]
  ;; Assume some side effect occurs.
  (str (java.time.LocalDate/now)))

(defchor buy-book [B S] (-> B S B) [order catalog]
  (let [price (S->B (S (get catalog (B->S (B (:title order))) :none)))]
    (if (B=>S (B (and (int? price) (>= (:budget order) price))))
      (let [date (S->B (S (ship! (B->S (B (:address order))))))]
        (B (println "I'll get the book on" (str date)))
        date)
      (do (S (println "Buyer changed his mind"))
          (B nil)))))
```

With the choreography is in place, we can write "driver code" that will invoke the respective projections.
This is done using Klor's `(play-role <conf> <chor> <arg>*)` function.
It invokes the desired projection of a choreography and passes to it the given arguments.

`play-role` and configure the necessary transport functions and TCP sockets as the locators.

For a TCP connection to work one of the roles will have to act as a server, so we choose `S` to be the server and `B` to be the client.

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
    (with-server [ssc {:host host :port port}]
      (loop []
        (println "Listening on" (str (.getLocalAddress ssc)))
        (with-accept [ssc sc]
          (println "Got client" (str (.getRemoteAddress sc)))
          (play-role (wrap-sockets {:role 'S} {'B sc} :log log)
                     buy-book catalog))
        (when forever (recur))))))

(defn run-buyer [order & {:keys [host port log]
                          :or {host "127.0.0.1" port port log :dynamic}}]
  (let [order (merge {:title "To Mock A Mockingbird"
                      :budget 50
                      :address "Some Address 123"}
                     order)]
    (with-client [sc {:host host :port port}]
      (println "Connected to" (str (.getRemoteAddress sc)))
      (play-role (wrap-sockets {:role 'B} {'S sc} :log log)
                 buy-book order))))
```

Note that the drivers come with some hardcoded data for the purposes of the example.
In particular, the catalog used by `run-seller` defaults to the map `{"To Mock A Mockingbird" 50}` if `nil` is given.
Similarly, the order provided to `run-buyer` order is merged with the default map `{:title "To Mock A Mockingbird" :budget 50 :address "Some Address 123"}`.

Now we can run the `S` on a separate thread with some logging enabled: `(run-seller nil :forever true :log true)`.
This will start the server and run in a loop forever, accepting one `B` at a time.

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

---
