# Reference: Language

## Usage

All operators necessary for writing Klor choreographies reside in the `klor.core` namespace.
This namespace is intentionally kept light and consists only of the entrypoint `defchor` macro and Klor's special operators.
It is suitable for being required with the `:all` qualifier:

```clojure
(require '[klor.core :refer :all])
```

## Syntax

Being an embedded domain-specific language, Klor's syntax naturally closely matches that of Clojure.
The lower-level "surface syntax" is the syntax accepted by the [Clojure reader](https://clojure.org/reference/reader), called [Clojure data syntax](https://clojure.org/reference/reader#_extensible_data_notation_edn).
The higher-level syntax is composed of Clojure data structures such as symbols, lists, vectors, etc.
Like Clojure and other Lisps, Klor is an expression-oriented language.

We use the following common metavariables in the EBNF-style grammars below:

- `<role>` is an unqualified symbol that names a role. Role symbols use TitleCase by convention.
- `<name>` is an unqualified symbol.

## Types

Klor's **choreographic type system** tracks **locations** of values and statically ensures that values cannot be used at a place other than the one they're located at.
It does not restrict the kinds of values that can be used (e.g., integers, strings, etc.), so Klor is still dynamically typed just like Clojure.
A key feature of the type system is the support for **multiply-located** values, allowing Klor to:

- express **agreement** between roles,
- **group choreographic values** located at different roles, and
- manipulate **choreographies as first-class values**.

The type system understands three kinds of types.
Their syntax is given by:

```clojure
<type>
  ::= <role>                 ; syntax sugar for a singleton agreement type
    | #{<role>+}             ; an agreement type
    | [<type>+]              ; a tuple type
    | (-> <type>+)           ; a choreography type with omitted auxiliary roles
    | (-> <type>+ | 0)       ; a choreography type with empty auxiliary roles
    | (-> <type>+ | <role>+) ; a choreography type with explicit auxiliary roles
```

An **agreement type** expresses a value that is agreed upon by a set of one or more roles.
In other words, the roles hold values that are assumed equal as if by Clojure's equality operator `=`.
An agreement type is denoted by a Clojure set of roles, such as `#{A B C}`, meaning "`A`, `B` and `C` agree on the value".
A bare role like `A` is syntax sugar for the singleton agreement type `#{A}`.

A **tuple type** expresses a value that is the composition of one or more other choreographic values, each of which might be of arbitrary choreographic type.
A tuple type is denoted by a Clojure vector of types, such as `[A [B] #{B C}]`, representing a tuple of three values of types `A`, `[B]`, and `#{B C}`, respectively.

A **choreography type** expresses a value that is a choreography.
A choreography type is denoted by a Clojure list prefixed with `->` followed by zero or more types representing the input parameters and a final type representing the output.
For example, `(-> A #{B C} [A D])` represents a choreography that takes values of type `A` and `#{B C}` as input and produces a value of type `[A D]` as output.

Optionally, a choreography type can mention the choreography's **auxiliary roles** at the end of the list, delimited with a pipe `|`.
Those are the roles which do not appear in the types of the inputs or the output but are nonetheless involved in the body of the choreography.
For example, `(-> A B | C)` represents a choreography taking an input of type `A`, producing an output of type `B` and with `C` involved in its implementation.
Tracking the auxiliary set of roles is necessary for the purposes of projection and is part of a choreography's signature.
A `0` in place of the auxiliary roles specifies an empty set.

Klor performs **type inference** but requires type signatures for named choreographic definitions and anonymous choreographies.
The auxiliary set in choreography types can be omitted and inferred automatically in some contexts (documented below).

## Expressions

Special operators implement the primitive operations of a language, such as control flow and variable binding.
The majority of the functionality in the choreographic domain is provided by the Klor-specific special operators:

```clojure
<expr>
  ::= (lifting [<role>+] <expr>*)
    | (copy [<src> <dst>] <expr>)
    | (narrow [<role>+] <expr>)
    | (chor <name>? <sig> [<param>*] <expr>*)
    | (inst <name> [<role>+])
    | (pack <expr>+)
    | (unpack [<binding>*] <expr>*)
    | (agree! <expr>+)
    | <clojure-expr>
```

Klor also borrows most of [Clojure's special operators](https://clojure.org/reference/special_forms), though some of them require special considerations to be given choreographic semantics.
All supported special operators are documented below.

### `(lifting [<role>+] <expr>*)`

`lifting` instructs Klor to infer the type of every literal and var reference (referring to a non-choreographic Clojure var) appearing lexically in its body as the agreement type `#{<role>+}`.
We say that the literal or var reference in question has been "lifted to `#{<role>+}`".
When `lifting` expressions are nested, the innermost enclosing `lifting` expression takes precedence.

`lifting` does **not** in any way constrain the set of roles available for use in its body, meaning is it perfectly fine for a nested `lifting` expression to widen the set of roles compared to an outer one.
Likewise, `lifting` does **not** influence the type inference of any expression other than a literal or var reference, so it is permitted for `lifting` to enclose any kind of expression, even those whose type does not mention some or any of `<role>+`.

A role expression `(<role> <expr>*)` is syntax sugar for `(lifting [<role>] <expr>*)`.
The body `<expr>*` forms an implicit `do` block.

### `(copy [<src> <dst>] <expr>)`

Given `<expr>` of agreement type with at least `<src>` in its agreement set, `copy` **communicates** the value of `<expr>` from `<src>` to `<dst>`, extending the agreement set with `<dst>`.
`<dst>` must not already be present in the type of `<expr>`.

The double-arrow expression `(A=>B <expr>)` is syntax sugar for `(copy [A B] <expr>)`, for any two roles `A` and `B`.

A `copy` immediately followed by a `narrow` at the destination is called a **move**.
This frequent pattern has its own syntax sugar -- the single-arrow expression `(A->B <expr>)` -- which expands to `(narrow [B] (copy [A B] <expr>))` for any two roles `A` and `B`.

### `(narrow [<role>+] <expr>)`

Given `<expr>` of agreement type, `(narrow [<role>+] <expr>)` is an expression equal in value to `<expr>` but of agreement type `#{<role>+}`.
The new agreement type `#{<role>+}` must be strictly **narrower** than the original, i.e. all `<role>`s must be present in the original agreement type.

`narrow` effectively "forgets" part of an agreement that was established previously, allowing the value to be manipulated by a fewer number of roles.

### `(chor <name>? <sig> [<param>*] <expr>*)`

`chor` is the choreographic analogue to Clojure's `fn`: it creates an **anonymous choreography**.

Each `<param>` in the parameter vector is either a symbol or an `unpack` `<binder>`, allowing for in-place unpacking.
`<sig>` must be a choreography type, specifying the type of each parameter in order and the return type.
The signature may specify an auxiliary set explicitly, but a minimal one will be inferred if omitted.

The body `<expr>*` forms an implicit `do` block.
Furthermore, `chor` establishes a `recur` block around the body.

`<name>` is a symbol that can be used for self-reference just like in `fn`.
When a name is provided however, the signature must specify an auxiliary set explicitly.

### `(inst <name> [<role>+])`

`inst` instantiates a choreographic definition created using `defchor`, returning a concrete choreography as a value.
The definition is instantiated by substituting each `<role>` for the appropriate role parameter of the definition.

### `(pack <expr>+)`

`pack` constructs a value of tuple type whose elements are the results of each `<expr>`.

### `(unpack [<binding>*] <expr>*)`

`unpack` destructures a value of tuple type by pattern matching.

Each `binding` is a pair `<binder> <init>` where `<init>` must be of tuple type and `<binder>` must be a vector destructuring pattern matching the tuple's shape.
The symbols in `<binder>` are bound to the elements of `<init>` according to their position.
The vector destructuring pattern of a `<binder>` can be **nested** arbitrarily as long as each pattern matches the shape of the corresponding element.
Like in Clojure's `let`, each binding is visible to all subsequent `<init>` expressions and within the body of `unpack`.

The body `<expr>*` forms an implicit `do` block.

### `(agree! <expr>+)`

TODO

### `(do <expr>*)`

`do` performs **choreographic sequencing** of the given expressions (see [Reference: Runtime](./reference-02-runtime.md)).
Its return value and type are equal to those of its last expression.
An empty sequence of expressions is treated as if a single `nil` expression was provided.

### `(let [<let-binding>*] <expr>*)`

`let` is the standard binding form.

Each `<let-binding>` is a pair `<let-binder> <init>`.
The type of each binding is inferred from its `<init>`, which can be an arbitrary choreographic value.

Destructuring within `<let-binder>` has the usual **Clojure semantics** of destructuring and **not** the Klor unpacking semantics.
Such destructuring is only permitted on agreement types, which should be of the appropriate collection type at run time.

The body `<expr>*` forms an implicit `do` block.

### `(if <guard> <then> <else>?)`

`if` is the choreographic conditional.

`<guard>` must be an expression of agreement type.
To ensure sufficient **knowledge of choice**, a role is allowed to appear within `<then>` or `<else>` only if it is part of `<guard>`'s agreement type.

The return value of the `if` is the value of either `<then>` or `<else>`, depending on outcome of `<guard>`.
The types of `<then>` and `<else>` must match and are the type of the `if`.

### `(case <expr> <clause>*)`

`case` has similar knowledge of choice considerations as `if`.

`<expr>` must be an expression of agreement type and all test constants part of any `<clause>` must be of that same agreement type.
A role can only appear in the result expression of a `<clause>` if it is part of the agreement type of `<expr>`.
The result expressions of all `<clause>`s must be of the same type.

### `(<op> <expr>*)`

Standard invocation syntax is used to invoke Clojure macros, Clojure functions (values of agreement type), Klor choreographies and Klor choreographic definitions.

If `<op>` is a macro, it is expanded like in Clojure.

If `<op>` is of agreement type (called an **agreement operator**), all `<expr>`s must be of an **agreement subtype** of `<op>`.
This means that they have to be at least as wide as `<op>`, i.e. their agreement set must contain at least all of the roles contained in the agreement set of `<op>`.
The type of the return value is the type of `<op>`.

If `<op>` is of choreography type (called a **choreographic operator**), the type of each `<expr>` must match the type of the corresponding parameter of the choreography.
The type of the return value is the return type of the choreography.

If `<op>` is a symbol that names a choreographic definition, the first argument must be a vector of roles used to instantiate it.
This is syntax sugar for an explicit instantiation followed by an invocation.
In other words, `(<op> [<role>+] <expr>*)` desugars into `((<inst> <op> [<role>+]) <expr>*)`.

### Other Clojure Operators

The following Clojure special operators are given choreographic semantics by treating them as agreement operators:

- `[<expr>*]`
- `#{<expr>*}`
- `{<pair>*}`
- `(quote <literal>)`
- `(new <expr>)`
- `(. <expr> <sym>)`, `(. <expr> (<sym> <expr>*))`
- `(throw <expr>)`

In other words, the active `lifting` context determines the return type, and the invocation follows the usual agreement operator rule: all evaluated subexpressions `<expr>` must be of an agreement subtype of that type.

On the other hand, the following Clojure special operators are given choreographic semantics by requiring that their bodies be **homogeneous**:

- `(fn <name>? ([<param>+ <expr>*])+)`
- `(try <expr>* (catch <class> <name> <expr>*)* (finally <expr>*)?)`

This means that all of their evaluated subexpressions `<expr>` **must not mention** any type other than the agreement type corresponding to the active `lifting` context.
Said differently, no subexpression `<expr>` or any of its further subexpressions **recursively** can be of type different from said agreement type.
Their return type is also that same agreement type.

This is necessary to make reuse of existing Clojure code possible.
For example, if `fn` did not return a value of agreement type, it would be unusable as an argument to other Clojure functions such as `map`, `with-bindings`, etc.
On the other hand, the only way it can return a value of agreement type is to require that the behavior in its body is consistent across roles, otherwise their return values could differ.

### Unsupported Clojure Operators

TODO: `loop`, others?

## The Entrypoint: `defchor`

The `defchor` macro defines a named **choreographic definition**, which is a **location-polymorphic** choreography.
The syntax is similar to that of `chor`:

```clojure
(defchor <name> [<role>+] <sig> [<param>*]
  <expr>*)
```

`<name>` is a symbol like in Clojure's `def` and names the choreography.
`[<role>+]` is a **role vector** introducing the roles available for use in the definition.

The signature `<sig>`, parameter vector `[<param>*]` and body `<expr>*` are as in `chor`.
The body is enclosed in an implicit `lifting` block that includes all of the roles in the definition.

The signature cannot specify an auxiliary set of roles for the top-level choreography type constructor `->`.
It is always inferred such that all roles from the role vector are present in the type signature.

`defchor` performs type checking and projection of the choreography at macroexpansion time.
When evaluated, it defines `<name>` as a Clojure var bound to an **unspecified value** representing the choreographic definition.
This value is suitable as an argument to `play-role` and `simulate-chor` (see [Reference: Runtime](./reference-02-runtime.md)).

The body of a `defchor` can be left out, in which case it serves as a **forward declaration**, similar to Clojure's `declare`.
This allows for writing **mutually recursive** choreographies.
The actual implementation of a forward declared choreography can be provided with a separate `defchor` form that has the same name and an equivalent type signature (up to alpha equivalence, i.e. role renaming).

Klor will raise a warning when a `defchor` form is re-evaluated with a type signature different from its last definition.
In that case the user also has to re-evaluate all dependent `defchor` forms.
