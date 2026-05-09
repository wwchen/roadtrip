#!/usr/bin/env python3
"""Extract the Cookie header value from a Chrome/Safari "Copy as cURL" blob.

Reads the blob from stdin, writes the cookie string to stdout.
Exits non-zero with a diagnostic on stderr if no cookie block is found.
"""
import re
import sys

RAW = sys.stdin.read()

# Chrome uses `-b '…'`; Safari uses `-H 'Cookie: …'`. Support both quote styles.
PATTERNS = [
    r"-b\s+'((?:[^'\\]|\\.)*)'",
    r'-b\s+"((?:[^"\\]|\\.)*)"',
    r"--cookie\s+'((?:[^'\\]|\\.)*)'",
    r'--cookie\s+"((?:[^"\\]|\\.)*)"',
    r"-H\s+'[Cc]ookie:\s*((?:[^'\\]|\\.)*)'",
    r'-H\s+"[Cc]ookie:\s*((?:[^"\\]|\\.)*)"',
]
for p in PATTERNS:
    m = re.search(p, RAW)
    if m:
        print(m.group(1))
        sys.exit(0)

print("error: couldn't find -b '…' or -H 'Cookie: …' in the input", file=sys.stderr)
sys.exit(1)
