#!/bin/bash
# Render one chart from app/src/main/assets/viz in headless Chrome and save a PNG, for checking a renderer by eye.
#   tools/vizshot.sh <type> <height> '<data json>' <out.png> [width]
# Animations are skipped so the picture shows the final state. Nothing is fetched from the network.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; VIZ="$ROOT/app/src/main/assets/viz"; TYPE="$1"; H="$2"; DATA="$3"; OUT="$4"; WIDTH="${5:-500}"
TMP="$(mktemp -d)"; cp "$VIZ"/*.js "$VIZ"/viz.html "$TMP"/
python3 - "$TMP" "$TYPE" "$H" "$DATA" "$WIDTH" <<'PY'
import sys, json
tmp, typ, h, data = sys.argv[1], sys.argv[2], int(sys.argv[3]), json.loads(sys.argv[4])
patch = '''<script>d3.selection.prototype.transition=function(){const t=this;const p=new Proxy(t,{get(o,k){if(k==="duration"||k==="delay"||k==="ease")return()=>p;if(k==="attrTween")return(n,f)=>{o.each(function(d,i){this.setAttribute(n,f.call(this,d,i)(1))});return p};if(k==="styleTween")return(n,f)=>{o.each(function(d,i){this.style[n]=f.call(this,d,i)(1)});return p};const v=o[k];return typeof v==="function"?(...a)=>{const r=v.apply(o,a);return r===o?p:r}:v}});return p};window.onerror=(m,s,l)=>{document.body.insertAdjacentHTML("beforeend","<pre style='color:#f66;font:11px monospace;white-space:pre-wrap'>JS ERROR: "+m+" @"+l+"</pre>")};</script>'''
s = open(tmp + '/viz.html').read().replace('<script src="d3.min.js"></script>', '<script src="d3.min.js"></script>' + patch, 1)
s = s.replace('<body>', '<body style="background:#1E2A38">').replace('</body>', '<script>render(%s)</script></body>' % json.dumps({"type": typ, "height": h, "data": data}))
open(tmp + '/shot.html', 'w').write(s)
# Headless Chrome won't make a window narrower than ~500 px, so the chart is drawn in a frame of the exact width.
w = int(sys.argv[5]) if len(sys.argv) > 5 else 500
open(tmp + '/outer.html', 'w').write('<body style="margin:0;background:#1E2A38"><iframe src="shot.html" style="border:0;width:%dpx;height:%dpx;display:block"></iframe></body>' % (w, h + 24))
PY
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --allow-file-access-from-files --user-data-dir="$TMP/prof" --hide-scrollbars --window-size="$(( WIDTH > 500 ? WIDTH : 500 )),$((H + 30))" --screenshot="$OUT" "file://$TMP/outer.html" >/dev/null 2>&1
rm -rf "$TMP"; echo "wrote $OUT"
