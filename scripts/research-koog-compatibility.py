#!/usr/bin/env python3
"""Opt-in isolated Android compiler probe; never changes the repository toolchain."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="uit-koog-probe-") as directory:
    work = Path(directory)
    (work / "settings.gradle.kts").write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = "uit-koog-probe"\n')
    (work / "build.gradle.kts").write_text('''plugins { id("com.android.library") version "8.9.2"; kotlin("android") version "2.1.20" }
android { namespace = "vn.edu.uit.spike"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies { implementation("ai.koog:agents-core:1.3.0") }
''')
    source = work / "src/main/kotlin/Probe.kt"
    source.parent.mkdir(parents=True)
    source.write_text('package vn.edu.uit.spike\nimport ai.koog.agents.core.agent.AIAgent\nfun agentType() = AIAgent::class.java.name\n')
    process = subprocess.run([str(ROOT / "gradlew"), "-p", str(work), "compileDebugKotlin"], env=os.environ.copy(), capture_output=True, text=True, timeout=600)
    text = process.stdout + process.stderr
    mismatch = "metadata is 2.3.0, expected version is 2.1.0" in text
    print(json.dumps({"koog": "1.3.0", "artifact": "ai.koog:agents-core", "kotlin": "2.1.20", "agp": "8.9.2", "minSdk": 26, "exitCode": process.returncode, "metadataMismatchObserved": mismatch, "compiled": process.returncode == 0}, indent=2))
    # A failed probe is an observation, not a request to disable metadata validation.
    if process.returncode != 0 and not mismatch:
        raise SystemExit("The probe failed for another reason; inspect the temporary build locally.")
