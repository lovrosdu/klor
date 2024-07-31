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
