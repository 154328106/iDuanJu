#!/usr/bin/env python3
"""Extract two unchanged ranking sources for PRIVATE local tests; never downloads or executes code.
The collection contains source-author redistribution restrictions; output is intentionally gitignored.
"""
import argparse,json,pathlib
p=argparse.ArgumentParser(description=__doc__);p.add_argument("collection",type=pathlib.Path);a=p.parse_args()
items=json.loads(a.collection.read_text());wanted=["七猫小说","米读小说"]
selected=[next(x for x in items if x.get("bookSourceName")==name) for name in wanted]
out=pathlib.Path(__file__).resolve().parents[2]/"android/app/src/androidTest/assets/source-compat/filter-sources.json"
out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(selected,ensure_ascii=False,indent=2))
print(str(out));print("Private test fixture only. Do not publish without reviewing source author licenses.")
