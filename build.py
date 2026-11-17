"""Builds the mod without IntelliJ: compiles src/, writes jars/MoveByText.jar and runs the offline tests.

    python build.py                    compile, write the jar, run AllOfflineTests and the Python tests
    python build.py --no-test          compile and write the jar only
    python build.py --test-only        compile and run the tests, leave the jar alone
    python build.py --filter history   run only the Java tests whose name contains "history"

Every build also runs SandboxCheck on what it built: a JDK class Starsector refuses to load for mods (java.io.File,
most of java.io and java.lang.reflect...) fails the build, because the game would only say so when running that code.

Needs Python 3 (standard library only) and a JDK 17 or newer: JAVA_HOME, else the newest one in ~/.jdks, else javac
on the PATH. The classpath is the Starsector jars (starsector-core) and RolflectionLib, as in the IntelliJ project.
Everything is compiled into out/build/, which is wiped first, so classes of deleted sources never reach the jar.
Exits with 1 when compiling, the sandbox check or a test fails.

The Python tests are test/python/ (unittest) and nlp/spacy/test_distance.py; some Java tests run Python too, with
this same interpreter (-Dmovebytext.python). nlp/rasa/test_handwritten.py uses find_jdk(), classpath() and javac()
from here.
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys

MOD = os.path.dirname(os.path.abspath(__file__))
EXE = ".exe" if os.name == "nt" else ""
CORE = os.path.normpath(os.path.join(MOD, "..", "..", "starsector-core"))
MOD_LIBS = [os.path.normpath(os.path.join(MOD, "..", "RolflectionLib-SS-main", "jars", "RolflectionLibJAR.jar"))]
BUILD = os.path.join(MOD, "out", "build")
JAR = os.path.join(MOD, "jars", "MoveByText.jar")
JAVA_RELEASE = "17"  # the game's own JRE is Java 17
TEST_MAIN = "KirizsKeve.MoveByText.AllOfflineTests"


def find_jdk():
    """The bin folder of a JDK: JAVA_HOME, else the newest in ~/.jdks, else javac on the PATH."""
    homes = [os.environ["JAVA_HOME"]] if os.environ.get("JAVA_HOME") else []
    homes += sorted(glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "*")), reverse=True)
    for home in homes:
        if os.path.exists(os.path.join(home, "bin", "javac" + EXE)):
            return os.path.join(home, "bin")
    javac_path = shutil.which("javac")
    if javac_path:
        return os.path.dirname(javac_path)
    sys.exit("No JDK found. Set JAVA_HOME to a JDK 17 or newer.")


def classpath():
    """The Starsector jars and the mods this one needs, as one classpath string."""
    jars = sorted(glob.glob(os.path.join(CORE, "*.jar")))
    if not jars:
        sys.exit("No Starsector jars in %s. This folder must be in Starsector/mods/." % CORE)
    return os.pathsep.join(jars + [p for p in MOD_LIBS if os.path.exists(p)])


def javac(jdk, sources, out_dir, cp):
    """Compiles the sources into out_dir; exits when javac reports an error."""
    os.makedirs(out_dir, exist_ok=True)
    argfile = os.path.join(out_dir, "..", os.path.basename(out_dir) + "-sources.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        # Forward slashes: a backslash inside quotes is an escape character in javac argument files
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in sources))
    result = subprocess.run([os.path.join(jdk, "javac" + EXE), "--release", JAVA_RELEASE, "-encoding", "UTF-8",
                             "-nowarn", "-d", out_dir, "-cp", cp, "@" + argfile])
    if result.returncode != 0:
        sys.exit("javac failed")
    print("compiled %d source files into %s" % (len(sources), shown(out_dir)), flush=True)


def shown(path):
    """A path for messages: relative to the mod folder when it is inside it."""
    rel = os.path.relpath(os.path.abspath(path), MOD)
    return os.path.abspath(path) if rel.startswith("..") else rel


def java_files(folder):
    return sorted(glob.glob(os.path.join(MOD, folder, "**", "*.java"), recursive=True))


def write_jar(jdk, classes):
    os.makedirs(os.path.dirname(JAR), exist_ok=True)
    tmp = JAR + ".tmp"
    result = subprocess.run([os.path.join(jdk, "jar" + EXE), "--create", "--file", tmp, "-C", classes, "."])
    if result.returncode != 0:
        sys.exit("jar failed")
    os.replace(tmp, JAR)  # only a complete jar replaces the old one
    print("wrote %s" % shown(JAR), flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build MoveByText and run its offline tests.")
    parser.add_argument("--no-test", action="store_true", help="only compile and write the jar")
    parser.add_argument("--test-only", action="store_true", help="compile and test, but don't write the jar")
    parser.add_argument("--filter", help="run only the tests whose name contains this")
    args = parser.parse_args()

    jdk = find_jdk()
    cp = classpath()
    shutil.rmtree(BUILD, ignore_errors=True)
    classes = os.path.join(BUILD, "classes")
    javac(jdk, java_files("src"), classes, cp)
    if not args.test_only:
        write_jar(jdk, classes)
    sandbox_check(jdk, classes if args.test_only else JAR)
    if args.no_test:
        return

    test_classes = os.path.join(BUILD, "test-classes")
    javac(jdk, java_files("test"), test_classes, classes + os.pathsep + cp)
    # Some Java tests check the Python tools; they run them with this same interpreter
    command = [os.path.join(jdk, "java" + EXE), "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
               "-Dmovebytext.python=" + sys.executable,
               "-cp", os.pathsep.join([test_classes, classes, cp]), TEST_MAIN]
    if args.filter:
        command.append(args.filter)
    java_tests = subprocess.run(command, cwd=MOD).returncode
    python_tests = 0 if args.filter else python_checks()
    sys.exit(1 if java_tests or python_tests else 0)


def sandbox_check(jdk, target):
    """Fails the build when the code uses a JDK class that Starsector refuses to load for mods (see SandboxCheck)."""
    tools = os.path.join(BUILD, "tools")
    source = os.path.join(MOD, "test", "KirizsKeve", "MoveByText", "SandboxCheck.java")
    javac(jdk, [source], tools, "")
    result = subprocess.run([os.path.join(jdk, "java" + EXE), "-cp", tools, "KirizsKeve.MoveByText.SandboxCheck",
                             target], cwd=MOD)
    if result.returncode != 0:
        sys.exit("the sandbox check failed: the game would refuse to load part of the mod")


def python_checks():
    """The checks written in Python that need nothing but the standard library; the number that failed.

    test/python/ holds unittest tests of the training tools and a syntax check of every .py file;
    nlp/spacy/test_distance.py checks rules.py's distance() against the table Java is tested against too.
    """
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1")  # no __pycache__ folders in the mod
    runs = [[sys.executable, os.path.join(MOD, "nlp", "spacy", "test_distance.py")],
            [sys.executable, "-m", "unittest", "discover", "-s", os.path.join(MOD, "test", "python"), "-t",
             os.path.join(MOD, "test", "python")]]
    failed = 0
    for command in runs:
        failed += subprocess.run(command, cwd=MOD, env=env).returncode != 0
    return failed


if __name__ == "__main__":
    main()
