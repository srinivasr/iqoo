"""Creates ref_extract.py from extract_v6.py and facts.tsv from ref.json. Usage:
   python patch_ref.py extract_v6.py feature_order_v3.json      (before run_ref.py)
   python patch_ref.py --tsv                                     (after run_ref.py)"""
import json, sys
if sys.argv[1] == "--tsv":
    with open("facts.tsv", "w") as o:
        for r in json.load(open("ref.json")):
            o.write("\t".join([r["path"], r["pkg"], str(r["hasLauncher"]).lower(), "|".join(r["permissions"]),
                               "|".join(r["services"]), "|".join(r["activities"]), "|".join(r["receivers"]),
                               "|".join(r["fired"])]) + "\n")
    sys.exit()
src, order = sys.argv[1], sys.argv[2]
s = open(src).read()
s = s.replace("DREBIN_CSV = '/home/axotron/dhrashta-ml/drebin/drebin-215.csv'\nheader = pd.read_csv(DREBIN_CSV, nrows=0)\n"
              "DREBIN_FEATURES = [c for c in header.columns if c != 'class']",
              f"import json as _j\nDREBIN_FEATURES = _j.load(open({order!r}))['drebin']")
s = s.replace("all_strings = set()", "all_strings = {}").replace("all_strings.add(v.lower())", "all_strings.setdefault(v.lower(), None)")
s = s.replace("list(all_classes)[:5000]", "list(all_classes)")
open("ref_extract.py", "w").write(s)
open("drebin.txt", "w").write("\n".join(json.load(open(order))["drebin"]))
