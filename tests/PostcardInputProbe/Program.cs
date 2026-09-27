using System.Collections;
using Celeste.Mod.MouseUI;

bool confirm = false, tap = false, completed = false, disposed = false;
var nested = new object[] { "animation" }.GetEnumerator();
IEnumerator Original() {
    try {
        yield return nested;
        yield return 0.75f;
        while (!confirm) yield return null;
        completed = true;
    } finally { disposed = true; }
}
IDisposable Begin() {
    if (!tap) return null;
    tap = false; confirm = true;
    return new Scope(() => confirm = false);
}
using (var routine = new ScopedInputRoutine(Original(), Begin)) {
    if (!routine.MoveNext() || routine.Current != nested) throw new Exception("Nested animation changed");
    if (!routine.MoveNext() || !Equals(routine.Current, 0.75f)) throw new Exception("Delay changed");
    if (!routine.MoveNext() || completed) throw new Exception("Confirmed without a tap");
    // The external owner advances the routine; the postcard entity never updates.
    tap = true;
    if (routine.MoveNext() || !completed || confirm || tap) throw new Exception("Tap was not scoped to confirmation");
}
if (!disposed) throw new Exception("Original coroutine was not disposed");
IEnumerator Failure() { if (confirm) throw new InvalidOperationException(); yield return null; }
tap = true;
using (var routine = new ScopedInputRoutine(Failure(), Begin)) {
    try { routine.MoveNext(); throw new Exception("Missing expected exception"); }
    catch (InvalidOperationException) { }
    if (confirm) throw new Exception("Input stuck after coroutine failure");
}
Console.WriteLine("PASS: externally owned postcard confirmation, original yields, disposal and input cleanup");
sealed class Scope(Action cleanup) : IDisposable { public void Dispose() => cleanup(); }
