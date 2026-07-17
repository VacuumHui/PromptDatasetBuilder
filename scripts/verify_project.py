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

api_client = (ROOT / "app/src/main/java/com/promptdatasetbuilder/app/network/CivitaiApiClient.kt")
if api_client.exists():
    text = api_client.read_text(encoding="utf-8")
    if "AppSettings.API_ENDPOINT" not in text:
        errors.append("API client does not use the fixed official endpoint")

models = ROOT / "app/src/main/java/com/promptdatasetbuilder/app/data/Models.kt"
if models.exists():
    text = models.read_text(encoding="utf-8")
    if 'https://civitai.com/api/v1/images' not in text:
        errors.append("Official Civitai endpoint is missing")

if errors:
    print("PROJECT VERIFICATION FAILED")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Project verification passed")
