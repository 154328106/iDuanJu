#!/usr/bin/env python3
"""Read-only GET evidence. No cookies, challenge execution, TLS bypass, or proxy changes."""
import concurrent.futures, datetime, json, pathlib, re, subprocess, tempfile
URLS = [
 "https://m.bookmaopu.com/", "https://m.bookmaopu.com/1.html?page=1",
 "http://www.jiuxs.la/", "http://www.jiuxs.la/shu/1-default-0-0-0-0-2-0-1.html",
 "https://www.quanwenyuedu.io/", "https://www.quanwenyuedu.io/c/1-1.html",
 "https://app.shubl.com/", "https://www.baidu.com/category_type=1&order=week_click&page=1",
 "https://www.zggdwx.com/", "https://www.librivox.org/", "https://www.bilibili.com/",
]
SAFE = {"server", "content-type", "location", "cf-ray", "cf-mitigated", "retry-after", "via"}
def probe(url):
 with tempfile.TemporaryDirectory() as d:
  h,b=pathlib.Path(d)/"headers",pathlib.Path(d)/"body"
  p=subprocess.run(["curl","--silent","--show-error","--location","--max-redirs","5","--connect-timeout","8","--max-time","22","--max-filesize","2097152","--compressed","--dump-header",str(h),"--output",str(b),"--write-out","%{json}",url],capture_output=True,text=True)
  try: meta=json.loads(p.stdout)
  except ValueError: meta={}
  headers=[]
  for line in h.read_text(errors="replace").splitlines() if h.exists() else []:
   if line.startswith("HTTP/") or line.partition(":")[0].lower() in SAFE:headers.append(line)
  raw=b.read_bytes() if b.exists() else b""; text=raw.decode("utf-8",errors="replace");low=text.lower()
  markers=[m for m in ["cf-chl-", "/cdn-cgi/challenge-platform/", "challenge-form", "cf-turnstile", "just a moment", "attention required", "sorry, you have been blocked", "why have i been blocked", "cf-chl-widget", "cloudflare ray id", "access denied", "service unavailable", "bad gateway", "err_name_not_resolved", "robots_denied"] if m in low]
  title=re.search(r"<title[^>]*>(.*?)</title>",text,re.I|re.S)
  return dict(url=url,status=meta.get("http_code",0),final_url=meta.get("url_effective"),content_type=meta.get("content_type"),curl_exit=p.returncode,error=p.stderr.strip(),headers=headers,title=re.sub(r"\s+"," ",title.group(1)).strip() if title else None,markers=markers,bytes=len(raw),snippet=re.sub(r"\s+"," ",re.sub(r"<[^>]*>"," ",text))[:400])
if __name__ == "__main__":
 import sys
 result={"time_shanghai":datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),"method":"GET; no Cookie/Authorization; ordinary curl UA; TLS verification on", "results":list(concurrent.futures.ThreadPoolExecutor(max_workers=4).map(probe,URLS))}
 out=pathlib.Path(sys.argv[1] if len(sys.argv)>1 else "diagnostics.json");out.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False,indent=2))
