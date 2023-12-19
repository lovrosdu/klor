# Klor: Choreographies in Clojure

## Setup

You should have Clojure and Leiningen installed.

Clone the Git repository and use:

- `lein test` to run the test suite,
- `lain run` to run a small printing demo.

## Example

We assume some familiary with *choreographic programming*.
In short, choreographic programming allows us to program a distributed system involving multiple communicating *roles* (participants) from a *global viewpoint*.
The global program that we write is called a **choreography** and can be automatically *projected* (compiled) to an implementation for each of the involved roles.
Running the projections simultaneously yields the global behavior described by the choreography.

In Klor, we model a choreography as a (choreographic) function, which is projected at compile-time to a normal Clojure function for each of the involved roles.
Choreographic functions are defined using the `defchor` macro, using the following syntax:

```clojure
(defchor <chor-name> <role-list> <param-list>
  <body>)
```

As a classical example, let's define a `buy-book` choreography where `Buyer` wishes to buy a book from `Seller`:

```clojure
(defchor buy-book [Buyer Seller] <param-list>
  <body>)
```

The roles participating in a choreography are listed explicitly in its *role list*.
A choreographic function also has a *parameters list*, just like a normal function.
Our choreography should take the `Buyer`'s order and the `Seller`'s current catalogue as parameters:

```clojure
(defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
  <body>)
```

Note how the parameters are *role-qualified*, denoting their location.
When a choreography is projected at a role, the resulting function keeps only the parameters that are located at the particular role.

More generally, all expressions within a choreographic function are *located*, which is done using *role forms*.
A role form looks like `(<role> <expr> ...)`and denotes that each `<expr>` is located at `<role>`, where `<role>` is any of the role names introduced by the role list.
Role forms can be nested, and inner role forms take precedence over outer ones.
A role-qualified symbol such as `Role/x` is really just a shorthand for the explicit role form version `(Role x)`.

Standard choreographic programming languages come with a primitive that explicitly specifies a communication between two roles.
In Klor however, communications are specified implicitly (but still deterministically) just by virtue of using an expression located at one role within a bigger expression located at another role.
For example, assuming we have roles `A` and `B`, the choreographic expression `(A (+ 1 (B x)))` would communicate the value `x` located at `B` to `A`, and produce the value `(+ 1 x)` at `A`.

Coming back to our choreography, the outline of the global behavior we want is:

- `Buyer` sends `Seller` the title of the order,
- `Seller` looks up the price of the book and sends it to `Buyer`,
- `Buyer` decides whether or not to buy the book depending on its budget,
- `Buyer` sends its decision to `Seller`, along with an address if necessary,
- `Seller` receives the decision and possibly ships the book to the `Buyer`.

Before we show the choreography in Klor, assume we have the following:

- `order` is a map `{:title <string> :budget <number> :address <string>}`,
- `catalogue` is an opaque value on which we can pass to `price-of`,
- `price-of` returns the price of a book, given the title and the catalogue,
- `ship!` performs the side-effect of shipping the book, given the address.

Now, putting it all together, we end up with the following:

```clojure

(defchor buy-book [Buyer Seller] [Buyer/order Seller/catalogue]
  (let [Seller/title (Buyer (:title order))
        Buyer/price (Seller (price-of title catalogue))]
    (if (Buyer (>= (:budget order) price))
      (let [Seller/address (Buyer (:address order))
            Seller/date (Seller (ship! address))]
        (Buyer (println "I'll get the book on" (Seller date))))
      (Seller (println "Buyer changed his mind")))))
```

Note how we use role-qualified symbols `Buyer/...` and `Seller/...`, as well as the role forms `(Buyer ...)` and `(Seller ...)`, to specify the location of a particular expression.
Klor extends Clojure's primitive operators such as `let` and `if` to work in a choreographic context.
For example, Klor's `let` allows one to (1) bind variables at multiple different roles and (2) to bind a variable to a value which might be located at a different role, triggering a communication.
