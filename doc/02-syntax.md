# Syntax

Being embedded in Clojure, a Lispy language, the "surface syntax" of Klor is naturally based on S-expressions.
Choreographic functions are defined using the `defchor` macro, with the following basic syntax:

```clojure
(defchor <name> <role-list> <param-list>
  <body>)
```

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
