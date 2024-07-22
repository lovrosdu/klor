# Chang--Roberts algorithm

The Chang--Roberts algorithm is a *leader election algorithm* for *ring networks*.
It assumes that each role has a *unique identifier*.

It is implemented by the Klor choreography `(chang-roberts [<role>+] <init>+)`:

- The given roles are connected in a ring formation from left to right, with the last role wrapping around to the first.

- Each `<init>` is an expression that evaluates to a map providing the initial state of each role, in order of their appearance in the role list.
  The map must contain an `:id` key whose value is the identifier, distinct from any other role's identifier.
  The map can also contain a `:passive?` key whose value is a boolean that determines whether the role participates in the election process or only forwards messages.

- The return value is a choreographic tuple containing the final states of the roles, in order of their appearance in the role list.
  The state will contain the `:leader` key whose value is the identifier of the elected leader.

The following example uses Chang--Roberts to elect a leader among 3 roles (`B`, `C` and `D`) whose identifiers are randomly-chosen (but unique) by a separate role `A`:

```clojure
(defchor elect-leader-1 [A B C D] (-> [B C D]) []
  (unpack [[id1 id2 id3]
           (scatter-seq [A B C D] (A (take 3 (shuffle (range 10)))))]
    (chang-roberts [B C D] (B {:id id1}) (C {:id id2}) (D {:id id3}))))
```

```
A --> C: 3
A --> B: 7
A --> D: 0
C --> D: [:propose {:id 3}]
B --> C: [:propose {:id 7}]
C --> D: [:propose {:id 7}]
D --> B: [:propose {:id 0}]
D --> B: [:propose {:id 3}]
D --> B: [:propose {:id 7}]
B --> C: [:exit {:id 7}]
C --> D: [:exit {:id 7}]
D --> B: [:exit {:id 7}]
```

```
{A #function[klor.multi.runtime/noop],
 B [{:id 7, :leader 7, :leader? true}],
 C [{:id 3, :passive? true, :leader 7}],
 D [{:id 0, :passive? true, :leader 7}]}
```

# Itai--Rodeh algorithm

The Itai--Rodeh algorithm is a refinement of Chang--Roberts that can deal with *duplicate identifiers* using a *probabilistic approach*.
This is useful in the context of *anonymous networks* where nodes either don't have or cannot reveal their unique identifier.

It is implemented by the Klor choreography `(itai-rodeh [<role>+] <init>+)`:

- The parameters have the same meaning as in `chang-roberts`, except that the roles' individual `:id` keys do not have to be unique.
  Actually, the `:id` keys can even be left out in which case they will be randomly-chosen integers.

- The return value is a choreographic tuple as in `chang-roberts`.

The following example uses Itai--Rodeh to elect a leader among 3 roles that start out with randomly-chosen identifiers:

```clojure
(defchor elect-leader-2 [A B C] (-> [A B C]) []
  (itai-rodeh [A B C] (A {}) (B {:id (rand-int 5)}) (C {})))
```

```
A --> B: [:propose {:hops 1, :round 0, :id 1, :dup? false}]
C --> A: [:propose {:hops 1, :round 0, :id 2, :dup? false}]
B --> C: [:propose {:hops 1, :round 0, :id 1, :dup? false}]
A --> B: [:propose {:hops 2, :round 0, :id 2, :dup? false}]
B --> C: [:propose {:id 1, :round 0, :hops 2, :dup? true}]
B --> C: [:propose {:hops 3, :round 0, :id 2, :dup? false}]
C --> A: [:exit {:hops 1}]
A --> B: [:exit {:hops 2}]
B --> C: [:exit {:hops 3}]
```

```
{A [{:round 0, :id 1, :passive? true, :leader 2}],
 B [{:round 0, :id 1, :passive? true, :leader 2}],
 C [{:round 0, :id 2, :leader 2, :leader? true}]}
```

# Tarry's algorithm

Tarry's algorithm is a *traversal algorithm* for *undirected networks*.
A message will be passed throughout the whole network with the guarantee that each node will send to each of its neighbors *exactly once*.

It is implemented by the Klor choreography `(tarry [<role>+] <layout>)`:

- The given roles are connected in a formation as described by `<layout>` using the following grammar:

  ```
  <layout>    ::= [<chain>*]
  <chain>     ::= (<link-head> <link-tail>+)
  <link-head> ::= <role>
  <link-tail> ::= -> <role> | <- <role> | -- <role>
  ```

  The layout is essentially a sequence of chains, each consisting of one or more uni- or bidirectional links.
  This makes it convenient to specify arbitrary connectivity graphs.

- The first role in the list is the *initiator* of the traversal.

- The return value is a choreographic tuple containing the final states of the roles, in order of their appearance in the role list.
  The state will contain the `:parent` key whose value is the name of the role that first forwarded a message to the respective role, or `:root` if the role was the initiator.

The following example uses Tarry's algorithm to traverse a small network of 5 roles:

```clojure
(defchor traverse-1 [A B C D E] (-> [A B C D E]) []
  (tarry [A B C D E] [(C -- B -- A -- D -- E -- B) (A -- E)]))
```

```
A --> D: [:token {:hops 1}]
D --> E: [:token {:hops 2}]
E --> A: [:token {:hops 3}]
A --> E: [:token {:hops 4}]
E --> B: [:token {:hops 5}]
B --> A: [:token {:hops 6}]
A --> B: [:token {:hops 7}]
B --> C: [:token {:hops 8}]
C --> B: [:token {:hops 9}]
B --> E: [:token {:hops 10}]
E --> D: [:token {:hops 11}]
D --> A: [:token {:hops 12}]
```

```
{A [{:parent :root, :seen #{D B E}}],
 B [{:seen #{A C E}, :parent E}],
 C [{:seen #{B}, :parent B}],
 D [{:seen #{A E}, :parent A}],
 E [{:seen #{A D B}, :parent D}]}
```

# Depth-first search

Depth-first search is a refinement of Tarry's algorithm that traverses the network in a *depth-first order*.

It is implemented by the Klor choreography `(dfs [<role>+] <layout>)`:

- The parameters and the return value are as in `tarry`.

The following example uses depth-first search to traverse the same example network from above:

```clojure
(defchor traverse-2 [A B C D E] (-> [A B C D E]) []
  (dfs [A B C D E] [(C -- B -- A -- D -- E -- B) (A -- E)]))
```

```
A --> D: [:token {:hops 1}]
D --> E: [:token {:hops 2}]
E --> A: [:token {:hops 3}]
A --> E: [:token {:hops 4}]
E --> B: [:token {:hops 5}]
B --> C: [:token {:hops 6}]
C --> B: [:token {:hops 7}]
B --> A: [:token {:hops 8}]
A --> B: [:token {:hops 9}]
B --> E: [:token {:hops 10}]
E --> D: [:token {:hops 11}]
D --> A: [:token {:hops 12}]
```

```
{A [{:parent :root, :seen #{D B E}}],
 B [{:seen #{A C E}, :parent E}],
 C [{:seen #{B}, :parent B}],
 D [{:seen #{A E}, :parent A}],
 E [{:seen #{A D B}, :parent D}]}
```

# Echo algorithm

The echo algorithm is a *wave algorithm* for *undirected networks*, which will distribute a message across a network in a manner similar to *breadth-first* search.

It is implemented by the Klor choreography `(echo [<role>+] <layout>)`:

- The parameters and the return value are as in `tarry`.

The following example uses the echo algorithm to distribute a message throughout the example network:

```clojure
(defchor distribute [A B C D E] (-> [A B C D E]) []
  (echo [A B C D E] [(C -- B -- A -- D -- E -- B -- D) (A -- E)]))
```

```
A --> B: [:token {:hops 1}]
B --> D: [:token {:hops 2}]
A --> D: [:token {:hops 1}]
B --> C: [:token {:hops 2}]
D --> A: [:token {:hops 3}]
C --> B: [:token {:hops 3}]
B --> E: [:token {:hops 2}]
A --> E: [:token {:hops 1}]
E --> A: [:token {:hops 3}]
E --> D: [:token {:hops 3}]
D --> B: [:token {:hops 4}]
D --> E: [:token {:hops 3}]
E --> B: [:token {:hops 4}]
B --> A: [:token {:hops 5}]
```

```
{A [{:todo #{}, :parent :root}],
 B [{:todo #{}, :parent A}],
 C [{:todo #{}, :parent B}],
 D [{:todo #{}, :parent B}],
 E [{:todo #{}, :parent B}]}
```

# Echo algorithm with extinction

The echo algorithm with extinction is a *leader election algorithm* for *undirected networks* based on the echo algorithm.
Each node initiates a wave of the echo algorithm in hope of becoming a leader.
The algorithm assumes that each role has a *unique identifier*.

It is implemented by the Klor choreography `(echoex [<role>+] <layout> <init>+)`:

- The `<role>` and `<layout>` arguments are as in `tarry`.

- The `<init>` arguments are as in `chang-roberts`: they represent the initial states of the roles and must contain an `:id` key whose value is the unique role identifier.

- The return value is a choreographic tuple containing the final states of the roles, in order of their appearance in the role list.
  The state will contain the `:wave` key whose value is the identifier of the elected leader..

The following example uses the echo algorithm with extinction to elect a leader within the example network:

```clojure
(defchor elect-leader-3 [A B C D E] (-> [A B C D E]) []
  (let [ids (A (take 5 (shuffle (range 10))))
        id1 (A (first ids))]
    (unpack [[id2 id3 id4 id5] (scatter-seq [A B C D E] (rest ids))]
      (echoex [A B C D E] [(C -- B -- A -- D -- E -- B -- D) (A -- E)]
        (A {:id id1}) (B {:id id2}) (C {:id id3})
        (D {:id id4}) (E {:id id5})))))
```

```
A --> B: 0
A --> D: 3
A --> E: 5
A --> C: 7
B --> D: [:token {:id 0}]
D --> A: [:token {:id 3}]
B --> A: [:token {:id 0}]
A --> D: [:token {:id 6}]
...
A --> E: [:token {:id 7}]
D --> E: [:token {:id 7}]
E --> D: [:token {:id 7}]
E --> B: [:token {:id 7}]
D --> B: [:token {:id 7}]
B --> C: [:token {:id 7}]
```

```
{A [{:id 6, :itodo #{D B E}, :parent B, :todo #{}, :wave 7, :exit true}],
 B [{:id 0, :itodo #{A D C E}, :parent C, :todo #{}, :wave 7, :exit true}],
 C [{:id 7, :itodo #{B}, :parent :root, :todo #{}, :wave 7, :exit true}],
 D [{:id 3, :itodo #{A B E}, :parent B, :todo #{}, :wave 7, :exit true}],
 E [{:id 5, :itodo #{A D B}, :parent B, :todo #{}, :wave 7, :exit true}]}
```
