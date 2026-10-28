"""Tests for tools/side_lint.py: client-only names outside a client package are caught; comments, strings,
text blocks, client packages and allow-marked lines are not. Run: python tools/tests/test_side_lint.py (from anywhere)."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import side_lint  # noqa: E402

results = []


def case(name, ok, detail=""):
    results.append(bool(ok))
    print(("ok    " if ok else "FAIL  ") + name + ("" if ok else f": {detail}"))


COMMON = "src/main/java/dev/champ/kimetsu/combat/Thing.java"
CLIENT = "src/main/java/dev/champ/kimetsu/client/render/Thing.java"


def check(text, rel=COMMON):
    return side_lint.check_text(rel, text)


p = check("package a;\n\nimport net.minecraft.client.Minecraft;\n")
case("an import of net.minecraft.client in common code is caught, with its line", len(p) == 1 and ":3:" in p[0], p)
p = check("class A { void f() { var m = net.minecraft.client.Minecraft.getInstance(); } }\n")
case("a fully qualified use is caught", len(p) == 1, p)
for pkg in ("com.mojang.blaze3d.systems.RenderSystem", "com.mojang.renderpearl.Device",
            "net.neoforged.neoforge.client.event.RenderGuiEvent"):
    p = check(f"import {pkg};\n")
    case(f"{pkg.rsplit('.', 1)[0]} is client-only", len(p) == 1, p)
case("the same import inside a client package passes", check("import net.minecraft.client.Minecraft;\n", CLIENT) == [])
case("a client folder deeper in the path counts", check("import net.minecraft.client.Minecraft;\n",
                                                        "src/main/java/a/client/hud/x/Y.java") == [])
case("a class named Client* outside a client folder is still checked",
     len(check("import net.minecraft.client.Minecraft;\n", "src/main/java/a/ClientBridge.java")) == 1)
case("a folder named clientside isn't a client package",
     len(check("import net.minecraft.client.Minecraft;\n", "src/main/java/a/clientside/B.java")) == 1)
case("a line comment mentioning it passes", check("int x; // see net.minecraft.client.Minecraft\n") == [])
case("a javadoc block mentioning it passes",
     check("/**\n * Unlike {@link net.minecraft.client.Minecraft}, this runs on the server.\n */\nclass A {}\n") == [])
case("a string literal passes", check('String s = "net.minecraft.client.Minecraft";\n') == [])
case("an escaped quote doesn't end the string early",
     check('String s = "a \\" net.minecraft.client.X \\"";\n') == [])
tb = 'String t = """\n    net.minecraft.client.Minecraft\n    """;\nimport net.minecraft.client.Minecraft;\n'
p = check(tb)
case("a text block passes, and a real use after it keeps its line number", len(p) == 1 and ":4:" in p[0], p)
p = check("char q = '\"'; Object o = net.minecraft.client.Minecraft.getInstance();\n")
case("a char literal holding a quote doesn't hide the code after it", len(p) == 1, p)
p = check("/* net.minecraft.client.A */ import net.minecraft.client.Minecraft;\n")
case("code after a block comment on the same line is still checked", len(p) == 1, p)
case("an allow marker on the line passes",
     check("import net.minecraft.client.Minecraft; // side-lint: allow reflection probe, guarded by FMLEnvironment\n") == [])
case("an allow marker with no reason doesn't count",
     len(check("import net.minecraft.client.Minecraft; // side-lint: allow\n")) == 1)
p = check("import dev.champ.kimetsu.client.ClientCombat;\n")
case("common code importing the mod's own client package is caught", len(p) == 1 and "kimetsu.client" in p[0], p)
case("a deeper mod client package and a wildcard are caught",
     len(check("import dev.champ.kimetsu.net.client.Handler;\nimport dev.champ.kimetsu.client.*;\n")) == 2)
case("a fully qualified use of the mod's client class is caught",
     len(check("Object o = dev.champ.kimetsu.client.KimetsuClient.INSTANCE;\n")) == 1)
case("the mod's client package inside a client package passes",
     check("import dev.champ.kimetsu.client.ClientCombat;\n", CLIENT) == [])
case("a mod class merely named Client* passes", check("import dev.champ.kimetsu.combat.ClientInputRules;\n") == [])
case("a lookalike package name passes", check("import mynet.minecraft.client.X;\nimport foo.net.minecraft.client.Y;\n") == [])
case("a non-Java file is ignored", side_lint.check_text("src/main/resources/x.json", "net.minecraft.client.X") == [])
case("net.minecraft.server and other vanilla packages pass",
     check("import net.minecraft.server.level.ServerLevel;\nimport net.minecraft.world.entity.Entity;\n") == [])

tmp = Path(tempfile.mkdtemp())
try:
    bad = tmp / "src/main/java/a/b/Bad.java"
    good = tmp / "src/main/java/a/client/Good.java"
    bad.parent.mkdir(parents=True)
    good.parent.mkdir(parents=True)
    bad.write_text("package a.b;\nimport net.minecraft.client.Minecraft;\nclass Bad {}\n")
    good.write_text("package a.client;\nimport net.minecraft.client.Minecraft;\nclass Good {}\n")
    r = subprocess.run([sys.executable, str(ROOT / "tools/side_lint.py"), "--root", str(tmp)],
                       capture_output=True, text=True)
    case("CLI: exit 1 and the file:line on a tree with a violation",
         r.returncode == 1 and "src/main/java/a/b/Bad.java:2:" in r.stdout, r.stdout + r.stderr)
    r = subprocess.run([sys.executable, str(ROOT / "tools/side_lint.py"), "--root", str(tmp), str(good)],
                       capture_output=True, text=True)
    case("CLI: exit 0 when the only file given is in a client package", r.returncode == 0, r.stdout + r.stderr)
    r = subprocess.run([sys.executable, str(ROOT / "tools/side_lint.py"), "--root", str(tmp), str(bad)],
                       capture_output=True, text=True, cwd=str(tmp))
    case("CLI: an absolute path to one file is judged by its place in the repo",
         r.returncode == 1 and "a/b/Bad.java:2:" in r.stdout, r.stdout + r.stderr)
finally:
    shutil.rmtree(tmp, ignore_errors=True)

r = subprocess.run([sys.executable, str(ROOT / "tools/side_lint.py")], capture_output=True, text=True)
case("the real mod passes", r.returncode == 0, r.stdout + r.stderr)
print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
