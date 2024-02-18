# Introduction

**Klor** is a Clojure library whose goal is to provide support for **choreographic programming** in Clojure.

## Choreographic Programming

We assume familiary with choreographic programming throughout the documentation.
Here we provide a quick introduction to get you up to speed, but for more details we recommend looking into the literature of choreographic programming.
A good starting point is [Introduction to Choreographies](https://doi.org/10.1017/9781108981491).

In brief, choreographic programming is a paradigm with which we program a distributed system from a **global viewpoint**.
A distributed system involes multiple **roles** (participants, locations, endpoints) that communicate (usually over a network) by passing messages between each other.

The program that we write is called a **choreography**.
The global view of the system's behavior provided by a choreography offers numerous advantages, the main one being that a compiler can automatically **project** (compile) the choreography to an implementation (executable code) for each of the roles.
Running the projections concurrently then yields the global behavior described by the choreography.

Standard choreographic programming languages come with a syntactic primitive for communication, usually in the style of "Alice and Bob" notation used in security protocols.
For example, `p.e -> q.x` would be read as "the role `p` sends the result of expression `e` to role `q` who stores it into its variable `x`".
Choreographic languages make it syntactically impossible to write communications that would result in mismatches or deadlocks.

## Principles of Klor

The main idea of Klor is to use macros to embed a choreographic, yet Clojure-like, programming language inside of Clojure.
Clojure was chosen as the base because it is a great fit for the inherently concurrent setting of choreographies.
It emphasizes a functional programming style and comes with a rich set of concurrency primitives and immutable persistent data structures, drastically lowering the amount of concurrency bugs.

In Klor, each choreography is modeled as a function (sometimes also called a **choreographic function**).
Choreographies defined with Klor can be projected to normal Clojure functions as part of Clojure's macroexpansion process and seamlessly integrated with existing Clojure code.
