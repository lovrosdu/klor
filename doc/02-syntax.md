# Syntax

Being embedded in Clojure, a Lispy language, the "surface syntax" of Klor is naturally based on S-expressions. We explain the subset of S-expressions that are valid Klor expressions bottom-up.

## Basic expressions

Every Clojure expression is an expression in Klor, including symbols, numbers, booleans, vectors, maps, sets, lists, functions and their applications etc. Similarly, every Clojure type is a type in Klor, checked dynamically by the Clojure runtime system.

## Role expressions

Every basic expression in Klor is located at a role. For instance:

```
;; Value 4 is located at role Ana
(Ana 4)
```

In general, the syntax of role expressions is as follows:

```
(<role> <expr>*)
```

Some more examples:

```
(Ana 4)
(Ana 4 5 6)
(Ana [4 5 6])
(Ana (inc 9))
(Ana (println "foo"))
```

## Choreographic expressions

A choreographic expression is an expression that is capable of involving more than one role (i.e., it consists of sub-expressions at different locations). This allows for performing communications between roles. 

There are two classes of choreographic expressions: non-special forms and [special forms](https://clojure.org/reference/special_forms) for control flow.

### Non-special forms

For any non-special operator `f`, if `(f e1 ... en)` is located at `q`, and if `ei` is located at `p`, then there is a communication of the value `vi` of `ei` from `p` to `q`. When all communications have happened, `q` computes `(f v1 ... vn)`. For instance:

```
;; Ana's 4 is communicated from her to Bob.
(Bob (Ana 4)))

;; Bob's value of x is communicated from him to Ana. Then Ana increments that value.
(Ana (inc (Bob x)))

;; Ana's and Bob's values are communicated from them to Cal, who adds them.
(Cal (+ (Ana 4) (Bob 5)))
```

In general, the syntax of communications for non-special forms is as follows:

```
(<role> (... (<role> ...) ...))
```

For special forms, the communication rules follow the same principle, but their special syntax need to be treated specially.

### Special forms

Just as special forms in Clojure, special forms in Klor have special evaluation rules.

   - If
       - c@a, t@a, e@a: local, a to outer context
       - c@a, t@b, e@b: choreo, a to b within if, b to outside
       - c@a, t@b, e@c: ,... error. ...
   - Do
       - (do e1 e2 ... en@rn): free for all, rn to outer
       - choreographic sequencing
   - Let
       - (let [v1@p1 e1@q1 ...] e@r ...):
           - if pi = qi: i-th binding is local, otherwise communication
           - last expression in body to outer
           - choreographic sequencing
   - Select
       - like do, used for projection

## Embedding choreographies in Clojure

Choreographic functions are defined using the `defchor` macro, with the following basic syntax:

```clojure
(defchor <name> <role-list> <param-list>
  <body>)
```

TODO: Refer back to role expressions

....

## Full example: Buyer-Seller

....

 
  





## Specifying Roles

One of the biggest considerations of a choreographic language is how the programmer specifies which and how many roles are involved in a choreography, and which role is involved in which part of the computation.
In other words, when writing a choreography, the programmer needs the ability to annotate the subexpressions present within the choreography with their "locations", as well as specify the source and destination of each communication.

In Klor, roles present in a choreography are introduced by its *role list*, which is a vector of symbols that name the roles.
By convention, these symbols use TitleCase.
Here is a choreography named `increment-chor` with two roles, `Ana` and `Bob`:

```clojure
(defchor increment-chor [Ana Bob] <param-list>
  <body>)
```

Once roles have been introduced, the locations of a choreography's parameters and subexpressions of the body can be specified using *role-qualified symbols* and *role expressions* (also called *role forms*).
Both of these features are designed to work within the constraints of Clojure and its S-expression syntax.

Role-qualified symbols are of the form `<role>/<name>` and make use of Clojure namespaces to denote a location.
For example, the role-qualified symbols `Ana/x` or `Bob/y` have `x` and `y` as their names, and are located at the roles `Ana` and `Bob`, respectively.
Role-qualified symbols are distinguished from other namespace-qualified symbols by the fact that `<role>` has to be a role previously introduced by the enclosing choreography's role list.

Role expressions are of the form `(<role> <expr> ...)` and denote that each `<expr>` is located at `<role>`, unless further enclosed by another role expression.
Because role expressions can be nested arbitrarily, an expression will only take on the innermost enclosing role as its location.

## Role Analysis

Before further analyzing the expressions of a choreography's body, Klor first traverses it to identify the involved roles.
This is done in two phases: *role expansion* and *role analysis*.

Role expansion is the simple process of desugaring role-qualified symbols, as role expressions are in fact the more general mechanism of specifying locations.
A role-qualified symbol of the form `<role>/<name>` desugars into `(<role> <name>)`.
For example, the role-qualified symbol `Ana/x` would be expanded into `(Ana x)`.

Role analysis is the process of traversing an expression and removing all occurrences of role expressions, while simultaneously annotating the enclosed subexpressions with their roles.
Role expressions with multiple subexpressions, such as `(<role> <expr> ...)`, are converted to `do` expressions, `(do <expr> ...)`, while those with a single subexpression are just replaced with the subexpression itself.
The current implementation tracks the roles through the use of Clojure metadata.

## Expressions

Once role analysis is done, the expressions that are left resemble and for the most part behave just like standard Clojure expressions.
Klor borrows some of Clojure's special operators and retains their familiar syntax, but enhances them with choreographic semantics.
The (S-expression based) syntax of the currently supported special operators is:

- `(let [<var> <expr> ...] <body>)`
- `(do <expr> ...)`
- `(if <cond> <then> <else>)`
- `(select <label> <expr> ...)`

`select` is a Klor-specific special operator that is relevant for the purposes of projection, but otherwise behaves just like `do`.

Other compound expressions `(<op> <expr> ...)` that are not any of the above operators are treated as function and macro calls, as usual.
The use of role-qualified symbols and role expressions is allowed within any subexpression of a compound expression.

## Parameters

Because choreographies are functions, each choreography specifies a list of parameters that it takes as input.
The parameter list must be a vector of located symbols.
Continuing our example, we have:

```clojure
(defchor increment-chor [Ana Bob] [Ana/x]
  <body>)
```

## Communications

Unlike traditional choreographic languages that come with explicit syntactic primitives for specifying communication, communications in Klor are specified implicitly.
This is done by tracking the transitions between roles across subexpressions.

When an expression `e` located at role `Bob` makes use of a subexpression `s` located at role `Ana`, Klor specifies that the result of `s` must be communicated from `Ana` to `Bob` in order for `Bob` to carry out the remainder of the computation specified by `e`.
To illustrate, here is the completed definition of our example:

```clojure
(defchor increment-chor [Ana Bob] [Ana/x]
  (Ana (print (Bob (+ 1 (Ana x))))))
```

The choreography prescribes that:

- `Ana` will determine the value of `x` and send it over to `Bob`,
- `Bob` will receive the value, increment it and send it back to `Ana`,
- `Ana` will receive the final value and print it out.

While implicit in the structure of the code, communications in Klor are still deterministic and not silently injected by the compiler.
The programmer retains control over when and in what order the communications are done.
