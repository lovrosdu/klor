# Syntax

Being embedded in Clojure, the lower-level "surface syntax" of Klor naturally matches the syntax accepted by the [Clojure reader](https://clojure.org/reference/reader), called [Clojure data syntax](https://clojure.org/reference/reader#_extensible_data_notation_edn).
At a higher-level however, the syntax of Klor's expressions consists of Clojure data structures such as symbols, lists, vectors, etc.

Like Clojure and other Lisps, Klor is an expression-oriented language.
Below we describe the different kinds of **Klor expressions**.
We start with role expressions that are of special importance in our setting.

In the Common Lisp tradition, an expression is sometimes also called a [*form*](https://www.lispworks.com/documentation/lw50/CLHS/Body/26_glo_f.htm#form).

## Role Expressions

One of the biggest considerations of a choreographic language is how the programmer specifies which role is involved in which part of a choreography.
The programmer needs the ability to annotate the subexpressions of the choreography with their roles, for example to specify the location of a literal value, the source and destination of a communication, or the location of a local computation.
A Klor expression annotated with a role is said to be **located** at that role.

Assume for now that there exists a set of roles consisting of `Ana`, `Bob`, `Cal`, `Dan` and `Eli` which will be used in examples.
We will discuss later how roles are actually introduced within a choreography.

Any Klor expression can be annotated with a role using a **role expression**.
A role expression is of the form

```clojure
(<role> <expr>*)
```

and denotes that each `<expr>` is located at `<role>`.
Role expressions can be nested arbitrarily, but a Klor expression is only located at the role specified by the innermost enclosing role annotation, which we call the **active role** for that Klor expression.
This works in a manner similar to lexical scope, with the active role propagating to all appropriate subexpressions of `<expr>` (just like a lexical binding's scope propagates to all subexpressions, unless shadowed).
For example, in the Klor expression `(Ana x (Bob y (Cal z)))`:

  - `x` and `(Bob y (Cal z))` are located at `Ana`,
  - `y` and `(Cal z)` are located at `Bob`,
  - `z` is located at `Cal`.

To make programs easier to read, Klor also has **role-qualified symbols**.
A role-qualified symbol is of the form

```clojure
<role>/<name>
```

and designates the symbol `<name>` while simultaneously annotating it as being located at `<role>`.
For example, `Ana/x` and `Bob/y` designate the symbols `x` and `y` located at `Ana` and `Bob`, respectively.
Role-qualified symbols are essentially just syntax sugar and are equivalent to the role expression `(<role> <name>)`.
They are distinguished from Clojure's usual namespace-qualified symbols by requiring that `<role>` be a name of a previously introduced role.

Both role expressions and role-qualified symbols are designed to work within the constraints of Clojure and its data syntax, as Clojure's reader is not extensible (unlike e.g. Common Lisp's; see [reader macros](https://www.lispworks.com/documentation/lw71/CLHS/Body/26_glo_r.htm#reader_macro)).

### Communications Between Roles

Unlike traditional choreographic languages that come with explicit syntactic primitives for specifying communication, communications in Klor are specified **implicitly**.
This is done by tracking the transitions between roles across subexpressions, determined wholly from the lexical structure of the choreography.
In general, communications are done **inside out**: the results of subexpressions are communicated to the roles of expressions that enclose them, if the locations of the two are different.
For instance:

```clojure
;; - Ana sends 1 to Bob,
;; - Bob receives the value and sends it to Cal.

(Cal (Bob (Ana 1)))
```

Despite being implicit in the structure of the code, communications in Klor are still **deterministic** and are not silently injected at the compiler's discretion.
The programmer retains enough control to arrange when and in what order the communications are done.

## Choreographic Expressions

A **choreographic expression** is a Klor expression that involves one or more role, i.e. one that can contain subexpressions located at different roles, which allows for communications between them.
A choreographic expression is either a **basic expression** or a **compound expression** (a Clojure collection -- list, vector, map or set -- of a certain structure).
Role expressions mentioned above are just one particular kind of compound expression.

As is customary in Lisps, list compound expressions are treated as applications of operators, which can be **functions** or **special operators**.
In contrast, **non-list compound expression** (vectors, maps and sets) don't have a similar special interpretation and are instead used to construct a collection of the corresponding type, like in Clojure.

Schematically, the following is an overview of the syntax of a choreographic expression `<expr>`:

```clojure
<expr> ::= <basic-expr> | <compound-expr>

<compound-expr>
  ::= (<role> <expr>*)           ; role expression
    | (<op> <expr>*)             ; function operator
    | (do <expr>*)               ; special operator
    | (let [<binding>*] <expr>*) ; special operator
    | (if <cond> <then> <else>?) ; special operator
    | (select <label> <expr>*)   ; special operator
    | [<expr>*]                  ; vector
    | {<pair>*}                  ; map
    | #{<expr>*}                 ; set

<pair> ::= <expr> <expr>

<binding> ::= <binder> <expr>
```

Below we detail the syntax of each kind of remaining expression and the communications they prescribe.
All communications follow the same core "inside out" principle as explained previously.

### Basic Expressions

A **basic expression** is any Clojure expression that is not a Clojure collection (list, vector, map or set) -- booleans, numbers, characters, strings, symbols, keywords and nil.

### Functions

An invocation of a function operator `<op>` is of the form

```clojure
(<op> <expr>*)
```

The active role is propagated to `<op>` and each `<expr>`.
`<op>` is not allowed to be located at a role different from the role of the whole Klor expression.

All function invocations use a single general communication rule.
If `(<op> <expr>*)` is located at role `<role-2>` and any argument `<expr>` is located at a different role `<role-1>`, then `<role-1>` will have to communicate the result of `<expr>` to `<role-2>` before `<role-2>` can carry out the remainder of the computation, which is the invocation of `<op>` itself.
For example:

```clojure
;; - Ana determines the value of x and sends it to Bob,
;; - Bob receives the value, increments it and sends it back to Ana,
;; - Ana receives the final value and prints it out.

(Ana (println (Bob (inc (Ana x)))))

;; - Ana and Bob independently send the values of x and y to Cal,
;; - Cal receives the two values and adds them together.

(Cal (+ (Ana x) (Bob y)))
```

### Special Operators

Special operators implement the primitive operations of a language such as control flow and variable binding.
Klor borrows some of [Clojure's special operators](https://clojure.org/reference/special_forms) and retains their familiar syntax, but enhances them with choreographic semantics.
The special operators of Klor are:

- `(do <expr>*)`

  The active role is propagated to each `<expr>`.
  Only the result of the last `<expr>`, if any, is communicated, if the location of `<expr>` is different from the location of the whole Klor expression.

  For convenience purposes, role expressions are treated as implicit `do` blocks, which is the reason why they can enclose more than one expression: `(<role> <expr>*)`
  Like role-qualified symbols, this makes Klor code more readable and reduces the level of nesting:

  ```clojure
  ;; - Ana sends 1 to Bob,
  ;; - Bob receives the value and sends it to Cal,
  ;; - but Dan's 3 and Cal's 2 are not communicated.

  (Cal (Dan 3) (Bob (Cal 2) (Ana 1)))
  ```

- `(let [<binding>*] <body-expr>*)`

  Each `<binding>` is of the form `<binder> <init-expr>`.
  A `<binder>` is anything that is accepted by Clojure's own `let` -- a plain symbol or a sequential or associative destructuring pattern.

  The active role is propagated to each `<binder>`, `<init-expr>` and `<body-expr>`.
  Role expressions are allowed within `<binder>`, but it is forbidden for parts of a binder to be located at different roles.
  Therefore, it is only ever useful for `<binder>` to contain a single top-level role expression, if any.
  Role-qualified symbols come in handy as binders.

  If the location of an `<init-expr>` is different from the location of its corresponding `<binder>`, the value of `<init-expr>` is communicated before it is bound according to `<binder>` at the destination.
  A communication within the body `<body-expr>*` is determined as in `do`.

- `(if <cond> <then> <else>?)`

  The active role is propagated to `<cond>`, `<then>` and `<else>`.
  The result of either `<then>` or `<else>`, if any, is communicated only if its location is different from the location of the whole Klor expression.

- `(select <label> <expr>*)`

  `select` is a Klor-specific special operator that is relevant for the purposes of projection, but otherwise behaves just like `do`.

### Non-list Compound Expressions

Non-list compound expressions are vectors, maps and sets: `[<expr>*]`, `{<pair>*}` and `#{<expr>*}` (where `<pair>` is `<expr> <expr>`).
Like in Clojure, they are effectively shorthands for creating a collection of elements given by the results of all `<expr>`s, but the same could be achieved by standard Clojure functions such as `vector`, `hash-map` and `hash-set`.

Non-list compound expressions behave essentially the same as function calls when it comes to communications.
Any `<expr>` whose location differs from the location of the whole Klor expression is communicated before the collection can be assembled at the destination:

```clojure
;; No communications are done.

(Ana [1 2 3])
(Ana [(Ana 1) 2 3])
(Ana [(Ana 1) (Ana 2) (Ana 3)])

;; - Bob, Cal and Dan send 1, 2 and 3 to Ana,
;; - Ana receives the values and constructs the vector [1 2 3].

(Ana [(Bob 1) (Cal 2) (Dan 3)])
```

# Embedding Choreographies in Clojure: `defchor`

Choreographies in Klor are defined using the `defchor` macro with the following syntax:

```clojure
(defchor <name> [<role>+] [<binder>*]
  <expr>*)
```

`<name>` is a symbol that names the choreography.

The **role vector** serves to introduce the roles participating in the choreography.
Each `<role>` is an unqualified symbol.
Role symbols use TitleCase by convention.

Choreographies are functions and can therefore accept zero or more parameters.
The second vector is the **parameter vector** and contains zero or more binders as in `let`, all of which must be located.

Finally, the body of a choreography is a sequence of zero or more choreographic expressions, forming an implicit `do` block.

## Example: The Increment Choreography

Here is the full definition of the small increment example presented before:

```clojure
(defchor increment-chor [Ana Bob] [Ana/x]
  (Ana (println (Bob (inc Ana/x)))))
```

## Example: The Buyer--Seller Choreography

This example shows how the classical buyer--seller choreography might be implemented in Klor.
The two roles, `Buyer` and `Seller`, communicate in order for `Buyer` to buy a book from `Seller`:

- `Buyer` sends `Seller` the title of the book,
- `Seller` receives the title, looks up the price of the book and sends it to `Buyer`,
- `Buyer` receives the price and decides whether or not to buy the book depending on its budget,
- `Buyer` sends its decision to `Seller`, along with an address if necessary,
- `Seller` receives the decision and possibly ships the book to the `Buyer`.

Before we show the choreography in Klor, assume we model the problem using the following:

- `order` is a map `{:title <string> :budget <number> :address <string>}`,
- `catalogue` is an opaque value which we can pass to `price-of`,
- `price-of` is a function that given the title and the catalogue returns the price of the book,
- `ship!` is a side-effectful function that given the address executes the book shipment.

Putting it all together, we might end up with the following:

```clojure

(defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
  (let [Seller/title (Buyer (:title order))
        Buyer/price (Seller (price-of title catalogue))]
    (if (Buyer (>= (:budget order) price))
      (select Buyer/ok
        (let [Seller/address (Buyer (:address order))
              Seller/date (Seller (ship! address))]
          (Buyer (println "I'll get the book on" Seller/date))))
      (select Buyer/ko
        (Seller (println "Buyer changed his mind"))))))
```
