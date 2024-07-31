# Tutorial: Composition

## Invoking Choreographies

Invoking a choreography is only slightly different from invoking an agreement operator.
Here's an example to demonstrate:

```clojure
(defchor remote-map [A B] (-> B A A) [f xs]
  (if (A=>B (A (empty? xs)))
    (A nil)
    (A (cons (remote-invoke [A B] f (first xs))
             (remote-map [A B] f (next xs))))))
```

The call to `remote-invoke` (defined previously) and the recursive call to `remote-map` are choreography invocations.
When invoking a named **choreographic definition**, one first has to **instantiate** its role parameters by passing a role vector as the first argument.
The role vector can use any role available from the current context (the enclosing choreographic definition), but it cannot contain duplicate roles, i.e. **role aliasing is not allowed**.
The rule for invoking a choreographic operator is simple: all of the arguments must be of the type expected by the choreography's signature.

`remote-map` implements a choreography where `A` sends successive elements of a sequence to `B` for processing.
At each step, `A` communicates to `B` whether there are more elements to process, and if so, sends one over and waits for the result.
Once done, `A` will return the list of the collected results.
To see it in action, `@(simulate-chor remote-map inc [1 2 3])`:

```
>> A spawned
>> B spawned
>> A --> B: false
>> A --> B: 1
>> B --> A: 2
>> A --> B: false
>> A --> B: 2
>> B --> A: 3
>> A --> B: false
>> A --> B: 3
>> B --> A: 4
>> A exited normally
>> A --> B: true
>> B exited normally
=> {A (2 3 4), B #function[klor.multi.runtime/noop]}
```

Here's another interesting example that combines all of the features we've seen so far: easy reuse of Clojure code, agreement types and choreography invocation:

```clojure
(defchor auth [C A] (-> C #{C A}) [get-creds]
  (or (A=>C (A (= (:password (C->A (get-creds))) "secret")))
      (and (C=>A (C (rand-nth [true false])))
           (auth [C A] get-creds))))

(defchor get-token [C S A] (-> C C) [get-creds]
  (if (A=>S (auth [C A] get-creds))
    (S->C (S (random-uuid)))
    (C :error)))
```

The entrypoint is the `get-token` choreography which implements a form of distributed authentication between three roles: the client `C`, the server `S` and the authenticator `A`.
`S` will grant `C` a session token if it first successfully authenticates with `A`.

The authentication itself is implemented as part of the helper `auth` choreography, involving *only* `C` and `A`.
The credentials sent from `C` to `A` will either match the predefined secret or lead to additional authentication attempts depending on whether `C` wants to continue.
The two will eventually reach agreement on the outcome of the authentication and return it as the result.

Agreement types allow us to once again elegantly handle knowledge of choice.
The first instance is in `auth` where we rely on the standard Clojure macros `or` and `and` for their short-circuiting behavior.
The macros will expand to nested conditionals that were not written with Klor in mind, but our type-based approach allows us to robustly handle them regardless (compared for example to the term-based approaches found in other languages, such as *selections*, that would instead require explicit instructions in each branch).
The second instance is in `get-token` where the agreement `#{C A}` is straightforwardly extended to `S`.
Here we need all three roles to be present: `S` and `C` because they appear within the branches, and `A` because it makes the decision.

The `get-creds` parameter allows us to specify a custom function to fetch the credentials at the client.
For example, to simulate retrying we can use `@(simulate-chor get-token #(hash-map :password (rand-nth ["wrong" "secret"])))`:

```
>> C spawned
>> S spawned
>> A spawned
>> C --> A: {:password "wrong"}
>> A --> C: false
>> C --> A: true
>> C --> A: {:password "secret"}
>> A exited normally
>> A --> C: true
>> A --> S: true
>> S exited normally
>> S --> C: #uuid "4c1a6932-7e57-46d8-85bb-8be3c11ee367"
>> C exited normally
=> {C #uuid "4c1a6932-7e57-46d8-85bb-8be3c11ee367",
    S #function[klor.multi.runtime/noop],
    A #function[klor.multi.runtime/noop]}
```

## Higher-order Choreographies

Like functions in Clojure, choreographies in Klor are first-class and can be passed around as values.
They are another example of a multiply-located value, since each involved role will need to carry around its respective projection.
Consider the following higher-order choreography:

```clojure
(defchor chain [A B C] (-> (-> B C) (-> A B) A C) [g f x]
  (g (f x)))
```

`(-> B C)` and `(-> A B)` are the types of the two choreographies `chain` takes as input.
The choreography type constructor `->` in Klor can be arbitrarily nested just like the function type constructor in statically-typed functional languages.
To use `chain` we have to provide the two choreographies as values:

```clojure
(defchor chain-test [A B C] (-> C) []
  (chain [A B C]
         (chor (-> B C) [x] (B->C (B (+ x 10))))
         (chor (-> A B) [x] (A->B (A (* x 10))))
         (A 41)))
```

Klor's special operator `chor` is the choreographic counterpart to Clojure's `fn`: it allows us to create anonymous choreographies.
Currently it is mandatory for every `chor` form to specify its type signature explicitly, though realistically it could be inferred with a more sophisticated type inference algorithm (a la Hindley--Milner).
This is an area for future work.

We can test that everything works as expected, `@(simulate-chor chain-test)`:

```clojure
>> B spawned
>> C spawned
>> A spawned
>> A exited normally
>> A --> B: 410
>> B exited normally
>> B --> C: 420
>> C exited normally
=> {A #function[klor.multi.runtime/noop],
    B #function[klor.multi.runtime/noop],
    C 420}
```

To avoid confusion it is important to make a distinction between a choreographic definition and a concrete choreography.
A choreographic definition defined with `defchor` is technically a **location-polymorphic choreography** that has to be instantiated with concrete roles before it can be used.
It is equivalent in nature to a parametrically-polymorphic function in statically-typed functional languages.
On the other hand, a concrete choreography created with `chor` is not location-polymorphic and does not have to be instantiated before use.
When necessary, a choreographic definition can be explicitly instantiated to get a choreography using the special operator `inst`.
Here's how we could rewrite `chain-test` using only choreographic definitions:

```clojure
(defchor mul [A B] (-> A B) [x]
  (A->B (A (* x 10))))

(defchor add [A B] (-> A B) [x]
  (A->B (A (+ x 10))))

(defchor chain-test [A B C] (-> C) []
  (chain [A B C] (inst add [B C]) (inst mul [A B]) (A 41)))
```

The "inline instantiation" performed when invoking a choreography, such as in `(chain [A B C] ...)`, is just syntax sugar for the `inst` operator.

## Auxiliary Roles

There is one issue unique to choreographic programming when it comes to thinking of choreographies as functions: it is possible for a role to not appear in a choreography's inputs or output, yet still be involved in its implementation.
Take for example the following:

```clojure
(defchor knock [A B] (-> A) []
  (B (println "Knock knock!"))
  (A (println "Who's there?")))
```

Though the type signature doesn't mention `B`, it is still involved in the choreography's body.
So what is the type of `knock` really?
Its true type is in fact `(-> A | B)`, where the roles after the pipe `|` are called **auxiliary roles**.
These are the roles that are not part of the choreography's interface (parameter list or return value) but still participate in its implementation.
In case you missed it, the signature of `chain-test` actually had this exact same feature: its true signature is really `(-> C | A B)`.

Working with higher-order features is therefore slightly more complicated in the choreographic setting.
To see why projection would not work if Klor did not track auxiliary roles, let's take a look at a slightly generalized version of `chain`:

```clojure
(defchor compose [A B C] (-> (-> B C) (-> A B) (-> A C | B)) [g f]
  (chor (-> A C) [x] (g (f x))))
```

`compose` will return the composition of the two choreographies as a new choreography, rather than immediately apply it to a value.
Note the type of its result: `(-> A C | B)`.
It would be an error to leave `B` out of the return value because `B` is what connects the two input choreographies together.
Klor must know this so that it can correctly generate its projection.

As a convenience, top-level choreography type constructors `->` in both `defchor` and `chor` signatures do not need to explicitly list any auxiliary roles.
Klor will automatically infer them from the body of the choreography, which is why `(-> A C)` in the body of `compose` is enough.
However, the auxiliary roles of any nested choreography type constructors `->` must be specified manually and are otherwise assumed to be empty, so the full `(-> A C | B)` is required in the signature.

Here is simple use of `compose` to complete the example:

```clojure
(defchor compose-test [A B C] (-> C) []
  (let [h (compose [A B C] (inst add [B C]) (inst mul [A B]))]
    (C (+ (h (A 40)) (h (A 0))))))
```

To test, `@(simulate-chor compose-test)`:

```
>> A spawned
>> B spawned
>> C spawned
>> A --> B: 400
>> A --> B: 0
>> B --> C: 410
>> A exited normally
>> B --> C: 10
>> B exited normally
>> C exited normally
=> {A #function[klor.multi.runtime/noop],
    B #function[klor.multi.runtime/noop],
    C 420}
```

## Returning Multiple Values

There are cases where we want a choreography to return completely different values at different roles, perhaps because they are part of independent computations happening concurrently.
The standard way of solving this issue in traditional programming languages is to return a single tuple (or record) with multiple fields.
Klor's analogue to this are **choreographic tuples**, its third kind of multiply-located value.
We demonstrate the utility of tuples with the following example:

```clojure
(defn modpow [base exp mod]
  (.modPow (biginteger base) (biginteger exp) (biginteger mod)))

(defchor exchange-key [A B] (-> #{A B} #{A B} A B [A B]) [g p sa sb]
  (pack (A (modpow (B->A (B (modpow g sb p))) sa p))
        (B (modpow (A->B (A (modpow g sa p))) sb p))))
```

`exchange-key` implements the [Diffie--Hellman key exchange protocol](https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange#Cryptographic_explanation) that allows two parties, `A` and `B`, to securely exchange a cryptographic key over a public channel.
The choreography takes as inputs a shared generator `g`, a shared prime `p`, and two secret values, `sa` and `sb`, known only to `A` and `B`, respectively.
Modular exponentiation `a^b mod m` is implemented with the helper function `modpow` that wraps the functionality of Java's `BigInteger` class.

The part to focus on is the choreography's return type.
`[A B]` is a choreographic tuple type containing independent values for `A` and `B`, which is the key they should use to encrypt their communication.
To create a tuple we use the `pack` special operator whose arguments will correspond to the fields of the tuple.
Klor's tuples can contain arbitrary values -- agreements, choreographies or even other tuples.

We can test our implementation with the [example from Wikipedia](https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange#Cryptographic_explanation), `@(simulate-chor exchange-key-1 5 23 4 3)`:

```
>> A spawned
>> B spawned
>> B --> A: 10
>> A exited normally
>> A --> B: 4
>> B exited normally
=> {A [18], B [18]}
```

A caveat to mention is that `A` and `B` will in reality agree on the cryptographic key.
However, this fact is a mathematical property of the Diffie--Hellman algorithm and not something we can derive from the Klor implementation itself.
This is why we return a choreographic tuple and not an agreement type -- the key is never physically communicated, only computed by both roles independently.
Klor projects tuples to plain Clojure vectors which is why the simulator shows `[18]` as the final result.
TODO: More on this later.

Now that we can exchange a cryptographic key, we can also write a choreography that securely communicates a value:

```clojure
(defchor secure [A B] (-> A B) [x]
  (unpack [[k1 k2] (exchange-key [A B] 5 23 (A 4) (B 3))]
    (B (.xor k2 (A->B (A (.xor k1 (biginteger x))))))))
```

To use the keys returned by `exchange-key` we have to destructure the tuple with the `unpack` special operator.
This operator is similar to Clojure's `let` except that the usual vector destructuring syntax is interpreted as tuple unpacking.

To test that the communication is secure, `@(simulate-chor secure-1 42)`:

```
>> A spawned
>> B spawned
>> B --> A: 10
>> A --> B: 4
>> A exited normally
>> A --> B: 56
>> B exited normally
=> {A #function[klor.multi.runtime/noop], B 42}
```

In [Tutorial: Execution](tutorial-05-execution.md) we finally consider the issues of deployment and the Klor--Clojure interface.
