# Spin environment for GraalPy scripts in CIB seven.
# Replaces Spin's Jython environment (script/env/python/spin.py), which GraalPy cannot run.
import java

_spin = java.type("de.bigmichi1.graalpy.cibseven.GraalPySpin")
S = _spin.S
JSON = _spin.JSON
XML = _spin.XML
