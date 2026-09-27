using Mono.Cecil;
using Mono.Cecil.Cil;

try {
    foreach (var relative in new[] { "celeste/Celeste.dll", "celeste/Celeste.Mod.mm.dll", "celeste/Everest/Celeste.Mod.mm.dll" }) {
        using var module = ModuleDefinition.ReadModule(Path.Combine(args[0], "CelesteRuntime", relative), new ReaderParameters { InMemory = true });
        var welcome = module.GetType("Celeste.Mod.UI.OuiOOBE");
        var start = welcome.Methods.Single(m => m.Name == "IsStart");
        if (start.Body.Instructions.Count != 2 || start.Body.Instructions[0].OpCode != OpCodes.Ldc_I4_0 || start.Body.Instructions[1].OpCode != OpCodes.Ret)
            throw new Exception(relative + ": automatic welcome screen is still enabled");
        if (!welcome.Methods.Single(m => m.Name == "CreateMenu").HasBody)
            throw new Exception(relative + ": optional welcome settings were removed");
        var type = module.GetTypes().Single(t => t.FullName is "Monocle.VirtualTexture" or "Monocle.patch_VirtualTexture");
        var instructions = type.Methods.Where(m => m.HasBody).SelectMany(m => m.Body.Instructions).ToArray();
        var writes = instructions.Where(i => i.OpCode == OpCodes.Stsfld && i.Operand is FieldReference f && f.Name == "ftlEnabled").ToArray();
        if (writes.Length == 0 || writes.Any(i => i.Previous.OpCode != OpCodes.Ldc_I4_0))
            throw new Exception(relative + ": parallel texture loading can still become enabled");
        var finish = instructions.Single(i => i.OpCode == OpCodes.Stsfld && i.Operand is FieldReference f && f.Name == "ftlFinish");
        if (finish.Previous.OpCode != OpCodes.Newobj || finish.Previous.Previous.OpCode != OpCodes.Ldc_I4_1)
            throw new Exception(relative + ": texture completion event is not initially signaled");
        var wait = type.Methods.Single(m => m.Name == "WaitFinishFastTextureLoading");
        if (!wait.Body.Instructions.Any(i => i.Operand is MethodReference m && m.Name == "Wait"))
            throw new Exception(relative + ": normal completion synchronization was removed");
        Console.WriteLine("PASS: synchronous texture path and signaled completion retained: " + relative);
    }
} catch (Exception error) {
    Console.Error.WriteLine(error.Message);
    Environment.ExitCode = 1;
}
