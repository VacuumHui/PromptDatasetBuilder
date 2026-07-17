#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

required = [
    ROOT / "app/src/main/AndroidManifest.xml",
    ROOT / "app/src/main/java/com/promptdatasetbuilder/app/MainActivity.kt",
    ROOT / "app/src/main/java/com/promptdatasetbuilder/app/network/CivitaiApiClient.kt",
    ROOT / "app/src/main/java/com/promptdatasetbuilder/app/network/CivitaiResponseParser.kt",
    ROOT / ".github/workflows/android.yml",
]
for path in required:
    if not path.exists():
        errors.append(f"Missing required file: {path.relative_to(ROOT)}")

for path in ROOT.rglob("*.xml"):
    try:
        ET.parse(path)
    except Exception as exc:
        errors.append(f"Invalid XML {path.relative_to(ROOT)}: {exc}")

for path in ROOT.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if "import androidx.compose.foundation.layout.weight" in text:
        errors.append(f"Forbidden internal weight import: {path.relative_to(ROOT)}")
    if "com.civitared.promptdataset" in text:
        errors.append(f"Old package remains: {path.relative_to(ROOT)}")

api_client = ROOT / "app/src/main/java/com/promptdatasetbuilder/app/network/CivitaiApiClient.kt"
if api_client.exists():
    text = api_client.read_text(encoding="utf-8")
    for marker in (
        "image.getInfinite",
        "image.getGenerationData",
        "browsingLevel",
        "x-client-version",
        "AppSettings.PRIMARY_SOURCE",
        "AppSettings.FALLBACK_SOURCE",
    ):
        if marker not in text:
            errors.append(f"Network implementation is missing marker: {marker}")
    if "/api/v1/images" in text:
        errors.append("Old REST image feed remains in the network client")

models = ROOT / "app/src/main/java/com/promptdatasetbuilder/app/data/Models.kt"
if models.exists():
    text = models.read_text(encoding="utf-8")
    if 'https://civita.red' not in text or 'https://civitai.com' not in text:
        errors.append("Primary and fallback source hosts are not both configured")

app_gradle = ROOT / "app/build.gradle.kts"
if app_gradle.exists():
    text = app_gradle.read_text(encoding="utf-8")
    if 'versionName = "1.1.0"' not in text:
        errors.append("Expected app version 1.1.0")
    if 'testImplementation("org.json:json:20240303")' not in text:
        errors.append("Pure JVM org.json dependency is missing for local parser tests")

if errors:
    print("PROJECT VERIFICATION FAILED")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Project verification passed")
