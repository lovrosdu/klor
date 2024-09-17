# Tutorial: Basics

## Setup

Throughout the tutorial we will explore a number of examples.
The reader is encouraged to follow along and try things out at the REPL.
All examples will assume that Klor has been loaded and imported into the current namespace as if by the following:

```clojure
(require
 '[klor.core :refer :all]
 '[klor.simulator :refer [simulate-chor]])
```

## First Choreography

Klor choreographies are similar to normal Clojure functions: they are pieces of behavior that take a number of inputs and produce an output.
Unlike functions however, choreographies describe the behavior of multiple independent roles executing **concurrently**, including their interactions and local computations.

We define choreographies using the `defchor` macro, reminiscent of Clojure's `defn`.
Here is a trivial 1-role choreography to get acquainted with `defchor`'s syntax:

```clojure
(defchor greet [A] (-> A) []
  (A (println "Hello world")))
```

The above defines a choreography called `greet` taking no parameters (`[]`).
The two additional elements between the name and the parameter vector are the **role vector** and the **choreographic type signature**.
The role vector `[A]` specifies that the choreography involves just a single role, named `A`.
The choreographic type signature `(-> A)` tells Klor that `greet` is a choreography taking no inputs and producing an output located at `A`.

Klor tracks the locations of values within a choreography using its lightweight **choreographic type system** in order to ensure the correctness of choreographic code.
The type system does not restrict the "structure" of a value, e.g. whether it is an integer or a string, but rather its **location**, i.e. whether it is located at role `A` or role `B`.
Therefore, Clojure's usual dynamic typing of values is completely orthogonal and unaffected.

The expression `(A ...)` in the body of the choreography is a **role expression**.
It tells Klor that all literals and free names (referring to plain Clojure vars) in its body -- `"Hello world"` and `println` in this case -- should be treated as being located at `A`.
We also say that they have been **lifted** to A.
The end result is that `(A (println "Hello world"))` specifies an invocation of `println` performed at `A`.
Generally, a choreographic programming language needs a way to specify where values are located and where computation takes place.
Klor's role expressions provide a syntactically unobtrusive way of doing this.

Aside from a handful of Klor-specific special operators like the mentioned role expressions, Klor can freely invoke existing Clojure code and use most of the standard Clojure special operators such as `do`, `let`, etc.
Certain operators do require special considerations within a choreographic context however, and some are not supported at all.
See [Reference: Language](./reference-01-language.md) for a full list of special operators supported by Klor, though most of them ones will be covered by the tutorial.

Behind the scenes, the `defchor` macro will type check the choreography and, assuming everything is ok, produce a projection for each of the roles involved.
Invoking the individual projections from Clojure will be covered later in [Tutorial: Execution](./tutorial-05-execution.md), so until then we will make use of Klor's **simulator** instead.
The simulator allows us to test a choreography from within a single Clojure process by automatically executing each role's projection on a separate thread.
This is a highly useful tool during development and debugging.

To test `greet` we evaluate `@(simulate-chor greet)`:

```
>> A spawned
>> A: Hello world
>> A exited normally
=> {A nil}
```

(Throughout the tutorial, the standard output and the result of an evaluation will be shown prefixed with `>>` and `=>`, respectively).
The simulator will show the output of each role prefixed with its name for easier reading, and will also produce some debugging output.
The return value is a map of the results produced by each role (i.e. each projection).
In this case `A` printed `Hello world` and produced `nil` as its result.

Let us now extend the choreography to two roles, `A` and `B`, where each one will print a part of the message (though in some non-deterministic order due to the concurrent execution of roles):

```clojure
(defchor greet-2 [A B] (-> B) []
  (A (println "Hello"))
  (B (println "World")))
```

The role vector is now `[A B]` since we have two roles.
The choreographic type `(-> B)` says that the choreography (still) takes no inputs but produces a single output located at `B`, because `(B (println "World"))` is the final form in the choreography's body.
To test we evaluate `@(simulate-chor greet-2)`:

```
>> A spawned
>> B spawned
>> A: Hello
>> A exited normally
>> B: World
>> B exited normally
=> {A #function[klor.runtime/noop], B nil}
```

The two roles independently print their messages and exit as expected.
Keep in mind that we could observe different orderings of the messages depending on how the execution is scheduled.
Also note the projections' results: `B` returned `nil`, which is the result of the choreography's final `println`, but `A` returned the special value `klor.runtime/noop`, which marks the absence of a value for `A` at the choreographic level.
In other words, even though `greet-2` involves both role `A` and `B`, the result of invoking it produces only a value at `B`.
We will see later how values can be returned at multiple roles simultaneously.

## Communication

While the two roles in the previous example do execute concurrently, they have no interaction with one another.
Here is a 2-role choreography where we perform a communication for the first time:

```clojure
(defchor simple [A B] (-> B) []
  (A->B (A 5)))
```

The special single-arrow communication operator `A->B` allows us to transfer a value from one role to another.
To be able to do so, the operator's argument must be located at the source (the left-hand side of the arrow) and the result will be a value located at the destination (the right-hand side of the arrow).
In this case, `A` communicates the value `5` to `B`.
Had `5` not been located at `A`, Klor would've reported an error.

If we now evaluate `@(simulate-chor simple)`, we will notice the simulator conveniently reporting the communication between the roles:

```
>> A spawned
>> B spawned
>> A --> B: 5
>> B exited normally
>> A exited normally
=> {A #function[klor.runtime/noop], B 5}
```

Let us modify the choreography so that it takes the value to communicate from `A` as input:

```clojure
(defchor simple-2 [A B] (-> A B) [x]
  (A->B x))
```

The type signature `(-> A B)` now specifies that the choreography takes a single input at `A` and produces an output at `B`.
Note how we don't need a role expression around `x` in the body since it is not a free name and Klor already knows it is located at `A` due to the choreography's signature.
Klor won't complain if you use a role expression anyway, even if the role doesn't match the actual location of the argument:

```clojure
(defchor simple-2 [A B] (-> A B) [x]
  (A->B (B x)))
```

Remember, role expressions only affect the type (i.e. location) of literals and free names; everything else is automatically **inferred** by Klor during type checking and is not affected by a role expression.
In practice, you will want to avoid superfluous role expressions and try to push them as inward as possible for readability reasons (to better signal where local computation takes place).

If we now wish to test `simulate-2`, we must give `simulate-chor` an additional argument, implicitly located at `A`.
When a choreography takes arguments, the simulator will take care of correctly distributing the parameters to their respective locations.
Evaluating `@(simulate-chor simple-2 "Hello")` gives us:

```
>> A spawned
>> B spawned
>> A exited normally
>> A --> B: "Hello"
>> B exited normally
=> {A #function[klor.runtime/noop], B "Hello"}
```

As mentioned before, it is `defchor` that will produce the projections and ensure that `A` contains a send and `B` contains a matching receive.
All of the necessary code is generated automatically and does not in any way assume it will be running in the simulator.
The simulator is just a convenience for local testing that schedules each projection on a separate thread and wires them up to communicate over in-memory `core.async` channels.
Generally, Klor permits any value as an argument to a communication, with the assumption that the underlying transport will know how to (de)serialize it.
Transport customization is covered later in [Tutorial: Execution](./tutorial-05-execution.md).

## More Examples

Here's an example of a "remote increment" choreography.
`A` will send a number to `B` who will increment it and return it to `A`:

```clojure
(defchor remote-inc [A B] (-> A A) [x]
  (B->A (B (inc (A->B x)))))
```

Note the role expression `(B ...)` which serves to lift `inc` to `B`.
To test we can evaluate `@(simulate-chor remote-inc 5)`:

```
>> A spawned
>> B spawned
>> A --> B: 5
>> B exited normally
>> B --> A: 6
>> A exited normally
=> {A 6, B #function[klor.runtime/noop]}
```

We could generalize our choreography to arbitrary functions at `B` with an additional parameter `f`:

```clojure
(defchor remote-invoke [A B] (-> B A A) [f x]
  (B->A (f (A->B x))))
```

To test decrementing with `dec`, `@(simulate-chor remote-invoke dec 5)`:

```
>> A spawned
>> B spawned
>> A --> B: 5
>> B exited normally
>> B --> A: 4
>> A exited normally
=> {A 4, B #function[klor.runtime/noop]}
```

We could go a step further and generalize the invocation to an arbitrary number of arguments, though the single parameter `xs` has to be a vector as choreographies don't support variadic arguments like Clojure functions do:

```clojure
(defchor remote-apply [A B] (-> B A A) [f xs]
  (B->A (B (apply f (A->B xs)))))
```

To test calculating a sum, `@(simulate-chor remote-apply + [1 2 3])`:

```
>> A spawned
>> B spawned
>> A --> B: [1 2 3]
>> B exited normally
>> B --> A: 6
>> A exited normally
=> {A 6, B #function[klor.runtime/noop]}
```

In [Tutorial: Sharing Knowledge](tutorial-03-sharing-knowledge.md) we introduce one of Klor's big ideas, demistify some of its syntax sugar and explore more control flow constructs.
