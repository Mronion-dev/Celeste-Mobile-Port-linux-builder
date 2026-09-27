using Mono.Cecil;
using System.IO.Compression;

try {
    var root = Path.GetFullPath(args[0]);
    using var module = ModuleDefinition.ReadModule(Path.Combine(root, "CelesteMobileMods/MobileTweaks/bin/MobileTweaks.dll"));
    var type = module.GetType("Celeste.Mod.MobileTweaks.MobileTweaksModule");
    var skip = type.Methods.Single(m => m.Name == "ForceSkipEverestOobe");
    if (!skip.Body.Instructions.Any(i => i.Operand is MethodReference m && m.Name == "set_CurrentVersion" && m.DeclaringType.Name == "CoreModuleSettings"))
        throw new Exception("Built MobileTweaks does not set Everest's onboarding completion flag");
    foreach (var name in new[] { "Load", "Initialize", "OnOverworldReloadMenus" }) {
        var method = type.Methods.Single(m => m.Name == name);
        var calls = method.Body.Instructions.Where(i => i.Operand is MethodReference).Select(i => (MethodReference)i.Operand).ToArray();
        if (calls.First().Name != "ForceSkipEverestOobe") throw new Exception(name + " does not skip onboarding before other startup work");
    }
    using var apk = ZipFile.OpenRead(args[1]);
    foreach (var mod in new[] { "MobileBridge", "MobileTweaks", "MouseUI" }) {
        var zipEntry = apk.GetEntry("assets/CelesteRuntime/Mods/" + mod + ".zip") ?? throw new Exception("APK missing " + mod);
        using var buffer = new MemoryStream();
        using (var input = zipEntry.Open()) input.CopyTo(buffer);
        if (!buffer.ToArray().SequenceEqual(File.ReadAllBytes(Path.Combine(root, "CelesteRuntime/Mods/" + mod + ".zip"))))
            throw new Exception("Stale APK mod ZIP: " + mod);
        buffer.Position = 0;
        using var zip = new ZipArchive(buffer);
        if (mod == "MobileTweaks") {
            using var text = new StreamReader(zip.GetEntry("Dialog/English.txt")!.Open());
            var dialog = text.ReadToEnd();
            if (!dialog.Contains("AUTOSAVING_DESC_PC=") || !dialog.Contains("Please do not turn off your phone") || !dialog.Contains("while this icon is visible"))
                throw new Exception("APK missing the phone autosave wording");
        }
        if (zip.Entries.Any(e => e.FullName.StartsWith("bin/"))) throw new Exception("Forbidden bin staging");
        using var dll = new MemoryStream();
        using (var input = zip.GetEntry(mod + ".dll")!.Open()) input.CopyTo(dll);
        if (!dll.ToArray().SequenceEqual(File.ReadAllBytes(Path.Combine(root, "CelesteMobileMods", mod, "bin", mod + ".dll"))))
            throw new Exception("ZIP contains stale DLL: " + mod);
        if (mod == "MouseUI") {
            dll.Position = 0;
            using var mouse = ModuleDefinition.ReadModule(dll);
            var mouseType = mouse.GetType("Celeste.Mod.MouseUI.MouseUIModule");
            var load = mouseType.Methods.Single(m => m.Name == "Load");
            if (!load.Body.Instructions.Any(i => i.Operand is MethodReference m && m.Name == "add_DisplayRoutine" && m.DeclaringType.Name == "Postcard"))
                throw new Exception("APK does not hook the postcard confirmation coroutine");
            Console.WriteLine("PASS: APK hooks Postcard.DisplayRoutine, not the unrelated entity update");
        }
        if (mod == "MobileBridge") {
            dll.Position = 0;
            using var bridge = ModuleDefinition.ReadModule(dll);
            var api = bridge.GetType("Celeste.Mod.MobileBridge.MobileBridgeApi");
            foreach (var name in new[] { "JsSetGameplay", "JsSetOption", "JsOpenModBrowser", "JsConsumeTouchScroll" }) {
                var method = api.Methods.Single(m => m.Name == name);
                if (!method.HasBody || !method.Body.Instructions.Any(i => i.Operand is MethodReference m && m.DeclaringType.Namespace.StartsWith("System.Runtime.InteropServices.JavaScript")))
                    throw new Exception("APK has desktop/no-op MobileBridge method: " + name + "\n" + string.Join("\n", method.Body.Instructions));
            }
            Console.WriteLine("PASS: APK MobileBridge contains browser interop, not desktop stubs");
        }
        Console.WriteLine("PASS: newly built " + mod + " DLL is at the root of the ZIP inside the APK");
    }
    Console.WriteLine("PASS: MobileTweaks skips onboarding before Load, Initialize and ReloadMenus");
} catch (Exception error) {
    Console.Error.WriteLine(error.Message);
    Environment.ExitCode = 1;
}
