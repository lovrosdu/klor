# Reference: Projection Interface

## Execution

Choreographic programming assumes the **concurrent execution** of all roles in a choreography.
Abstractly, all subexpressions of a choreography are evaluated **out-of-order up to role dependency**.
This means that their evaluation can be arbitrarily interleaved, except that sequentiality is maintained between all expressions located at the same role, including any **communication actions** initiated by the role (sends and receives to/from other roles).
Receives are assumed to be **blocking (synchronous)** while sends can be either blocking or non-blocking.

Practically, the way this kind of execution is achieved is by **projecting** a choreography to a separate Clojure function for each role and executing all of them concurrently via some concurrency mechanism -- multiple threads, processes, nodes, any combination thereof, etc.
Each projection retains only the subexpressions relevant to the particular role and incorporates the necessary communication actions to interact with other roles as specified by the choreography.

## Projection

A full treatment of the theory of projection with all of its details and properties is out of the scope of this document, but we try to give a working summary here.

In general, the projection of a Klor expression at a role depends on which of its parts are annotated with that role.
For example, an expression might be projected differently for a role `r` depending on whether it is **located** at `r`, **only involves** `r` in its subexpressions, or **doesn't involve** `r` at all.
Additionally, whenever an expression located at `r` needs the result of another expression located at `s`, the projections of both will have to include communication actions so that `s` can send its result to `r` and `r` can receive it from `s`.

Projection works **recursively** through the subexpressions of a Klor expression and applies the above considerations at every step in order to produce code that contains all of the necessary expressions and communication actions described by the choreography.
The rough idea of the process of projecting an expression for a role is:

- If the expression is **located** at the role, the projection usually retains the expression, projects all appropriate subexpressions recursively, and arranges any necessary communication actions to receive the values of subexpressions that are located at a different role,
- Otherwise, if the expression **only involves** the role in its subexpressions, the projection is usually required to evaluate to `nil`, but must again project all appropriate subexpressions recursively and include any necessary communication actions to first send the values of the subexpressions to the role of the enclosing expression,
- Otherwise, if the expression **doesn't involve** the role at all, the projection is `nil`.

The above is only meant to give a general understanding of Klor's projection and does not precisely describe the details of some special operators, such as `do` or `dance`.
