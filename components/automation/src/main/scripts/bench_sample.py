"""Sample PT submission throughput and opt-in CPU/GPU scopes through the validation probe."""

import argparse
import json
from pathlib import Path
import socket
import statistics
import time


def audit(token, port, command):
    with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
        connection.sendall(f"{command.split()[0]} {token} {' '.join(command.split()[1:])}\n".encode())
        with connection.makefile("r", encoding="utf-8") as response:
            result = json.loads(response.readline())
    if not result.get("ok"):
        raise RuntimeError(result)
    return result


def summarize(start, end, frames, elapsed):
    count = end["frameCount"] - start["frameCount"]
    if count <= 0 or elapsed <= 0:
        raise ValueError("No PT frames measured")
    ordered = sorted(frames)
    cpu = {
        key.removeprefix("cpu").removesuffix("Nanos"): (value - start[key]) / count / 1e6
        for key, value in end.items()
        if key.startswith("cpu") and key.endswith("Nanos")
    } if end["profileMode"] in ("cpu", "all") else None
    gpu = {}
    for stage in ("Ray", "Tlas", "DynBlas", "TerrainBlas", "GlScene", "GlHandoff"):
        samples = end.get(f"gpu{stage}Samples", 0) - start.get(f"gpu{stage}Samples", 0)
        nanos = end.get(f"gpu{stage}Nanos", 0) - start.get(f"gpu{stage}Nanos", 0)
        gpu[stage] = {"samples": samples, "mean_ms": nanos / samples / 1e6 if samples else None}
    return {
        "profile_mode": end["profileMode"],
        "pt_frame_count": count,
        "pt_callbacks_per_second": count / elapsed,
        "wall_ms_per_pt_frame": elapsed * 1000 / count,
        "wall_seconds": elapsed,
        "frame_interval_sample_count": len(frames),
        "frame_interval_mean_ms": statistics.mean(frames) / 1e6 if frames else None,
        "frame_interval_p95_ms": ordered[int(.95 * (len(ordered) - 1))] / 1e6 if frames else None,
        "frame_interval_p99_ms": ordered[int(.99 * (len(ordered) - 1))] / 1e6 if frames else None,
        "cpu_ms_per_pt_frame": cpu,
        "gpu_scopes": gpu,
        "snapshot_start": start,
        "snapshot_end": end,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("token")
    parser.add_argument("port", type=int)
    parser.add_argument("out", type=Path)
    parser.add_argument("--mode", choices=("off", "cpu", "gpu", "all"), default="all")
    parser.add_argument("--warmup", type=float, default=8)
    parser.add_argument("--seconds", type=float, default=15)
    args = parser.parse_args()
    if args.warmup < 0 or args.seconds <= 0:
        parser.error("Warmup must be nonnegative and sample duration positive")
    audit(args.token, args.port, f"profile {args.mode}")
    time.sleep(args.warmup)
    audit(args.token, args.port, "resetperf")
    before = time.monotonic()
    start = audit(args.token, args.port, "snapshot")["data"]
    after = time.monotonic()
    client_start = audit(args.token, args.port, "clientstate")["data"]
    time.sleep(args.seconds)
    before_end = time.monotonic()
    end = audit(args.token, args.port, "snapshot")["data"]
    after_end = time.monotonic()
    client_end = audit(args.token, args.port, "clientstate")["data"]
    elapsed = (before_end + after_end - before - after) / 2
    frames = audit(args.token, args.port, "frames")["data"]
    result = summarize(start, end, frames, elapsed)
    # Ring reads are bounded and slightly later than the counter snapshot; never use them as FPS.
    result["snapshot_roundtrip_seconds"] = [after - before, after_end - before_end]
    result["client_state"] = [client_start, client_end]
    result["throughput_comparison_valid"] = all(not state["paused"] and state["frameLimit"] > 30 for state in result["client_state"])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("profile_mode", "pt_frame_count", "pt_callbacks_per_second", "cpu_ms_per_pt_frame", "gpu_scopes")}, indent=2))


if __name__ == "__main__":
    main()
