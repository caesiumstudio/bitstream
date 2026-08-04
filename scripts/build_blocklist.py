#!/usr/bin/env python3
"""
Builds a binary Bloom filter from blocklist.txt and writes it to assets/blocklist.bin.
Also writes a small header file so AdBlocker.kt knows the filter parameters.

Usage: python3 scripts/build_blocklist.py
"""

import math, struct, hashlib, os, urllib.request, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets")
TXT = os.path.join(ASSETS, "blocklist.txt")
BIN = os.path.join(ASSETS, "blocklist.bin")

# Bloom filter parameters
CAPACITY = 250_000      # max domains
FALSE_POS_RATE = 0.01   # 1% false positive rate

def bloom_params(n, p):
    """Return (bit_size, num_hashes) for n items and false-positive rate p."""
    m = -int(n * math.log(p) / (math.log(2) ** 2))
    k = max(1, round((m / n) * math.log(2)))
    return m, k

def get_hashes(domain: str, m: int, k: int):
    """Return k bit positions for domain using double-hashing (FNV + MD5)."""
    b = domain.encode('utf-8')
    h1 = int(hashlib.md5(b).hexdigest(), 16)
    h2 = int(hashlib.sha1(b).hexdigest(), 16)
    return [(h1 + i * h2) % m for i in range(k)]

def extract_domain(line):
    line = line.strip().lower()
    if not line or line.startswith('#') or line.startswith('!'):
        return None
    if line.startswith('0.0.0.0 ') or line.startswith('127.0.0.1 '):
        parts = line.split()
        if len(parts) >= 2:
            d = parts[1].strip()
            if d and d != 'localhost' and '.' in d and not d.startswith('#'):
                return d
    if re.match(r'^[a-z0-9][a-z0-9\-\.]+\.[a-z]{2,}$', line) and ' ' not in line:
        return line
    m = re.match(r'^\|\|([a-z0-9][a-z0-9\-\.]+\.[a-z]{2,})\^', line)
    if m:
        return m.group(1)
    return None

# ── Step 1: load domains ──────────────────────────────────────────────────────
print("Loading domains from blocklist.txt...")
domains = set()
with open(TXT) as f:
    for line in f:
        d = extract_domain(line)
        if d:
            domains.add(d)
print(f"  {len(domains)} unique domains loaded")

# ── Step 2: compute filter size ───────────────────────────────────────────────
m, k = bloom_params(CAPACITY, FALSE_POS_RATE)
print(f"  Bloom filter: {m} bits ({m//8//1024} KB), {k} hash functions")

# ── Step 3: build bit array ───────────────────────────────────────────────────
bits = bytearray(math.ceil(m / 8))
for domain in domains:
    for pos in get_hashes(domain, m, k):
        bits[pos >> 3] |= (1 << (pos & 7))

# ── Step 4: write binary file ─────────────────────────────────────────────────
# Format: [4 bytes: m (bit size)] [4 bytes: k (num hashes)] [m/8 bytes: bit array]
with open(BIN, 'wb') as f:
    f.write(struct.pack('>II', m, k))
    f.write(bits)

size_kb = os.path.getsize(BIN) // 1024
print(f"  Written {BIN} ({size_kb} KB)")
print("Done.")
