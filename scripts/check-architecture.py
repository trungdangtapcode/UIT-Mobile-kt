from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
failures = []
for module in (root / "feature").iterdir():
    text = (module / "build.gradle.kts").read_text()
    for dependency in re.findall(r'project\("([^" ]+)"\)', text):
        if dependency not in {":core:domain", ":core:model", ":core:common", ":core:designsystem"}:
            failures.append(f"{module.name} depends on {dependency}")
for module, allowed in {"model": set(), "domain": {":core:model", ":core:common"}}.items():
    text = (root / "core" / module / "build.gradle.kts").read_text()
    for dependency in re.findall(r'project\("([^" ]+)"\)', text):
        if dependency not in allowed: failures.append(f"{module} depends on {dependency}")
assert not failures, "\n".join(failures)
print("Architecture boundaries verified")
