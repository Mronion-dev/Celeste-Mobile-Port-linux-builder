using System;
using System.Collections;

namespace Celeste.Mod.MouseUI;

// Keep temporary input alive while the consumer coroutine runs, regardless of
// which entity/scene owns it. Preserve yielded delays and nested enumerators.
internal sealed class ScopedInputRoutine : IEnumerator, IDisposable {
    private readonly IEnumerator routine;
    private readonly Func<IDisposable> beginInput;
    public ScopedInputRoutine(IEnumerator routine, Func<IDisposable> beginInput) {
        this.routine = routine;
        this.beginInput = beginInput;
    }
    public object Current => routine.Current;
    public bool MoveNext() {
        using (beginInput()) return routine.MoveNext();
    }
    public void Reset() => throw new NotSupportedException();
    public void Dispose() => (routine as IDisposable)?.Dispose();
}
