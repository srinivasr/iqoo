import sys, json, os, logging
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref_extract as R
from androguard.core.apk import APK
out = []
for p in sys.argv[1:]:
    f, tot, dr, mf = R.extract_features(p)
    a = APK(p)
    out.append({"apk": os.path.basename(p), "path": os.path.abspath(p),
        "pkg": a.get_package(), "permissions": sorted(a.get_permissions()),
        "services": a.get_services(), "activities": a.get_activities(), "receivers": a.get_receivers(),
        "hasLauncher": bool(a.get_main_activity()), "fired": sorted(k for k, v in f.items() if v == 1)})
    print(os.path.basename(p), "fired", len(out[-1]["fired"]), file=sys.stderr)
json.dump(out, open("ref.json", "w"), indent=1)
