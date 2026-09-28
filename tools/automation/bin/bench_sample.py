import socket, json, time, sys, statistics, hashlib
from pathlib import Path

TOKEN = sys.argv[1] if len(sys.argv) > 1 else 'tk-bench-phaseB'
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 53301
OUT = Path(sys.argv[3]) if len(sys.argv) > 3 else Path('bench.json')
WARMUP = 8.0
SAMPLE = 15.0

def audit(line):
    s = socket.create_connection(('127.0.0.1', PORT), timeout=15)
    s.sendall((line + '\n').encode())
    data = b''
    while not data.endswith(b'\n'):
        chunk = s.recv(1 << 20)
        if not chunk:
            break
        data += chunk
    s.close()
    return data.decode().strip()

def snap():
    return json.loads(audit('snapshot ' + TOKEN))

print('ping', audit('ping ' + TOKEN))
print('reset', audit('resetperf ' + TOKEN))
time.sleep(WARMUP)
audit('resetperf ' + TOKEN)
t0 = time.time()
while time.time() - t0 < SAMPLE:
    time.sleep(1.0)
wall = time.time() - t0
fr = json.loads(audit('frames ' + TOKEN))
frames = fr.get('data', [])
sn = snap()
if frames:
    avg = statistics.mean(frames) / 1e6
    med = statistics.median(frames) / 1e6
    ps = sorted(frames)
    p95 = ps[int(0.95 * (len(ps) - 1))] / 1e6
    p99 = ps[int(0.99 * (len(ps) - 1))] / 1e6
    fps = (len(frames) / wall) if wall > 0 else 0
    # also report wall-derived frame time
    wall_ms = (wall * 1000.0 / len(frames)) if frames else 0
else:
    avg = med = p95 = p99 = fps = wall_ms = 0

result = {
    'label': 'C2-Foundation',
    'frame_count': len(frames),
    'avg_ms': round(avg, 3),
    'median_ms': round(med, 3),
    'p95_ms': round(p95, 3),
    'p99_ms': round(p99, 3),
    'fps_avg': round(fps, 2),
    'wall_ms_per_frame': round(wall_ms, 3),
    'wall_seconds': round(wall, 3),
    'raw_frames_ns_head': frames[:20],
    'snapshot': sn.get('data'),
}
OUT.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({k: result[k] for k in ['label','frame_count','avg_ms','median_ms','p95_ms','p99_ms','fps_avg']}, indent=2))
d = result['snapshot']
print('CPU ms:', {
    'indexedFence': round(d.get('cpuIndexedFenceNanos', 0) / 1e6, 3),
    'indexedReadback': round(d.get('cpuIndexedReadbackNanos', 0) / 1e6, 3),
    'assemble': round(d.get('cpuAssembleNanos', 0) / 1e6, 3),
    'pipeline': round(d.get('cpuPipelineNanos', 0) / 1e6, 3),
    'tlasCpu': round(d.get('cpuTlasNanos', 0) / 1e6, 3),
    'indexedDraws': d.get('indexedDraws'),
    'indexedBytes': d.get('indexedBytes'),
    'assembledVertices': d.get('assembledVertices'),
})
print('GPU ticks:', {
    'ray': d.get('gpuRayTicks'),
    'tlas': d.get('gpuTlasTicks'),
    'dynBlas': d.get('gpuDynBlasTicks'),
    'terrainBlas': d.get('gpuTerrainBlasTicks'),
})
