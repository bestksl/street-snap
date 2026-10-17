#!/usr/bin/env python3
"""Check an externally stored SmolVLM 256M ONNX bundle.

This script uses only Python's standard library. It never downloads files and
never changes the Android project. It checks the files required by the local
SmolVlmRuntime, records local SHA-256 values, and writes a manifest beside the
bundle when all required files are present.
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import List, Tuple


REVISION = "7e3e67edbbed1bf9888184d9df282b700a323964"
REQUIRED_ROOT = (
    "config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "preprocessor_config.json",
)
GRAPH_CANDIDATES = {
    "vision": ("onnx/vision_encoder_q4.onnx", "onnx/vision_encoder_quantized.onnx"),
    "embedding": ("onnx/embed_tokens_quantized.onnx", "onnx/embed_tokens_q4.onnx"),
    "decoder": (
        "onnx/decoder_model_merged_q4.onnx",
        "onnx/decoder_model_merged_quantized.onnx",
    ),
}
GRAPH_PROFILES = {
    "compact": GRAPH_CANDIDATES,
    # CPU kernels can be faster with Int8 than Q4 even though the APK is larger.
    # Explicit selection must fail when a graph is absent, never mix profiles.
    "cpu-int8": {
        "vision": ("onnx/vision_encoder_quantized.onnx",),
        "embedding": ("onnx/embed_tokens_quantized.onnx",),
        "decoder": ("onnx/decoder_model_merged_quantized.onnx",),
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def select_graph(root: Path, candidates: Tuple[str, ...]) -> Path:
    for relative in candidates:
        path = root / relative
        if path.is_file() and path.stat().st_size > 0:
            return path
    names = ", ".join(candidates)
    raise FileNotFoundError(f"missing ONNX graph; expected one of: {names}")


def sidecars(root: Path, graph: Path) -> List[Path]:
    relative = graph.relative_to(root).as_posix()
    prefix = graph.name
    matches = []
    for path in sorted(graph.parent.iterdir()):
        if path == graph or not path.is_file():
            continue
        name = path.name
        if name == f"{prefix}.data" or name.startswith(f"{prefix}_data"):
            matches.append(path)
    if not matches:
        # Some exports keep external data next to the graph with the ONNX
        # suffix removed. An absent sidecar is valid for a self-contained graph.
        return []
    return matches


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="external smolvlm-256m directory")
    parser.add_argument(
        "--profile", choices=tuple(GRAPH_PROFILES), default="compact",
        help="compact keeps the original graph preference; cpu-int8 requires all three Int8 graphs",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        help="manifest output path (default: <bundle>/street-snap-manifest.json)",
    )
    args = parser.parse_args()
    root = args.bundle.expanduser().resolve()
    if not root.is_dir():
        print(f"错误：目录不存在：{root}", file=sys.stderr)
        return 2

    missing = [name for name in REQUIRED_ROOT if not (root / name).is_file()]
    if missing:
        print("缺少根目录文件：" + ", ".join(missing), file=sys.stderr)
        return 2

    try:
        config = json.loads((root / "config.json").read_text(encoding="utf-8"))
        processor = json.loads((root / "preprocessor_config.json").read_text(encoding="utf-8"))
        graphs = {name: select_graph(root, candidates) for name, candidates in GRAPH_PROFILES[args.profile].items()}
    except (OSError, ValueError, FileNotFoundError) as error:
        print(f"校验失败：{error}", file=sys.stderr)
        return 2

    model_type = config.get("model_type")
    if model_type not in {"smolvlm", "idefics3"}:
        print(f"校验失败：不支持的 model_type={model_type!r}", file=sys.stderr)
        return 2
    if processor.get("do_normalize", True) is not True or processor.get("do_rescale", True) is not True:
        print("校验失败：preprocessor_config 的 normalize/rescale 不符合运行时要求", file=sys.stderr)
        return 2

    files: List[Path] = [root / name for name in REQUIRED_ROOT]
    for graph in graphs.values():
        files.append(graph)
        files.extend(sidecars(root, graph))
    files = list(dict.fromkeys(files))

    records = []
    total = 0
    for path in files:
        size = path.stat().st_size
        total += size
        records.append({"path": path.relative_to(root).as_posix(), "bytes": size, "sha256": sha256(path)})

    manifest = {
        "model": "smolvlm-256m",
        "repository": "HuggingFaceTB/SmolVLM-256M-Instruct",
        "revision": REVISION,
        "model_type": model_type,
        "profile": args.profile,
        "graphs": {name: path.relative_to(root).as_posix() for name, path in graphs.items()},
        "files": records,
        "total_bytes": total,
        "note": "Local SHA-256 only; compare with the pinned Hugging Face tree metadata before distribution.",
    }
    manifest_path = args.manifest or root / "street-snap-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"OK: {root}")
    print(f"revision: {REVISION}")
    print(f"profile: {args.profile}")
    print(f"files: {len(files)}")
    print(f"size: {total / (1024 * 1024):.1f} MiB")
    print(f"manifest: {manifest_path}")
    for record in records:
        print(f"  {record['sha256']}  {record['path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
