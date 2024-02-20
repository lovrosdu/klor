# Syntax

Being embedded in Clojure, the syntax of Klor naturally matches Clojure's.
The lower-level "surface syntax" is the syntax accepted by the [Clojure reader](https://clojure.org/reference/reader), called [Clojure data syntax](https://clojure.org/reference/reader#_extensible_data_notation_edn).
The higher-level syntax is composed of Clojure data structures such as symbols, lists, vectors, etc.

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
A role expression is of the form `(<role> <expr>*)` and denotes that each `<expr>` is located at `<role>`.
Role expressions can be nested arbitrarily, but a Klor expression is only located at the role specified by the innermost enclosing role expression, which we call the **active role** for that expression.
This works in a manner similar to lexical scope, with the active role propagating to all appropriate subexpressions of `<expr>` (just like a lexical binding's scope propagates to all subexpressions, unless shadowed).
For example, in the Klor expression `(Ana x (Bob y (Cal z)))`:

- `x` and `(Bob y (Cal z))` are located at `Ana`,
- `y` and `(Cal z)` are located at `Bob`,
- `z` is located at `Cal`.

To make programs easier to read, Klor also has **role-qualified symbols**.
A role-qualified symbol is of the form `<role>/<name>` and designates the symbol `<name>` while simultaneously annotating it as being located at `<role>`.
For example, `Ana/x` and `Bob/y` designate the symbols `x` and `y` located at `Ana` and `Bob`, respectively.
Role-qualified symbols are essentially just syntax sugar and are equivalent to the role expression `(<role> <name>)`.
They are distinguished from Clojure's usual namespace-qualified symbols by requiring that `<role>` be a name of a previously introduced role.

Both role expressions and role-qualified symbols are designed to work within the constraints of Clojure and its data syntax, as Clojure's reader is not extensible (unlike e.g. Common Lisp's; see [reader macros](https://www.lispworks.com/documentation/lw71/CLHS/Body/26_glo_r.htm#reader_macro)).

### Communication Between Roles

Unlike traditional choreographic languages that come with explicit syntactic primitives for specifying communication, communications in Klor are specified **implicitly**.
This is done by tracking the transitions between roles across expressions, determined almost always purely from the lexical structure of the choreography.
In general, communications are done **inside out**: the results of all necessary subexpressions are communicated to the roles of expressions that enclose them, if the locations of the two are different.
For instance:

```clojure
;; - Ana sends 1 to Bob,
;; - Bob receives the value and sends it to Cal.

(Cal (Bob (Ana 1)))
```

Despite being implicit in the structure of the code, communications in Klor are still **deterministic** and are not silently injected at the compiler's discretion.
The programmer retains enough control to arrange when and in what order the communications are done.

## Choreographic Expressions

An arbitrary Klor expression is called a **choreographic expression** and can invole any number of roles.
A choreographic expression is either an **atomic expression** or a **compound expression** (a Clojure collection -- list, vector, map or set -- of a certain structure).
Role expressions mentioned above are just one particular kind of compound expression.

As is customary in Lisps, list compound expressions are treated as applications of operators, which can be **functions** or **special operators**.
In contrast, **non-list compound expressions** (vectors, maps and sets) don't have a similar special interpretation and are instead used to construct a collection of the corresponding type, like in Clojure.

Schematically, the following is an overview of the syntax of a choreographic expression `<expr>`:

```clojure
<expr>
  ::= <atomic-expr>                      ; atomic expression
    | (<role> <expr>*)                   ; role expression
    | (<op> <expr>*)                     ; function operator
    | (do <expr>*)                       ; special operator
    | (let [<binding>*] <expr>*)         ; special operator
    | (if <cond> <then> <else>?)         ; special operator
    | (select [<label> <role>+] <expr>*) ; special operator
    | (dance <name> [<role>+] <expr>*)   ; special operator
    | [<expr>*]                          ; vector
    | {<pair>*}                          ; map
    | #{<expr>*}                         ; set
```

Below we briefly go over each broad category of Klor expressions, but leave the finer details of their meaning to [Semantics](./03-semantics.md).

### Atomic Expressions

An **atomic expression** (also called an **atom**, not to be confused with Clojure's atoms) is any Clojure expression that is not a Clojure collection (list, vector, map or set) -- booleans, numbers, characters, strings, symbols, keywords and nil.

### Functions

An invocation of any function operator `<op>` with zero or more arguments is of the form `(<op> <expr>*)`.

### Special Operators

Special operators implement the primitive operations of a language such as control flow and variable binding.
Klor borrows some of [Clojure's special operators](https://clojure.org/reference/special_forms) and retains their familiar syntax, but enhances them with choreographic semantics.
These are `do`, `let` and `if`, and the Klor-specific `select` and `dance`.

### Non-list Compound Expressions

Non-list compound expressions are vectors, maps and sets: `[<expr>*]`, `{<pair>*}` and `#{<expr>*}` (where `<pair>` is `<expr> <expr>`).
Like in Clojure, they are effectively shorthands for creating a collection of elements given by the results of all `<expr>`s, but the same could be achieved with standard Clojure functions such as `vector`, `hash-map` and `hash-set`.

## Embedding Choreographies in Clojure: `defchor`

Choreographies in Klor are defined using the Clojure `defchor` macro with the following syntax:

```clojure
(defchor <name> [<role>+] [<binder>*]
  <expr>*)
```

`<name>` is a symbol that names the choreography.

The **role vector** `[<role>+]` serves to introduce the roles participating in the choreography.
Each `<role>` is an unqualified symbol.
Role symbols use TitleCase by convention.

Choreographies are functions and can therefore accept zero or more parameters.
The second vector `[<binder>*]` is the **parameter vector** and contains zero or more binders as in `let` (see [Semantics](./03-semantics.md)).

Finally, the body of a choreography is a sequence of zero or more choreographic expressions, forming an implicit `do` block.
