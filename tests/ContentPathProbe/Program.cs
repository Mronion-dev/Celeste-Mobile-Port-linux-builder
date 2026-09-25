using Mono.Cecil;
using System.Text.Json;

try {
var root = Path.GetFullPath(args[0]);
using var module = ModuleDefinition.ReadModule(Path.Combine(root, "CelesteRuntime/celeste/Celeste.dll"));
var rootSetter = module.GetType("Monocle.Engine").Methods.Where(m => m.IsConstructor && m.HasBody)
    .SelectMany(m => m.Body.Instructions).Single(i => i.Operand is MethodReference m &&
        m.DeclaringType.FullName == "Microsoft.Xna.Framework.Content.ContentManager" && m.Name == "set_RootDirectory");
var contentRoot = (string)rootSetter.Previous.Operand;
// The loader sets AssemblyDirectory to /; Everest resolves PathGame to /libsdl.
static string Combine(string left, string right) => right.StartsWith('/') ? right : left.TrimEnd('/') + "/" + right;
var engineRoot = Combine("/", contentRoot);
var everestRoot = Combine("/libsdl", contentRoot);
var source = File.ReadAllText(Path.Combine(root, "CelesteRuntime/data.js"));
var start = source.IndexOf("var package_metadata = ") + "var package_metadata = ".Length;
var end = source.IndexOf(";", start);
using var manifest = JsonDocument.Parse(source[start..end]);
var count = 0;
foreach (var file in manifest.RootElement.EnumerateArray()) {
    var filename = file.GetProperty("filename").GetString()!;
    if (!filename.StartsWith("/libsdl/Content/Dialog/") || !filename.EndsWith(".txt")) continue;
    var relative = filename["/libsdl/Content/".Length..];
    var enginePath = Combine(engineRoot, relative);
    // Match Language._GetLanguageText's prefix, extension and Dialog/ stripping.
    var key = enginePath.Substring(everestRoot.Length + 1);
    key = key.Substring(0, key.Length - 4);
    var language = key.Substring("Dialog/".Length);
    if (enginePath != filename || key != relative[..^4] || language.Length == 0)
        throw new Exception($"Inconsistent language path: {enginePath}, Everest root {everestRoot}, key {key}");
    count++;
}
if (count == 0) throw new Exception("No language files found");
Console.WriteLine($"PASS: {count} packaged language paths agree between Engine and Everest ({contentRoot}).");
} catch (Exception error) {
    Console.Error.WriteLine(error.Message);
    Environment.ExitCode = 1;
}
