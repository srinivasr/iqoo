# S0 parity check (Kotlin extractor vs extract_v6.py)

1. `pip install androguard==4.1.4`
2. Copy `extract_v6.py` to `ref_extract.py` and make two edits (see `patch_ref.py`):
   load `DREBIN_FEATURES` from `feature_order_v3.json`, and keep strings in an insertion-ordered dict
   (the original `list(set)[:N]` order is random per run).
3. `python run_ref.py /path/to/*.apk`  -> `ref.json`; convert to `facts.tsv` (see `patch_ref.py`).
4. Compile `Harness.kt` with `app/src/main/java/com/dhrashta/x/ai/staticml/{ApkArchive,DexReader,StaticFeatureExtractor}.kt`
   using kotlinc and run: `java -cp h.jar HarnessKt drebin.txt facts.tsv`.

Result on 2026-10-09 (termux 0.118, termux-api 0.50.1, Shizuku 13.5.4, QKSMS 3.10.1): PARITY PASS,
identical feature sets (24 to 57 features per APK), 50-280 ms per APK on a laptop JVM.
