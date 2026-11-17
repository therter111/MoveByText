"""A syntax check of every Python file in the mod, so a mistake in a script that only runs in the Rasa or spaCy
environment shows anyway. It imports none of them, so it runs even where they couldn't. Standard library only;
build.py runs it:  python -m unittest discover -s test/python
"""

import glob
import os
import unittest

MOD = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Generated or third-party code that isn't the mod's own
SKIP = ("__pycache__", os.sep + "results" + os.sep, os.sep + "models" + os.sep, os.sep + ".rasa" + os.sep,
        os.sep + "out" + os.sep, os.sep + ".venv")


class EveryScript(unittest.TestCase):

    def test_every_python_file_compiles(self):
        files = [f for f in glob.glob(os.path.join(MOD, "**", "*.py"), recursive=True) if not any(s in f for s in SKIP)]
        self.assertGreater(len(files), 15, "too few .py files found")
        for path in files:
            with self.subTest(os.path.relpath(path, MOD)):
                with open(path, encoding="utf-8") as f:
                    source = f.read()
                compile(source, path, "exec")  # a SyntaxError fails the test; nothing is written to disk


if __name__ == "__main__":
    unittest.main()
