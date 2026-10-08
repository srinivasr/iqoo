# Bundled assets

- `models/network_mlp_qdq.onnx`, `models/network_mlp_float.onnx` - 13-class network model from
  `dhrashta-x-v3` (82 raw window features, batch fixed to 1 for accelerator delegates).
- `models/network_model.json` - feature order, class order, scaler, file hashes.
- `models/network_selftest.json` - golden windows + expected outputs (ONNX Runtime CPU) for the
  on-device self-test in Settings.
- `threats.json` - local indicator list. Release ships it EMPTY (no vetted feed). Debug builds use
  `src/debug/assets/threats.json` (labelled demo values).

Optional, not bundled: `models/accessibility_mlp.onnx` + `models/accessibility_mlp.json` (must list a
`BENIGN` class in `class_order`). Without it the accessibility path runs on rules only and Settings
says so.
