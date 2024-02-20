# Semantics

Choreographic programming assumes **concurrent execution** of all roles involved in a choreography.
Abstractly, all subexpressions of a choreography are evaluated **out-of-order up to role dependency**.
This means that their evaluation can be arbitrarily interleaved, except that sequentiality is maintained between all expressions located at the same role, including any implicit **communication actions** initiated by the role (sends and receives to/from other roles).
Receives are assumed to be **blocking (synchronous)** while sends can be either blocking or non-blocking.

Practically, the way this kind of execution is achieved is by **projecting** a choreography to a separate Clojure function for each role and executing all of them concurrently via some concurrency mechanism -- multiple threads, processes, nodes, any combination thereof, etc.
Each projection retains only the subexpressions relevant to the particular role and incorporates the necessary communication actions to interact with other roles as specified by the choreography.

## Evaluation and Projection

Below, we describe in detail the meaning of each Klor expression by describing how it is evaluated and what communications between roles it prescribes.
To keep it close with the way it's done in practice, we specify the evaluation semantics **indirectly**, by stating what an expression projects to at each role.
A full treatment of the theory of projection with all of its details and properties is out of the scope of this document, but we try to give a working summary here.

In general, the projection of a Klor expression at a role depends on which of its parts are annotated with that role.
For example, an expression might be projected differently for a role `Ana` depending on whether it is **located** at `Ana`, **only involves** `Ana` in its subexpressions, or **doesn't involve** `Ana` at all.
Additionally, whenever an expression located at `Ana` needs the result of another expression located at `Bob`, the projections of both will have to include communication actions so that `Bob` can send its result to `Ana` and `Ana` can receive it from `Bob`.

Projection works **recursively** through the subexpressions of a Klor expression and applies the above considerations at every step in order to produce code that contains all the necessary expressions and communication actions described by the choreography.
The general idea of the process of projecting an expression for a role is:

- If the expression is **located** at the role, the projection usually retains the expression and arranges any necessary communication actions to receive the values of subexpressions that are located at a different role,
- Otherwise, if the expression **only involves** the role in its subexpressions, the projection is required to evaluate to `nil` but also includes any necessary communication actions to first send the values of the subexpressions to the role of the enclosing expression,
- Otherwise, if the expression **doesn't involve** the role at all, the projection is `nil`.

In the projection examples below we use `send` and `recv` for the internal operators that implement the mentioned communication actions in the projections. `(send '<role> <expr>)` sends the result of `<expr>` to `<role>` and `(recv '<role>)` receives a value from `<role>`.

### Atomic Expressions

An atomic expression must be located at some role `r`.

The projection at `r` is the atomic expression itself and is `nil` at any other role.

For example:

```clojure
(Ana 123)

Ana: 123
Bob: nil
```

### Functions

A function invocation expression `(<op> <expr>*)` invokes any Clojure function.

A function invocation expression must be located at some role `r`.
`<op>` can be an arbitrary expression but is not allowed to be located at a role different from `r` (to prevent having to communicate functions).

The projection at `r` is the invocation expression `(<op> <expr>*)`, with `<op>` and all `<expr>` projected recursively.
If any argument expression `<expr>` is located at a role different from `r`, its result is communicated from that role to `r`.
`r` receives the arguments in a left-to-right order.

The projection is required to evaluate to `nil` at any role other than `r`.

For example:

```clojure
;; - Ana determines the value of x and sends it to Bob,
;; - Bob receives the value, increments it and sends it back to Ana,
;; - Ana receives the final value and prints it out.

(Ana (println (Bob (inc Ana/x))))

Ana: (println (do (send 'Bob x) (recv 'Bob)))
Bob: (do (send 'Ana (inc (recv 'Ana))) nil)

;; - Ana and Bob independently send the values of x and y to Cal,
;; - Cal receives the two values and adds them together.

(Cal (+ (Ana x) (Bob y)))

Ana: (do (send 'Cal x) nil)
Bob: (do (send 'Cal y) nil)
Cal: (+ (recv 'Ana) (recv 'Bob))
```

### `do` Special Operator

A `(do <expr>*)` expression composes multiple choreographic expressions in sequence.
This kind of "choreographic sequencing" does not impose a total order on the evaluation of expressions: they are still executed out-of-order up to role dependency.

A `do` expression is not required to be located at a role.

The projection at any role `r` is a `do` expression containing the projections of all subexpressions `<expr>`.
As a consequence, the projection of the last expression is what the projected `do` evaluates to.
An empty `do` expression projects to `nil` at any role.

If a `do` expression is located at a role `r` and its last subexpression `<expr>` (if any) is located at a role different from `r`, its result is communicated from that role to `r`.

For example:

```clojure
;; Ana and Bob independently execute some expressions without communication.

(do (Ana 123) (Bob 456))

Ana: (do 123 nil)
Bob: (do 456)

;; The final expression in a `do` is communicated if necessary.

(Ana (do (Bob 123)))

Ana: (recv 'Bob)
Bob: (do (send 'Ana 123) nil)
```

For convenience purposes, role expressions are treated as implicit `do` blocks when they appear in evaluated contexts, which is the reason why they can enclose more than one expression: `(<role> <expr>*)`.
Like role-qualified symbols, this makes Klor code more readable and reduces the level of nesting:

```clojure
;; The following two expressions are equivalent in their effects.

(Cal (f) (Bob (g) (Ana 1)))
(Cal (do (f) (do (Bob (g) (Ana 1)))))

Ana: (do (send 'Bob 1) nil)
Bob: (do (send 'Cal (do (g) (recv 'Ana))) nil)
Cal: (do (f) (recv 'Bob))
```

### `let` Special Operator

A `(let [<binding>*] <body-expr>*)` expression binds values to names at multiple different roles.

A `let` expression is not required to be located at a role.

Each `<binding>` is of the form `<binder> <init-expr>`.
A `<binder>` is anything that is accepted by Clojure's own `let` -- a plain symbol or a sequential or associative destructuring pattern.
Role expressions are allowed within binders and a binder must be located at a role, but it is forbidden for parts of a binder to be located at different roles.
Therefore, it is only ever useful for binders to contain a single top-level role expression (if any).
Role-qualified symbols come in handy as binders.

The projection at any role `r` is a `let` expression containing all binders located at `r` (if any).
As a consequence, multiple roles are allowed to bind the same name, as they are distinguished by their location.
Binding the same name at the same location multiple times has the usual semantics of variable shadowing.

All `<init-expr>` corresponding to any binders are projected recursively.
If any initialization expression is located at a role different from `r`, its result is communicated from that role to `r` before it is bound at the destination.
`r` receives the values in the order in which they are bound.

The body of the `let` forms an implicit `do` block and is projected recursively.

For example:

```clojure
;; Each initialization expression can involve a communication.

(Ana (let [x (Bob 123)] x))

Ana: (let [x (recv 'Bob)] x)
Bob: (let [_ (do (send 'Ana 123) nil)] nil)

;; A `let` can be unlocated.

(let [Bob/x (Ana 123)] Bob/x)

Ana: (let [_ (do (send 'Bob 123) nil)] nil)
Bob: (let [x (recv 'Ana)] x)

;; A `let`'s body is an implicit `do` block so its result is communicated.

(Ana (let [Bob/x 123] Bob/x))

Ana: (let [_ (do (send 'Bob 123) nil)] (recv 'Bob))
Bob: (let [x (recv 'Ana)] (send 'Ana x) nil)

;; Some roles might not have any binders.

(Cal (let [Ana/x (Bob 123)] (f Ana/x)))

Ana: (let [x (recv 'Bob)] (send 'Cal x) nil)
Bob: (let [_ (do (send 'Ana 123) nil)])
Cal: (f (recv 'Ana))
```

### `if` and `select` Special Operators

An `(if <cond> <then> <else>?)` expression allows for branching the control flow of a choreography.

An `if` expression must be located at a role `r`.

The projection at `r` is the expression `(if <cond> <then> <else>)`, with `<cond>`, `<then>` and `<else>` projected recursively.
If `<else>` is not given, it defaults to `nil`.
If any of `<cond>`, `<then>` or `<else>` are located at roles different from `r`, their result is communicated from those roles to `r` when necessary.

The projection is required to evaluate to `nil` at any other role.

However, a special consideration applies when a role that is not `r` is involved in any of the two branches of the conditional.
Because the decision about which branch was taken is only known to `r`, any other role participating in any of the two branches has to be told what the decision was so that it can pick the correct branch.
This is known as the **problem of knowledge of choice** and is handled via **selections** -- actions that allow `r` to signal to other roles which branch to take by communicating constants called **labels**.

A selection to one or more roles is done with a `(select [<label> <role>+] <expr>*)` expression.

A `select` expression is not required to be located at a role.

`<label>` can be any literal (constant) value such as a Clojure number, keyword, symbol, etc., and must be located at a role `s`.

The projection at `s` is a special `choose` expression that is like a `send` but for labels.

The projection at any `<role>` (which is not allowed to be `s`) is a special `offer` expression.
An `offer` expression acts like a `recv` but for a label, except that it also dispatches on the label's value to select a branch.
The various branches are part of the `offer` expression and are assembled from the bodies of `select` expressions found within both branches.

The body of the `select` forms an implicit `do` block and is projected recursively.

In order for an `if` to be projectable when multiple roles are involved in its body, its two branches need to contain appropriate selections for all roles that are not `r`.
During projection, all `offer` terms found in the branches are **merged** together to form the final projection.
The process of merging is technically involved and is out of the scope of this document.
However, in case merging fails due to inadequate knowledge of choice, Klor will report an error during compilation.

For example:

```clojure
;; A completely local `if`.

(Ana (if (int? x) (inc x) x))

Ana: (if (int? x) (inc x) x)
Bob: nil

;; An `if` requiring a selection for `Bob`.

(Ana (if cond
       (select [ok Bob] (Bob (println "hello")) 1)
       (select [ko Bob] (Bob (println "world")) 2)))

Ana: (if cond
       (do (choose 'Bob 'ok) 1)
       (do (choose 'Bob 'ko) 2))
Bob: (offer Ana
       ok (do (println "hello") nil)
       ko (do (println "world") nil))
```

### `dance` Special Operator

A `(dance <name> [<role>+] <expr>*)` expression invokes a previously defined choreography.

A `dance` expression is not required to be located at a role.

`<name>` is a symbol naming the choreography to invoke.
A choreography is allowed to invoke itself recursively.

The role vector `[<role>+]` **instantiates** the choreography's role parameters with a sequence of some role parameters currently in scope.
**Aliasing is not allowed**, i.e. each role parameter of the choreography must be instantiated with a separate role parameter from the current scope.

The projection at any role `r` that is present within the role vector is the invocation of a particular projection of the target choreography, the one corresponding to the choreography's role parameter that `r` instantiates.
The return value of that invocation is the return value of the specific projection.
If an `<expr>` that initializes a parameter belonging to `r` is located at a role different from `r`, its result is communicated from that role to `r`.

The projection is required to evaluate to `nil` at any other role.

### Non-list Compound Expressions

Non-list compound expressions, `[<expr>*]`, `{<pair>*}` and `#{<expr>*}`, construct Clojure vectors, maps and sets, respectively.
`<pair>` is a sequence of two expressions: `<expr> <expr>`.

A non-list compound expression must be located at a role `r`.

The three expression types are projected as if they were calls to the functions `vector`, `hash-map` and `hash-set`.
The projection at `r` is the invocation of the appropriate function with all argument expressions `<expr>` projected recursively.
If any argument expression `<expr>` is located at a role different from `r`, its result is communicated from that role to `r`.

The projection is required to evaluate to `nil` at any other role.

One peculiarity of "code is data" and unordered collections (maps and sets) as expressions is the fact that the order in which communications are done depends on the order of the expressions in the collection, which is in general non-deterministic.
The order is guaranteed to be consistent for a given fixed collection object, but is not guaranteed to be the same between two different collection objects even if they compare equal (let alone between equal collections that are part of multiple executions of the same or different Clojure processes, etc.).
For that reason, Klor will issue a warning whenever a map or set expression contains elements located at a role different from `r`.

```clojure
;; No communications are done.

(Ana [1 2 3])

Ana: [1 2 3]
Bob: nil
Cal: nil

;; - Bob, Cal and Dan send 1, 2 and 3 to Ana,
;; - Ana receives the values and constructs the vector [1 2 3].

(Ana [(Ana 1) (Bob 2) (Cal 3)])

Ana: [1 (recv 'Bob) (recv 'Cal)]
Bob: (do (send 'Ana 2) nil)
Cal: (do (send 'Ana 3) nil)

;; A warning is issued when the order of communications is non-deterministic.

(Ana #{(Ana 1) (Bob 2) (Cal 3)})
>> WARNING: Order of communications within a set is non-deterministic, use an explicit `let` instead

Ana: (hash-set 1 (recv 'Cal) (recv 'Bob))
Bob: (do (send 'Ana 2) nil)
Cal: (do (send 'Ana 3) nil)
```
