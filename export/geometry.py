"""Streaming geometry shared by every exporter and by the Android engine.

Everything on the Hexagon NPU must have a static shape, so the whole realtime
pipeline is specialised to ONE block configuration decided here.  All sizes are
counted in 10 ms "frames" (the RVC feature rate), exactly like the original
realtime_gui.py / rtrvc.py:

    window  W = extra + crossfade + sola_search + block      (frames)
    HuBERT  gets W*160 samples @16 kHz and returns (W//2 - 1 + ...) frames
    synth   encodes W frames, flows W-flow_head, decodes `return_length`

Defaults: 100 ms block, 500 ms left context, 50 ms crossfade  ->  W = 66.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field


@dataclass
class Geometry:
    # ---- user knobs (frames of 10 ms) -------------------------------------
    block: int = 10        # new audio per step  -> 100 ms
    extra: int = 50        # left context        -> 500 ms
    crossfade: int = 5     # crossfade           -> 50 ms (sola buffer = min(.,4))
    sola_search: int = 1   # SOLA search window  -> 10 ms
    # ---- model facts (filled from the checkpoint) -------------------------
    tgt_sr: int = 40000
    version: str = "v2"
    feat_dim: int = 768
    inter_channels: int = 192
    upp: int = 400         # product(upsample_rates) == tgt_sr // 100
    # ---- derived ----------------------------------------------------------
    window: int = field(init=False)
    sola_buffer: int = field(init=False)
    skip_head: int = field(init=False)
    return_length: int = field(init=False)
    flow_head: int = field(init=False)
    dec_head: int = field(init=False)
    hubert_samples: int = field(init=False)
    hubert_frames: int = field(init=False)
    fcpe_samples: int = field(init=False)
    fcpe_frames: int = field(init=False)
    out_samples: int = field(init=False)

    def __post_init__(self) -> None:
        self.window = self.extra + self.crossfade + self.sola_search + self.block
        self.sola_buffer = min(self.crossfade, 4)
        self.skip_head = self.extra
        self.return_length = self.block + self.sola_buffer + self.sola_search
        # synth.infer(): flow only needs 24 frames of left context beyond the
        # head, the decoder then drops those extra 24 again.
        self.flow_head = max(self.skip_head - 24, 0)
        self.dec_head = self.skip_head - self.flow_head
        # HuBERT: 16 kHz, 160 samples per 10 ms frame
        self.hubert_samples = self.window * 160
        # conv feature extractor: kernel 400, stride 320 (no padding)
        self.hubert_frames = (self.hubert_samples - 400) // 320 + 1
        # RVC appends one copy of the last frame, then upsamples x2 (nearest)
        if (self.hubert_frames + 1) * 2 < self.window:
            raise ValueError(
                f"window of {self.window} frames is too short for HuBERT "
                f"(only {(self.hubert_frames + 1) * 2} frames after upsampling)"
            )
        # FCPE only sees the newest block + 50 ms of lead-in (same as rtrvc.py)
        # RMVPE (U-Net + BiGRU) needs real context: use ~640 ms (64 frames, no padding to 32), not 150 ms
        self.fcpe_samples = max(self.block * 160 + 800, min(self.hubert_samples, 63 * 160))
        self.fcpe_frames = self.fcpe_samples // 160 + 1
        self.out_samples = self.return_length * self.upp
        if self.upp * 100 != self.tgt_sr:
            raise ValueError(f"upp*100 ({self.upp * 100}) != tgt_sr ({self.tgt_sr})")
        if self.skip_head + self.return_length > self.window:
            raise ValueError("skip_head + return_length exceeds the window")

    # ------------------------------------------------------------------ io --
    def to_dict(self) -> dict:
        return asdict(self)

    def dump(self, path: str) -> None:
        with open(path, "w") as f:
            json.dump(self.to_dict(), f, indent=2)

    @classmethod
    def load(cls, path: str) -> "Geometry":
        d = json.load(open(path))
        keys = ("block", "extra", "crossfade", "sola_search", "tgt_sr",
                "version", "feat_dim", "inter_channels", "upp")
        return cls(**{k: d[k] for k in keys})


if __name__ == "__main__":
    g = Geometry()
    print(json.dumps(g.to_dict(), indent=2))
    print("latency floor ~", (g.block + g.sola_buffer + g.sola_search) * 10, "ms + compute")
