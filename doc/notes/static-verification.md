# Choreographic Type System

We've implemented a static verification system for Klor in the form of **types**.
Our types do not statically restrict the kinds of values that can be used (e.g., integers, strings, etc.), so Klor is still dynamically typed just like Clojure.
Instead, our **choreographic type system** tracks **locations** of values and statically ensures that values cannot be used at a place other than the one they're located at.
Unlike Klor v1, a key feature that our type system enables is the support for **multi-located** values, allowing us to:

- express **agreements** between roles,
- completely eliminate selections,
- return multiple values at different roles, and
- manipulate choreographies as **first-class** values.

## Types

The type system understands 3 kinds of types.

**Agreement types** allow us to express that a value is agreed upon by multiple roles, i.e. that a set of roles hold the exact same value.
An agreement type is denoted with a Clojure set of roles, such as `#{Ana Bob Cal}` meaning "`Ana`, `Bob` and `Cal` agree on what this value is".
A bare role like `Ana` is just a shorthand for the singleton `#{Ana}`.

**Tuple types** allow us to return multiple values, each of which might be at a different location (or a set thereof).
A tuple type is denoted with a Clojure vector of types, such as `[Ana Bob #{Bob Cal}]` meaning "a result of 3 values: one at `Ana`, one at `Bob`, and one agreed upon by `Bob` and `Cal`".

**Choreography types** allow us to keep track of choreographies as values and remove the need for Klor v1's `dance` operator, unifying the call syntax of choreographies and standard Clojure functions.
A choreography type is denoted with a Clojure list prefixed with `->`, such as `(-> Ana #{Bob Cal} [Ana Dan])` meaning "a choreography that takes a value of type `Ana` and `#{Bob Cal}` as input, and returns a value of type `[Ana Dan]` as output".

The full syntax of types is given below (`R` stands for a role, `T` for a type).
Note that we allow arbitrary nesting and interleaving of tuple and choreography types.

```
R ::= <unqualified-symbol>
T ::= R              ; shorthand for a singleton agreement type
    | #{R+}          ; an agreement type
    | [T+]           ; a tuple type
    | (-> T* T)      ; a choreography type with omitted auxiliary roles
    | (-> T* T | 0)  ; a choreography type with empty auxiliary roles
    | (-> T* T | R+) ; a choreography type with explicit auxiliary roles
```

The **auxiliary roles** of a choreography type are roles which do not expect any input nor produce any output, but are nonetheless involved in the body of the choreography.
Tracking the auxiliary set of roles is necessary for the purposes of projection and is part of a choreography's signature.
We allow the user to omit the auxiliary set and instead infer it automatically in certain contexts (documented below).

Relevant files:

- [`multi/types.clj`](https://github.com/lovrosdu/klor/blob/master/src/klor/multi/types.clj) -- the infrastructure for working with types,
- [`multi/typecheck.clj`](https://github.com/lovrosdu/klor/blob/master/src/klor/multi/typecheck.clj) -- the type checker.

# Semantics

Here is a brief overview of the changes we've made in comparison to Klor v1:

- every Klor expression is now type checked, with references to Clojure vars and constants automatically **lifted** as agreement types,
- there is no longer the idea of an *active role*, as values can be **multi-located**,
- *role expressions* are now syntax sugar for a new special operator, `local`,
- communications are now **explicit**, as there is no active role,
- the execution semantics are still *out-of-order up to role dependency*,
- `do` has been kept as-is,
- `let` no longer requires explicitly specifying the locations of its bindings as they are now automatically **inferred**,
- `if` no longer requires the complex machinery of *selections* and *merging* due to agreement types, so `select` has been **removed**,
- `dance` is no longer required due to choreography types and has been **removed**,
- `(<op> <expr>*)` can invoke Clojure functions, **macros** and Klor **choreographies**,
- `at` has been **added** for manipulating agreement types,
- `local` has been **added** for controlling the behavior of lifting,
- `copy` has been **added** for communication,
- `pack` and `unpack` have been **added** for manipulating tuple types,
- `chor` has been **added** for creating anonymous choreographies,
- `inst` has been **added** for instantiating choreographic definitions.

The new special operators are:

- `(local [<role>+] <expr>*)`

  `local` modifies Klor's lifting procedure so that all references to Clojure vars and constants within its body are lifted as agreement types `#{<role>+}`.
  A role expression `(<role> <expr>*)` is syntax sugar for `(local [<role>] <expr>*)`.

- `(at [<role>+] <expr>)`

  Given `<expr>` of agreement type, `at` allows one to **narrow** the agreement type to a subset of roles `#{<role>+}`.
  This effectively "forgets/abandons" part of the agreement that was established.
  This only affects the compiler's knowledge and not the run-time values, which are still identical.

- `(copy [<src> <dst>] <expr>)`

  Given `<expr>` of agreement type with at least `<src>` in its agreement set, `copy` **communicates** the value of `<expr>` from `<src>` to `<dst>`, extending the agreement type with `<dst>`.
  In other words, communication keeps track of what roles share the value.
  Agreement can always be abandoned with the use of the `at` operator.

  A `copy` immediately followed by an `at` at the destination is called a **move**.
  This common operation is provided by the Klor standard library as the macro `(move [<src> <dst>] <expr>)`, equivalent to `(at [<dst>] (copy [<src> <dst>] <expr>))`.

  Both `copy` and `move` come with syntax sugar for convenience, using double and single **arrow expressions**.
  Instead of `(copy [Ana Bob] <expr>)` it is possible to write `(Ana=>Bob <expr>)`.
  Likewise, instead of `(move [Ana Bob] <expr>)` it is possible to write `(Ana->Bob <expr>)`.

- `(pack <expr>+)`

  `pack` constructs a value of tuple type whose elements are the results of each `<expr>`.

- `(unpack [<binding>*] <expr>*)`

  `unpack` deconstructs a value of tuple type by pattern matching.
  Each `binding` is a pair `<binder> <init>` where `<init>` must be of tuple type and `<binder>` must be a vector destructuring pattern matching the tuple's shape.
  Elements of `<init>` are bound to symbols in `<binder>` according to their position.
  Bindings are visible to subsequent `<init>` expressions and within the body of `unpack`.
  The vector destructuring pattern of a `<binder>` can be **nested** arbitrarily as long as each pattern matches the shape of the corresponding element.

- `(chor <signature> [<param>*] <expr>*)`

  `chor` is the choreographic analogue to Clojure's `fn` and creates an anonymous choreography.
  Each `<param>` in the parameter vector is either a symbol or an `unpack` binder, allowing for unpacking.
  `<signature>` must be a choreography type, specifying the type of each parameter (in order) and the return type.
  The signature may specify an auxiliary set explicitly, but a minimal one will be **inferred** if omitted.

- `(inst <name> [<role>+])`

  `inst` instantiates a choreographic definition created using `defchor`, returning a concrete choreography as a value.
  The definition is instantiated by substituting each `<role>` for the appropriate role parameter of the definition.

The following operators have had their behavior changed:

- `(defchor <name> [<role>+] <signature> [<param>*] <expr>*)`

  `defchor` is now essentially just a named version of `chor` and therefore requires a signature.
  The signature may specify an auxiliary set explicitly, but one containing all `<role>`s will be **inferred** if omitted.

- `(let [<binding>*] <expr>*)`

  The `<binder>` of each `<binding>` no longer has to, and in fact cannot, specify a location.
  Instead, the type of each binding is inferred from its `<init>`.
  Bindings can now be fully multi-located, referring to values of agreement, tuple or choreography type.

  Destructuring within `let` has the usual **Clojure semantics** of destructuring a collection and **not** Klor unpacking semantics.
  Such destructuring is only possible on agreement types, which should be of the appropriate collection type at run-time.

- `(if <cond> <then> <else>?)`

  `if` no longer requires the use of selections.
  Instead, `<cond>` must be of **agreement type** and a role is allowed to appear within `<then>` or `<else>` only if it is part of `<cond>`'s agreement type.
  Furthermore, the types of `<then>` and `<else>` must match.

- `(<op> <expr>*)`

  Standard invocation syntax can now be used to invoke not only Clojure functions but also Clojure macros and Klor choreographies.

  If `<op>` is of agreement type, all `<expr>`s must be of the exact same agreement type.

  If `<op>` is of choreography type, the type of each `<expr>` must match the type of the corresponding parameter of the choreography.

  For convenience, syntax sugar is provided in the case of invoking a choreographic definition.
  If `<op>` is a symbol that names a definition, the first argument must be a vector of roles used to instantiate the definition.
  In other words, `(<op> [<role>+] <expr>*)` desugars into `((<inst> <op> [<role>+]) <expr>*)`.
  If you need to instantiate the choreography without invoking it, use `inst` explicitly.

Relevant files:

- [`multi/analyzer.clj`](https://github.com/lovrosdu/klor/blob/master/src/klor/multi/analyzer.clj) -- the new Klor analyzer based on `tools.analyzer`,
- [`multi/macros.clj`](https://github.com/lovrosdu/klor/blob/master/src/klor/multi/macros.clj) -- the implementation of the new `defchor`,
- [`multi/stdlib.clj`](https://github.com/lovrosdu/klor/blob/master/src/klor/multi/stdlib.clj) -- Klor's standard library.

# Examples

## Buyer--Seller

Here is the standard Buyer--Seller protocol rewritten for the new version of Klor.
Notice the absence of selections.

```clojure
(defchor buy-book [B S] (-> B S B) [order catalog]
  (let [price (S->B (S (get catalog (B->S (B (:title order))) :none)))]
    (if (B=>S (B (and (int? price) (>= (:budget order) price))))
      (let [date (S->B (S (ship! (B->S (B (:address order))))))]
        (B (println "I'll get the book on" date))
        date)
      (do (S (println "B changed his mind"))
          (B nil)))))
```

## Higher-Order Two-Buyer

The two-buyer protocol is similar to Buyer--Seller but involves two buyers.
The second buyer `B2` doesn't communicate with the seller directly but helps `B1` make the decision whether to buy the book or not, for example by covering part of the cost.
We model the protocol with `buy-book-2` below.

Rather than hardcode the decision process between `B1` and `B2`, `buy-book-2` takes it as a new `decide` parameter of choreography type, `(-> B1 B1 | B2)`.
This makes `buy-book-2` a higher-order choreography, as it takes another choreography as input.

The `decide` choreography will be invoked with the price at `B1` and must return a boolean decision at `B1`.
However, it can freely communicate with `B2` to arrive at the final result.

Note how the body of `buy-book-2` is almost identical to `buy-book` except that the guard of the conditional has been changed to externalize the decision-making process.

```clojure
(defchor buy-book-2 [B1 B2 S] (-> B1 S (-> B1 B1 | B2) B1)
  [order catalog decide]
  (let [price (S->B1 (S (get catalog (B1->S (B1 (:title order))) :none)))]
    (if (B1=>S (B1 (and (int? price) (decide price))))
      (let [date (S->B1 (S (ship! (B1->S (B1 (:address order))))))]
        (B1 (println "I'll get the book on" date))
        date)
      (do (S (println "Buyer changed his mind"))
          (B1 nil)))))
```

The `main` choreography shows an example usage.
It invokes `buy-book-2` and passes it an anonymous choreography constructed using `chor`.
We use a simple decision choreography where `B2` contributes with a fixed amount of 42.

```clojure
(defchor main [B1 B2 S] (-> B1 S B1) [order catalog]
  (buy-book-2 [B1 B2 S] order catalog
              (chor (-> B1 B1) [price]
                (let [contrib (B2->B1 (B2 42))]
                  (B1 (>= (:budget order) (- price contrib)))))))
```

## Diffie--Hellman Key Exchange

The Diffie--Hellman key exchange protocol allows two parties `A` and `B` to securely exchange cryptographic keys over a public channel.

Assume that we are given the shared generator `g`, the shared prime `p`, two secret values `sa` and `sb`, and that modular exponentiation `a^b mod m` is implemented with the Clojure function `(modpow a b m)`.
Then we can compute the cryptographic keys as follows:

```clojure
(defchor exchange-key [A B] (-> #{A B} #{A B} A B [A B]) [g p sa sb]
  (let [ga (at [A] g) pa (at [A] p)
        gb (at [B] g) pb (at [B] p)]
    (pack (A (modpow (B->A (B (modpow gb sb pb))) sa pa))
          (B (modpow (A->B (A (modpow ga sa pa))) sb pb)))))
```

Note how we use `pack` to return multiple values to the caller: the key at `A` and the key at `B`, respectively.

## Distributed Authentication

In this distributed authentication protocol the client `C` wishes to authenticate through the authenticator `A` so that it can receive a session token from `S`.

In the `auth` choreography, the client sends its credentials to the authenticator and the two eventually reach agreement on whether authentication has succeeded or not.

```clojure
(defchor auth [C A] (-> C #{C A}) [creds]
  (or (A=>C (A (= (:password (C->A creds)) "secret")))
      (and (C=>A (C (rand-nth [true false])))
           (auth [C A] creds))))
```

In the `get-token` choreography, the client and the authenticator first reach agreement via `auth`.
Then, the authenticator communicates the outcome to the server, extending the agreement set.
Finally, the server communicates a session token to the client if the authentication was successful.
Again, notice the absence of selections.

```clojure
(defchor get-token [C S A] (-> C C) [creds]
  (if (A=>S (auth [C A] creds))
    (S->C (S (random-uuid)))
    (C :error)))
```
