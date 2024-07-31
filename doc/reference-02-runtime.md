# Reference: Runtime

## Execution Model

Choreographic programming assumes the **concurrent execution** of all roles in a choreography.
Abstractly, all subexpressions of a choreography are evaluated **out-of-order up to role dependency**.
This means that their evaluation can be arbitrarily interleaved, except that sequentiality is maintained between all expressions located at the same role, including any **communication actions** initiated by the role (sends and receives to/from other roles).
Receives are assumed to be **blocking (synchronous)** while sends can be either blocking or non-blocking.

## Projection

The execution model is implemented by **projecting** a choreography to a separate Clojure function for each role and executing all of them concurrently via some concurrency mechanism -- multiple threads, processes, nodes, any combination thereof, etc.
Each projection retains only the subexpressions relevant to the particular role and incorporates the necessary communication actions to interact with other roles as specified by the choreography.

A full treatment of the theory of projection with all of its details and properties is out of the scope of this document, but we try to give a working summary here.
For more details the [Introduction to Choreographies](https://doi.org/10.1017/9781108981491) book is a good starting point.

In general, the projection of a Klor expression for a role depends on the expression's choreographic type and the roles it **mentions**.
An expression is projected differently for a role `r` depending on whether it **has a result** for `r` (involves `r` in its type), **only mentions** `r` (only involves `r` in its subexpressions), or **doesn't mention** `r` at all.
Projection works **recursively** through the subexpressions of a Klor expression and applies these considerations at every step in order to generate code that contains all of the necessary expressions and communication actions described by the choreography.
The rough idea is:

- If the expression **has a result** for `r`, the projection generates code to yield that result, including any necessary communication actions between the roles. All appropriate subexpressions are projected recursively.
- Otherwise, if the expression **only mentions** `r` (in its subexpressions), it is projected to code that ultimately evaluates to the special `klor.runtime/noop` value representing the absence of a choreographic value, but first carries out all of the necessary computation required by the recursive projection of its subexpressions.
- Otherwise, if the expression **doesn't mention** `r` at all, the projection is just the `klor.runtime/noop` value.

Special operators are the base cases of the recursive procedure.
A few of the more important ones are described briefly:

- `copy` projects to corresponding send and receive actions at the source and destination, respectively.
- `chor` projects to Clojure anonymous functions, each representing a projection of the anonymous choreography.
- `do` projects to a Clojure `do` with each expression in its body projected recursively. The `do` is effectively "pulled apart" and its pieces are distributed among the roles. This means that it does **not** maintain sequential execution of its subexpressions across roles but only within each role. We call this **choreographic sequencing**.
- `if` projects to a Clojure `if` for all of the roles that have a result for the guard expression. If a role does not have a result for the guard, it cannot participate in either of the branches.

## Invoking Projections

The `(klor.runtime/play-role <conf> <chor> <arg>*)` function invokes a particular projection of a Klor choreography.

`<conf>` is called the **role configuration**.
It is a map that describes which role's projection to invoke and how to communicate with other roles.
Its structure is the following:

```clojure
{:role <role>
 :send <send-fn>
 :recv <recv-fn>
 :locators {<role-1> <loc-1> ... <role-n> <loc-n>}}
```

`<role>` is an unqualified symbol that names the role to play.
It has to match one of the role parameters of the choreography (as they appear in its role vector).

`<send-fn>` and `<recv-fn>` form the **transport** that defines how values are actually communicated to/from other roles.
When a role has to send or receive a normal value, Klor will call into one of these two functions:

- `<send-fn>`: its parameter list is `(<loc> <value>)`; it should send the given `<value>` and return; its result is ignored.
- `<recv-fn>`: its parameter list is `(<loc>)`; it should receive a value and return it.

The transport functions accept a **locator** `<loc>` as their first argument.
Locators are supplied by the user via the `:locators` map (mapping each role parameter to its locator) and are forwarded by Klor to the transport functions.
A locator is an arbitrary Clojure value representing the role to send to/receive from and should contain the information necessary for the transport functions to carry out their effects.
Practically speaking, locators will most often be mechanisms such as `core.async` channels, TCP sockets, etc.
If the played role doesn't communicate with a particular role, that role's locator can be left out.

If a choreography invokes another choreography, Klor will make sure to properly "thread" the role configuration, taking into account the way the choreography was instantiated.
Any communication actions performed by the invoked choreography will use the same transport functions and locators provided at the top.
The Klor user only ever has to worry about the "top-level view" and getting the initial call to `play-role` right.

`<chor>` should be the value of a var defined as a choreography with `defchor`.
Its structure is left unspecified and is an implementation detail.

All supplied arguments `<arg>*` are provided to the projection.
Their number and structure is dependent on the played role `r` and is derived from the choreography's signature in an **erasure style**:

- Parameters that do not mention `r` at all are erased from the projection's parameter list.
- Parameters of agreement type are retained and assumed to be arbitrary Clojure values. Note that it is the **user's responsibility** to provide the same value for an agreement parameter to all projections.
- Parameters of tuple or choreography type cannot be provided directly, so choreographies that take them are not directly usable with `play-role`. This is to reduce the possibility of user mistakes. Instead, the user can create a "wrapper choreography" that takes only parameters of agreement type and constructs the necessary tuples and choreographies to pass to the choreography of interest.
Because this is done within the confines of the type system it is guaranteed that the constructed values will follow Klor's usual assumptions.

The return value of `play-role` is derived from the return value of the choreography in a similar fashion:

- If the type of the return value does not mention `r` at all, `klor.runtime/noop` is returned.
- If the return value is of agreement type, the value is returned as is.
- If the return value is of tuple type, a Clojure vector is returned whose elements are derived recursively. However, elements of the tuple that do not mention `r` at all are erased like in the projection's parameter list and so do not appear in the vector.
- If the return value is of choreography type, a Clojure function is returned. Its parameter list and return value are derived recursively according to the rules above. The returned Clojure function effectively represents a projection of the returned choreography and has the role configuration "baked in". When called, it will use the same transport that was initially provided to `play-role`.

## TCP Transport

The `klor.sockets` namespace provides a simple transport based on standard [`java.nio`](https://docs.oracle.com/javase/8/docs/api/java/nio/package-summary.html) TCP sockets and the [Nippy](https://github.com/taoensso/nippy) serialization library.

The function `(klor.sockets/wrap-sockets <config> <sockets> & {:as <opts>})` can be used to construct a role configuration using the TCP transport, suitable as an argument to `play-role`:

- `<config>` is an existing role configuration. Its `:send`, `:recv` and `:locators` keys will be overriden and returned as a new configuration.
- `<sockets>` is a map of role parameters to Java [`SocketChannel`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/SocketChannel.html) objects. The sockets must be provided by the user and are used to send/receive data to/from other roles.
- `<opts>` is a map of additional options. The `:log` option controls logging of socket sends and receives. If set to a boolean, the logging is enabled or disabled. If set to `:dynamic` (the default), the logging depends on the boolean value of the dynamic variable `klor.sockets/*log*` (false by default).

## Simulator

The Klor simulator provides a simple way of executing choreographies within a single Clojure process.
This comes in handy during development and debugging.
Technically, the simulator just executes each projection on a separate thread and plugs in a transport based on in-memory `core.async` channels.

The `(klor.simulator/simulate-chor <chor> <arg>*)` function is used to simulate the execution of a choreography.
`<chor>` is the value of a var defined as a choreography with `defchor`, just like in `play-role`.
The number and structure of the arguments `<arg>*` follows the signature of the choreography, without erasure like in `play-role`.
`simulate-chor` will automatically distribute the arguments to the correct projections.

Like with `play-role`, it is not possible to directly use `simulate-chor` with a choreography that makes use of parameters of tuple or choreography type.
A "wrapper choreography" must be used instead in those cases.

## TODO Projection Options

The `(klor.opts/with-opts <opts> <expr>*)` macro can be used to alter the way Klor projects any `defchor` forms in its body.

`<opts>` is an expression evaluated in the global environment at macroexpansion-time and should yield a Clojure map.
The currently supported options are:

- `:verify` -- a nested map of options to control verification
  - `:agreement` -- a value to control **agreement verification**
  - `:signature` -- a value to control **signature verification**

## TODO Dynamic Verification

While the type system guarantees that agreement is propagated correctly, issues can arise during execution due to violations at the interface between Clojure and Klor.
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

### Agreement Verification

**Agreement verification** adds dynamic checks to verify that values of agreement type (with at least 2 roles) are equal between participants as expected.
There are two situations where this is useful: top-level agreement and unsafe agreement.

#### Top-level Agreement

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

#### **Unsafe agreement**

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

### Signature Verification

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
