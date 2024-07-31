# Tutorial: Introduction

This tutorial is aimed at programmers who already have a solid understanding of Clojure but haven't heard of Klor or [choreographic programming](https://en.wikipedia.org/wiki/Choreographic_programming) before.
It first gives a quick overview of the general idea of choreographic programming and for the remainder focuses on showing how it's done in Klor specifically.

## Choreographic Programming

In short, choreographic programming is a paradigm in which one programs a distributed system from a **global viewpoint**.
A distributed system involes multiple **roles** (also called participants, endpoints or locations) that execute **concurrently** and communicate by passing messages between each other (most often over a network).

A program written in the choreographic paradigm is called a **choreography**.
Intuitively, it specifies the control flow of the distributed system as a whole -- the communications between the participants and the local computations they perform.

A choreography's global view of the system's behavior offers certain advantages, such as the fact that a compiler can automatically transform the choreography into an executable implementation for each role.
This compilation procedure is known as **projection** (also called endpoint projection or EPP) and the generated implementations as **projections**.
Running the projections concurrently will yield the global behavior described by the choreography.

Another advantage is that choreographic programming languages come with a syntactic primitive for communication, usually in the style of "Alice and Bob" notation found in security protocols.
For example, in an imperative choreographic programming language, `p.e -> q.x` would be an instruction read as "the role `p` sends the result of expression `e` to role `q`, who stores it in its variable `x`".
This style makes it syntactically impossible to write communications that would result in **send--receive mismatches or deadlocks**.

Here's a small example involving 3 roles (`A`, `B` and `C`) that demonstrates what (imperative) choreographies look like and how projection works:

```
A.x -> B.x; // A sends the value of x to B who stores it in x
A.y -> C.x;
C.y := f(); // C locally computes y
C.y -> B.y;
```

Projection will then produce 3 local programs, one for each of `A`, `B` and `C`, respectively.
Note how each local program contains only the instructions related to the corresponding role:

```
send(B, x); // Send the value of x to B
send(C, y);
```

```
x := recv(A); // Receive from A into x
y := recv(C);
```

```
x := recv(A);
y := f(); // Compute y locally
send(B, y);
```

Choreographic programming languages also come with support for conditionals, procedures, recursion, etc.
All of this and more will be explained throughout the tutorial as necessary.

For a more formal treatment of choreographic programming we recommend looking into the literature.
A good starting point is the [Introduction to Choreographies](https://doi.org/10.1017/9781108981491) book.

## Klor

Klor brings choreographic programming to Clojure in the form of a **domain-specific language** (DSL) packaged as a normal Clojure library.
Building on top of the powerful Lispy metaprogramming facilities (macros), Klor delivers a DSL without the use of a custom toolchain, yet with sophisticated compile-time analyses guaranteeing correctness.

Unlike the custom imperative language used in the example above, Klor embraces the functional approach of Clojure and molds the DSL around it.
Clojure was chosen as the base because it is a great fit for the inherently concurrent setting of choreographies -- it emphasizes a mostly-pure functional programming style and comes with a rich set of concurrency primitives and persistent data structures, drastically lowering the amount of concurrency bugs.
Choreographies defined in Klor are projected to normal Clojure code during the macroexpansion process and can be seamlessly integrated with existing code.

See [Tutorial: Basics](tutorial-02-basics.md) to get started with Klor.
