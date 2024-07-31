# Tutorial: Sharing Knowledge

## Agreement

It turns out that the single-role choreographic types (such as `A` and `B`) and the single-arrow communication operator (like `A->B`) are actually special cases of a more powerful idea.
Consider the following choreography, very similar to the previous `simple`:

```clojure
(defchor share [A B] (-> #{A B}) []
  (A=>B (A 5)))
```

The type `#{A B}`, represented as a Clojure set of roles, is called an **agreement type**.
It denotes that a value is located at both `A` and `B`, and furthermore that the two roles agree on what it is.
In other words, `A` and `B` will at run-time hold values equivalent under Clojure's equality operator `=`.
Single-role types such as `A` are just syntax sugar for the more verbose singleton agreement type `#{A}`.

The double-arrow communication operator `A=>B` used in the body is like the single-arrow operator `A->B`, except that instead of just **moving** a value from `A` to `B`, it **copies** it to (or shares it with) `B`.
When a value is copied, the resulting type is an agreement type whose set of roles is extended with the source location.
Therefore, `A=>B` will take as an argument a value of an agreement type involving *at least* `A`, communicate it to `B`, and as a result produce a value of the initial agreement type extended with `B`.

Klor fundamentally assumes that **copying preserves value**, i.e. that communication will not modify the value in a way that would change the outcome of any deterministic computation using it as input (all else being equal).

A value of an agreement type is one kind of **multiply-located** value, so the above choreography will yield a result at both of the roles, equal in value in this case.
We can check this with `@(simulate-chor share)`:

```
>> A spawned
>> B spawned
>> A --> B: 5
>> B exited normally
>> A exited normally
=> {A 5, B 5}
```

## Agreement Operators

With the idea of agreement laid down, we can now briefly revisit and expand on the previously introduced concept of role expressions and lifting.
A role expression such as `(A ...)` is actually syntax sugar for Klor's special operator `(lifting [A] ...)`.
While the syntax sugar only supports a single role, the explicit form supports lifting to any number of roles.
`(lifting [A B C] ...)` will lift all literals and free names (referring to plain, i.e. non-choreographic, Clojure vars) within its body to the agreement type `#{A B C}`.
Lifting is the way that Klor reuses existing Clojure code; the "Klor-calls-Clojure" direction of integration.

by default, each choreography's body is implictily wrapped in a `lifting` block that includes all of its roles.
For example, the following is a well-formed choreography as `5` is implicitly lifted to `#{A B}`:

```clojure
(defchor together [A B] (-> #{A B}) []
  5)
```

Which could've also been written explicitly as:

```clojure
(defchor together [A B] (-> #{A B}) []
  (lifting [A B] 5))
```

The more interesting case however is the invocation of a lifted operator.
We have seen this before in `greet` where we invoked `println` at just `A` or `B` separately.
In the following however, we invoke `println` at both `A` *and* `B` with the use of a single invocation:

```clojure
(defchor together-2 [A B] (-> #{A B}) []
  (println "Hello world"))
```

Notice how the expected return type of the invocation of the lifted `println` is an agreement type, which implies that `println` must return the same value at both roles.
This leads us to Klor's idea of **agreement operators** and its accompanying rule: Klor allows the invocation of any operator of agreement type, as long as all of its arguments are of that same agreement type.
Furthermore, the result of such an operation is assumed to also be of the same agreement type.
That is to say, Klor assumes that all **agreement operators are deterministic**, so that when the involved roles carry out the computation with the same arguments, they all end up with the same result.
Note that the operators don't have to be exactly pure, just deterministic.
Side-effects, as in the case of `println`, are allowed as long as the operator produces the same result at each involved role.
**Violating this assumption will make agreement silently fail and lead to bugs.**
Luckily, Clojure highly emphasizes stateless and pure functions so ensuring this condition is generally not an issue.

To confirm, `@(simulate-chor together-2)`:

```
>> A spawned
>> B spawned
>> A: Hello world
>> A exited normally
>> B: Hello world
>> B exited normally
=> {A nil, B nil}
```

Note that Klor doesn't in any way ensure that a value of agreement type is necessarily invokable.
The underlying values are still dynamically typed, so if used in the operator position, Klor happily assumes they can be invoked, just like Clojure (and throws a run-time exception otherwise).

## Narrowing

Opposite to extension, we can also **narrow** agreements: if `x` is a value of agreement type `#{A B C}`, the special operator `(narrow [A B] x)` will produce the same value but of agreement type `#{A B}`.
This allows us to effectively "forget" an agreement in part or whole, which is useful when we want to use the value among a subset of the roles or purely locally.
For example:

```clojure
(defchor inc-locally [A B] (-> B) []
  (B (inc (narrow [B] (A=>B (A 5))))))
```

`A` shares a value with `B`, but `B` then performs the increment operation on its own.
The final type of the body is just `B`, so `A` has no result.
To test, `@(simulate-chor inc-locally)`:

```
>> A spawned
>> B spawned
>> A exited normally
>> A --> B: 5
>> B exited normally
=> {A #function[klor.runtime/noop], B 6}
```

This *copy-then-narrow pattern* should immediately remind us of the move-style communication we used earlier in the tutorial.
Klor's fundamental communication mechanism is actually the **double-arrow copy operator** `(A=>B <expr>)`, which is syntax sugar for the special operator `(copy [A B] <expr>)`.
The single-arrow move operator `(A->B <expr>)` is just a shorthand for the copy-then-narrow pattern: `(narrow [B] (copy [A B] <expr>))`.

However, even if we didn't narrow the agreement established by the copy in the example above, the choreography would still type check:

```clojure
(defchor inc-locally [A B] (-> B) []
  (B (inc (A=>B (A 5)))))
```

This is because Klor allows **agreement subtyping** in certain cases for convenience and readability purposes.
Specifically, the full agreement operator rule only requires that the operator's arguments are all *subtypes* of the operator's agreement type.
This means that the arguments are allowed to be of a *wider* agreement type, as long as they contain *at least* the roles found in the operator's agreement type.

## Conditionals

One of the big issues all choreographic programming lanugages have to overcome is the way they handle conditionals.
The fundamental problem is known as the **problem of knowledge of choice**: if one role makes a decision regarding which branch of a conditional to take, how can the other roles involved be notified of its decision?

Klor gives high importance to the idea of agreement because it provides an elegant way of dealing with this issue.
We simply require that the guard of every conditional be of agreement type and that any roles present in either of its branches be part of that agreement type.
This ensures that all roles will have sufficient knowledge to make the same decision, guaranteeing that their control will continue flowing along the same branch in the choreography.

To start off simple, here's an example where knowledge of choice is **not** required:

```clojure
(defchor check [A B] (-> A B) [x]
  (if (B (even? (A->B x)))
    (B (println "Even!"))
    (B (println "Odd!"))))
```

`B` will receive a number from `A`, and based on a local decision will print either `Even!` or `Odd!`.
`A` is not involved in either of the branches so it does not need to know the outcome of the decision.
We could've also written the example more compactly by pushing the `if` inward:

```clojure
(defchor check [A B] (-> A B) [x]
   (B (println (if (even? (A->B x)) "Even!" "Odd!"))))
```

Now here's a choreography where both of the roles *are* involved in the branches of the conditional, and in such a way that the communication structure depends on the outcome of the decision:

```clojure
(defchor check-2 [A B] (-> A B) [x]
  (if (A=>B (A (even? x)))
    (A->B (A (rand-int 5)))
    (B 42)))
```

Here, `A` will communicate the outcome of its `even?` check to `B`, so both will be able to proceed to the same branch.
In case the number is even, `A` will send `B` a random integer to return as the result.
Otherwise, `B` will return the integer `42` instead.

To test with an odd number, `@(simulate-chor check-2 1)`:

```
>> A spawned
>> B spawned
>> A exited normally
>> A --> B: false
>> B exited normally
=> {A #function[klor.runtime/noop], B 42}
```

Or with an even number, `@(simulate-chor check-2 2)`:

```
>> A spawned
>> B spawned
>> A --> B: true
>> A --> B: 3
>> B exited normally
>> A exited normally
=> {A #function[klor.runtime/noop], B 3}
```

Notice the extra communication in the latter case.

If for some reason we wanted each role to perform the `even?` check on its own rather than communicating a boolean, it would be enough to communicate the number instead by just pushing the communication inward:

```clojure
(defchor check-3 [A B] (-> A B) [x]
  (if (even? (A=>B x))
    (A->B (A (rand-int 5)))
    (B 42)))
```

Achieving these slight but sometimes important variations in behavior is quite easy with Klor's agreement framework.

In [Tutorial: Composition](tutorial-04-composition.md) we go over how to effectively reuse and compose choreographies.
