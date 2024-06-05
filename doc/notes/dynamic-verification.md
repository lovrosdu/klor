# Execution

Klor's runtime and projection have been updated to handle the new operators and choreographic types introduced with static verification.
As before, `defchor` generates one projection per role and the user's interface to invoking them from Clojure is the `play-role` function.

To use `play-role` and provide arguments to a projection the user must know the projection's signature, which is derived from the choreography in the usual "**erasure style**".
In particular, for the projection at `r`:

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

# Dynamic Verification

While the type system guarantees that agreement is propagated correctly at all times, issues can arise during execution due to violations at the interface between Clojure and Klor.
For example, it is possible for a user to make mistakes when invoking the projections using `play-role` or for the transport layer to misbehave and interfere with agreement.
To catch these issues we've added the option of enabling a series of dynamic checks implemented via code instrumentation.

The dynamic checks are enabled using a new `with-opts` macro that acts as a general interface for specifying options to the Klor compiler.
The macro should be wrapped around one or more `defchor` forms to affect their compilation.
For example:

```clojure
(with-opts <opts>
  (defchor ...)
  (defchor ...))
```

`<opts>` is an expression evaluated in the global environment at macroexpansion-time and should yield a Clojure map.
The currently supported options are:

- `:verify` -- a nested map of options to control verification
  - `:agreement` -- a value to control **agreement verification**
  - `:signature` -- a value to control **signature verification**

The exact meaning of the options is explained further below.

## Agreement Verification

**Agreement verification** adds dynamic checks to verify that values of agreement type (with at least 2 roles) are equal between participants as expected.
There are two situations where this is useful: top-level agreement and unsafe agreement.

### Top-level Agreement

**Top-level agreement** refers to parameters of agreement type in a choreography whose projections are invoked from Clojure using `play-role` (a so-called *top-level choreography* as it sits at the boundary between Klor and Clojure).

Normally, it would be possible for different `play-role` invocations to provide mismatched values for such agreement parameters, which would lead to unexpected errors at run-time.
With agreement verification however, the code is instrumented to check that agreement holds.
At run-time, this will cause the roles participating in the choreography to communicate between each other to verify the agreement.
If the agreement is violated, errors will be raised at all roles.
The value of the `:agreement` option determines the type of verification.
For example:

```clojure
(with-opts {:verify {:agreement true}}
  (defchor my-chor [A B C] (-> #{A B C} #{A B C}) [x]
    ;; <everyone-verifies-agreement-for-x>
    x))
```

When set to `true` as above, the roles perform a **decentralized** check where all pairs of roles exchange their copies of the value and then perform the equality check individually.
This style of verification induces less latency (as all communications occur in parallel) but requires more messages to be exchanged, needing `O(n^2)` communications for `n` roles:

```clojure
;; Run concurrently (possibly on different machines)
(play-role my-chor {:role 'A ...} 123)
(play-role my-chor {:role 'B ...} 123)
(play-role my-chor {:role 'C ...} 456) ; Violate agreement for `x`
```

```clojure
A --> B: 123 ; A sends its copy to B
B --> A: 123 ; B sends its copy to A
A --> C: 123 ; A sends its copy to C
B --> C: 123 ; B sends its copy to C
C --> A: 456 ; C sends its copy to A
C --> B: 456 ; C sends its copy to B
B exited abruptly: Values of an agreement differ: x, [123 123 456]
C exited abruptly: Values of an agreement differ: x, [123 123 456]
A exited abruptly: Values of an agreement differ: x, [123 123 456]
```

Alternatively, if `:agreement` is set to a symbol naming a particular role, a **centralized** approach is used where the given role acts as the "checker".
All other roles send their values to the checker who checks for equality and send back the outcome.
This style has higher latency but requires less messages, resulting in `O(n)` communications:

```clojure
(with-opts {:verify {:agreement 'A}}
  (defchor my-chor [A B C] (-> #{A B C} #{A B C}) [x]
    ;; <A-verifies-agreement-for-x>
    x))
```

```clojure
B --> A: 123           ; B sends its copy to the checker
C --> A: 456           ; C sends its copy to the checker
A --> C: false         ; C receives the result from the checker
A --> B: false         ; B receives the result from the checker
A --> B: [123 123 456] ; on error, also receive the data to report
A --> C: [123 123 456]
A exited abruptly: Values of an agreement differ: x, [123 123 456]
B exited abruptly: Values of an agreement differ: x, [123 123 456]
C exited abruptly: Values of an agreement differ: x, [123 123 456]
```

### **Unsafe agreement**

We have added a new special operator called `agree!` whose purpose is similar to the `unsafe` keyword in languages like Rust.
`agree!` can be used to convince Klor that a set of expressions at different roles actually have the same value, but **without** performing any communications.
This is useful in situations where the user is certain agreement exists but cannot prove it within the choreographic framework.
The expressions have to be of disjoint agreement types and the result of `agree!` is an agreement among the union of the roles.

For example, the final agreement on the key achieved by the Diffie--Hellman key exchange protocol is a mathematical property of the algorithm and cannot be captured with the standard agreement propagation rules.
Using `agree!` however, it is possible to still incorporate this fact into the choreography.
When agreement verification is enabled it will be checked at run-time for added safety:

```clojure
(with-opts {:verify {:agreement true}}
  (defchor exchange-key [A B] (-> #{A B} #{A B} A B #{A B}) [g p sa sb]
    (agree! (A 123) ; Violate agreement
            (B (modpow (A->B (A (modpow g sa p))) sb p)))))
```

```clojure
;; Run concurrently
(play-role exchange-key {:role 'A ...} 4 23 4)
(play-role exchange-key {:role 'B ...} 4 23 3)
```

```clojure
...
A --> B: 123
B --> A: 18
B exited abruptly: Values of an agreement differ: (agree! ...), [123 18]
A exited abruptly: Values of an agreement differ: (agree! ...), [123 18]
```

Agreement verification is disabled by default and is generally useful to enable only in scenarios like the above -- either at the boundary between Klor and Clojure (i.e. in top-level choreographies that act as entrypoints) or to double check unsafe agreement.
All other agreement is ensured through the guarantees of Klor's type system.

## Signature Verification

Klor aims for the same REPL-based interactive development style familiar to Clojure developers, so redefining a choreography is naturally supported by Klor.
However, a problem arises when a choreography is redefined with a type signature different from its previous definition, as that violates the assumptions of already-compiled choreographies that depend on it.
Executing such a depending choreography might lead to internal exceptions at best or silent communication mismatches and deadlocks at worst.

Ensuring that the type signatures of all choreographies present in the system are compatible would require a global analysis and a compilation unit much coarser than just a single `defchor` form, which would go against the Lispy spirit of Klor and significantly harm its interactivity, as well as require constant recompilation on every change.
Instead, when a choreography is redefined with a different type signature, Klor will warn the user with a message to let them know they should re-evaluate any depending choreographies.

But if even more safety is desired, **signature verification** can be enabled to instrument each call site of a choreography with an automatic check that its type signature is the same as when the call was compiled.
If not, errors will be raised at all roles.

Signature verification can be enabled on a per-choreography basis using the `with-opts` macro with `{:verify {:signature true}}` or, more convenient for development, globally with the `alter-opts!` function:

```clojure
(alter-opts! (partial merge-with merge) {:verify {:signature true}})
```

As an example, consider the `get-token` choreography that calls into `auth`:

```clojure
(defchor auth [C A] (-> C #{C A}) [creds]
  ...)

(defchor get-token [C S A] (-> C C) [creds]
  ... (auth [C A] creds) ...)
```

If we redefine `auth` with a different type signature, Klor will warn the user:

```clojure
(defchor auth [C S A] (-> C #{C S A}) [creds]
  ...)

;; WARNING: Signature of #'example/auth changed:
;;   was (forall [C A] (-> C #{C A} | 0)),
;;   is (forall [C S A] (-> C #{C S A} | 0));
;; make sure to recompile dependents
```

With signature verification enabled, running any of the projections of `get-token` will now fail with a descriptive error message:

```clojure
(play-role get-token {:role 'C ...} <creds>)

;; C exited abruptly: Signature of #'example/auth differs from before:
;;   was (forall [C A] (-> C #{C A} | 0)),
;;   is (forall [C S A] (-> C #{C S A} | 0));
;; make sure to recompile
```

By default, signature verification is disabled and is intended to be enabled only during development.
