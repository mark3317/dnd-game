"""Prepare the existing, source-checked CC0 pack for portable game playback.

Run with the project's QA venv containing numpy and soundfile. No downloads, synthesis,
audition claims or source replacement. Output hashes record the exact shipped WAVs.
"""
import hashlib
import json
import math
from pathlib import Path
import shutil

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "assets/audio/starter-v1"
OUTPUT = ROOT / "shared/src/commonMain/composeResources/files/effects"
REPORT = ROOT / "assets/audio/game-v1"
IDS = ["impact.wood.light", "impact.wood.heavy", "blade.slice", "impact.metal.light",
       "cloth.rustle", "step.stone.1", "step.stone.2", "ambience.dungeon"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def decibels(value):
    return round(20 * math.log10(max(float(value), 1e-12)), 3)


def main():
    sources = {item["id"]: item for item in json.loads((SOURCE / "manifest.json").read_text())["assets"]}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    REPORT.mkdir(parents=True, exist_ok=True)
    catalog = []
    for identity in IDS:
        source = sources[identity]
        path = SOURCE / source["file"]
        if source["license"] != "CC0-1.0" or sha(path) != source["sha256"]:
            raise ValueError(f"Unverified source: {identity}")
        stereo, rate = sf.read(path, dtype="float64", always_2d=True)
        if not np.isfinite(stereo).all() or len(stereo) == 0:
            raise ValueError(f"Invalid samples: {identity}")
        audio = stereo.mean(axis=1)
        ambience = identity == "ambience.dungeon"
        parameters = {
            "channelMix": "arithmetic mean to mono", "sampleRate": "preserved",
            "targetRmsDbfs": -34 if ambience else -24,
            "peakCeilingDbfs": -20 if ambience else -10,
            "maximumBoostDb": 0 if ambience else 3,
            "trim": "none", "subtype": "PCM_16",
        }
        if ambience:
            # Circular tail/head crossfade; no claim of an auditioned seamless loop.
            frames = min(round(rate * .5), len(audio) // 4)
            weight = (1 - np.cos(np.linspace(0, math.pi, frames))) / 2
            seam = audio[-frames:] * (1 - weight) + audio[:frames] * weight
            audio = np.concatenate((audio[frames:-frames], seam))
            parameters["circularCrossfadeMs"] = round(frames * 1000 / rate)
        else:
            # Retain the whole clip, including its tail. Only soften endpoint discontinuities.
            fade_in, fade_out = min(round(rate * .003), len(audio)), min(round(rate * .010), len(audio))
            audio[:fade_in] *= np.linspace(0, 1, fade_in)
            audio[-fade_out:] *= np.linspace(1, 0, fade_out)
            parameters.update({"fadeInMs": 3, "fadeOutMs": 10})
        rms = np.sqrt(np.mean(audio ** 2))
        peak = np.max(np.abs(audio))
        if rms <= 0 or peak <= 0:
            raise ValueError(f"Silent source: {identity}")
        gain = min(10 ** (parameters["targetRmsDbfs"] / 20) / rms,
                   10 ** (parameters["peakCeilingDbfs"] / 20) / peak,
                   10 ** (parameters["maximumBoostDb"] / 20))
        audio *= gain
        filename = identity.replace(".", "_") + ".wav"
        target = OUTPUT / filename
        sf.write(target, audio, rate, subtype="PCM_16", format="WAV")
        decoded, actual_rate = sf.read(target, dtype="float64")
        catalog.append({
            "id": identity, "file": filename, "duration_ms": round(len(decoded) * 1000 / actual_rate),
            "sampleRate": actual_rate, "channels": 1, "subtype": "PCM_16",
            "sha256": sha(target), "bytes": target.stat().st_size,
            "rmsDbfs": decibels(np.sqrt(np.mean(decoded ** 2))),
            "peakDbfs": decibels(np.max(np.abs(decoded))), "gainDb": decibels(gain),
            "sourceFile": "assets/audio/starter-v1/" + source["file"],
            "sourceSha256": source["sha256"], "author": source["author"],
            "sourceUrl": source["sourceUrl"], "license": source["license"],
            "licenseUrl": source["licenseUrl"], "parameters": parameters,
            "reviewStatus": "decoded-and-measured-not-auditioned",
        })
    text = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    (OUTPUT / "catalog.json").write_text(text, encoding="utf-8")
    (REPORT / "manifest.json").write_text(text, encoding="utf-8")
    licenses = OUTPUT / "licenses"
    licenses.mkdir(exist_ok=True)
    for name in ["kenney-rpg.txt", "kenney-impact.txt", "jaggedstone-source.md"]:
        shutil.copyfile(SOURCE / "licenses" / name, licenses / name)
    print(json.dumps({"assets": len(catalog), "bytes": sum(x["bytes"] for x in catalog),
                      "soundfile": sf.__version__, "numpy": np.__version__}))


if __name__ == "__main__":
    main()
