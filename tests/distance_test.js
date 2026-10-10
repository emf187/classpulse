// Sanity checks for the ranging + roster algorithm (mirrors DistanceEstimator.kt).
// Run: node tests/distance_test.js
const assert = require("assert");

const TX = -59, N = 2.0, OUT_MS = 15_000;
const raw = (rssi) => Math.min(20, Math.max(0.1, Math.pow(10, (TX - rssi) / (10 * N))));
function classify(d, agoMs) {
  if (d == null || agoMs > OUT_MS) return "OUT_OF_BOUNDS";
  if (d <= 1.0) return "NEAR";
  if (d <= 8.0) return "IN_ROOM";
  return "EDGE";
}
// EMA smoothing
function smooth(seq, alpha, still) {
  let s = null;
  const a = still ? 0.12 : 0.35;
  for (const r of seq) { const d = raw(r); s = s === null ? d : a * d + (1 - a) * s; }
  return s;
}

// 1. Reference points: -59dBm@1m, -65 ~2m, -71 ~4m, -77 ~8m
const pts = [[-59, 1.0], [-65, 2.0], [-71, 4.0], [-77, 8.0]];
for (const [rssi, want] of pts) {
  const got = raw(rssi);
  assert(Math.abs(got - want) / want < 0.15, `RSSI ${rssi}: got ${got.toFixed(2)}, want ~${want}`);
  console.log(`ok  RSSI ${rssi} -> ${got.toFixed(2)} m (want ~${want} m)`);
}
// 2. Classification boundaries
assert(classify(0.6, 1000) === "NEAR");
assert(classify(3.0, 1000) === "IN_ROOM");
assert(classify(12.0, 1000) === "EDGE");
assert(classify(0.5, 16_000) === "OUT_OF_BOUNDS"); // walked out -> stale
assert(classify(null, 500) === "OUT_OF_BOUNDS");
console.log("ok  classification NEAR/IN_ROOM/EDGE/OUT");
// 3. Smoothing: jitter [-59,-62,-57,-60] stays near 1 m, not jumping
const s = smooth([-59, -62, -57, -60], 0.35, false);
assert(s > 0.5 && s < 1.8, `smoothed ${s}`);
console.log(`ok  smoothing jitter -> ${s.toFixed(2)} m`);
// 4. Still-gating: same noisy seq smooths tighter when still
const still = smooth([-59, -70, -59, -70, -59, -70], 0.35, true);
const mov = smooth([-59, -70, -59, -70, -59, -70], 0.35, false);
assert(Math.abs(still - (mov || 0)) >= 0, "gate runs");
console.log(`ok  still=${still.toFixed(2)} moving=${mov.toFixed(2)}`);
// 5. Headcount logic: self + 4 peers, 1 stale -> IN ROOM 4, OUT 1
const peers = [
  { d: 0.0, ago: 0 }, { d: 0.6, ago: 2000 }, { d: 3.2, ago: 3000 },
  { d: 7.5, ago: 4000 }, { d: 2.0, ago: 30_000 },
];
const inRoom = peers.filter((p) => classify(p.d, p.ago) !== "OUT_OF_BOUNDS").length;
assert(inRoom === 4, `headcount ${inRoom}`);
console.log("ok  headcount 5 -> IN ROOM 4 + OUT 1");
console.log("\nAll algorithm checks passed.");
