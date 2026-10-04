# Support code evaluated once inside every pooled GraalPy context.
#
# The Java side talks to the interpreter exclusively through the two functions
# exported at the end of this file:
#
#   prepare(source, filename) -> Prepared
#       Parses and compiles a script (LRU cached per context). The returned object
#       exposes `names`, the identifiers the script reads, so the Java side only has
#       to look up those names in the javax.script bindings instead of copying every
#       binding into Python.
#
#   flush()
#       Flushes Python's stdout and stderr buffers into the redirected streams.
#
#   execute(prepared, keys, values, write_back) -> [has_result, result, changes]
#       Runs the compiled script in a fresh module namespace seeded with the given
#       bindings. `changes` is a flat [name, value, name, value, ...] list of the
#       top-level variables the script created or re-bound.
#
# Every evaluation gets its own globals dict, so scripts never see variables left
# behind by earlier evaluations that happened to run in the same context.

import ast
import builtins
import collections
import sys
import types

RESULT_NAME = "__jsr223_result__"

# Values of these types are never written back to the bindings: they are program
# structure (imports, def, class), not data.
_STRUCTURAL_TYPES = (
    types.ModuleType,
    types.FunctionType,
    types.BuiltinFunctionType,
    types.MethodType,
    type,
)


class Prepared:
    __slots__ = ("code", "names", "has_result", "filename")

    def __init__(self, code, names, has_result, filename):
        self.code = code
        self.names = names
        self.has_result = has_result
        self.filename = filename


class _Cache:
    def __init__(self, capacity):
        self.capacity = capacity
        self.entries = collections.OrderedDict()

    def get(self, key):
        entry = self.entries.get(key)
        if entry is not None:
            self.entries.move_to_end(key)
        return entry

    def put(self, key, value):
        if self.capacity <= 0:
            return
        self.entries[key] = value
        self.entries.move_to_end(key)
        while len(self.entries) > self.capacity:
            self.entries.popitem(last=False)


def _compile(source, filename):
    tree = ast.parse(source, filename, "exec")

    # JSR-223 expects eval() to return the value of the script. Python's exec() has no
    # result, so a trailing expression statement is rewritten into an assignment to a
    # hidden variable (the same trick interactive shells use).
    has_result = bool(tree.body) and isinstance(tree.body[-1], ast.Expr)
    if has_result:
        last = tree.body[-1]
        tree.body[-1] = ast.copy_location(
            ast.Assign(
                targets=[ast.Name(id=RESULT_NAME, ctx=ast.Store())],
                value=last.value,
            ),
            last,
        )
        ast.fix_missing_locations(tree)

    names = sorted({node.id for node in ast.walk(tree) if isinstance(node, ast.Name)} - {RESULT_NAME})
    code = compile(tree, filename, "exec", dont_inherit=True)
    return Prepared(code, tuple(names), has_result, filename)


def _flush():
    sys.stdout.flush()
    sys.stderr.flush()


def _is_writable(name, value):
    return not name.startswith("_") and not isinstance(value, _STRUCTURAL_TYPES)


def _make_api(cache_size):
    cache = _Cache(cache_size)

    def prepare(source, filename):
        key = (source, filename)
        prepared = cache.get(key)
        if prepared is None:
            prepared = _compile(source, filename)
            cache.put(key, prepared)
        return prepared

    def execute(prepared, keys, values, write_back):
        namespace = {
            "__name__": "__main__",
            "__file__": prepared.filename,
            "__builtins__": builtins,
        }
        injected = {}
        for i in range(len(keys)):
            key = str(keys[i])
            value = values[i]
            namespace[key] = value
            # Keep the stored object, not `value`: identity comparison below must use
            # exactly what the script saw.
            injected[key] = namespace[key]

        # No try/except here: GraalPy 25.4 fails to build the stack trace of an exception re-raised
        # through a handler in this frame ("Bytecode index out of range"), losing the script's error.
        # The Java side calls flush() afterwards instead.
        exec(prepared.code, namespace)

        result = namespace.pop(RESULT_NAME, None)

        changes = []
        if write_back:
            for name, value in namespace.items():
                if not _is_writable(name, value):
                    continue
                if name in injected and injected[name] is value:
                    continue
                changes.append(name)
                changes.append(value)

        return [prepared.has_result, result, changes]

    return {"prepare": prepare, "execute": execute, "flush": _flush}


_make_api
