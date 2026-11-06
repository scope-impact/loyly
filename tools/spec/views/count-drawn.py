"""Read a page's DOM, as headless Chrome dumped it after Mermaid ran, on stdin. Print
"<blocks> <drawn> <errors> <view-chars> <js-error>": the Mermaid blocks on the page, how many
Mermaid processed, how many came out as its syntax-error box, the size of the rendered view,
and the page's recorded JavaScript error (data-js-error on <html>), or "-"."""
import re, sys
d = sys.stdin.read()
err = re.search(r'<html[^>]*data-js-error="([^"]*)"', d)
d = re.sub(r"<script\b[^>]*>.*?</script>", "", d, flags=re.S)
blocks = len(re.findall(r'<pre class="mermaid"', d))
drawn = len(re.findall(r'<pre class="mermaid"[^>]*data-processed="true"', d))
errors = len(re.findall(r"Syntax error in text", d))
view = re.search(r'<main id="view"[^>]*>(.*?)</main>', d, re.S)
print(blocks, drawn, errors, len(view.group(1).strip()) if view else 0, (err.group(1).replace(" ", "_") if err else "-"))
