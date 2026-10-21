"""Caching and compression of the game's files (review 4.2, 4.3): what may be stored, for how long, and what is gzipped."""
import gzip, re, urllib.error, urllib.request

from conftest import GAME, head


def test_caching_and_compression(check):
    _, h, page = head("/")
    check("4.2 the game page is never stored (launch links carry a token)", h.get("Cache-Control") == "no-store", h.get("Cache-Control"))
    script = re.search(rb'/assets/index-[A-Za-z0-9_-]{8}\.js', page)
    _, h, body = head(script.group(0).decode(), gzip=True) if script else (0, {}, b"")
    check("4.2 fingerprinted scripts are cached for a year, immutable", "immutable" in (h.get("Cache-Control") or "")
          and "max-age=31536000" in h.get("Cache-Control", ""), h.get("Cache-Control"))
    check("4.3 scripts are sent gzipped", h.get("Content-Encoding") == "gzip", h.get("Content-Encoding"))
    check("4.2 cached files keep the security headers", h.get("X-Frame-Options") == "DENY" and "default-src 'self'" in (h.get("Content-Security-Policy") or ""))
    version = re.search(rb'v=([0-9a-f]{12})', body if h.get("Content-Encoding") != "gzip" else gzip.decompress(body))
    _, h, _ = head(f"/themes/bombs-away/logo@2x.png?v={version.group(1).decode() if version else 'x'}")
    check("4.2 versioned theme images are cached for a year", "max-age=31536000" in (h.get("Cache-Control") or ""), h.get("Cache-Control"))
    _, h, _ = head("/themes/bombs-away/logo@2x.png")
    check("4.2 unversioned theme images are re-checked", h.get("Cache-Control") == "no-cache", h.get("Cache-Control"))
    # R5: a missing file is never stored, versioned or not, theme file or hashed script (nginx's year applies to 200/206/304 only)
    for path in ("/themes/rat-race/does-not-exist.avif?v=abc123", "/assets/sounds/missing.ogg?v=abc123", "/assets/index-ZZZZZZZZ.js"):
        status, h, _ = head(path)
        check(f"R5 a 404 is not cacheable: {path}", status == 404 and h.get("Cache-Control") == "no-store", f"{status} {h.get('Cache-Control')}")
    _, h, _ = head("/themes/bombs-away/theme.json?v=1")
    check("4.2 theme.json is always re-checked", h.get("Cache-Control") == "no-cache", h.get("Cache-Control"))
    r = urllib.request.Request(GAME + "/api/game/session", data=b'{"token":"x"}', method="POST",
                               headers={"Content-Type": "application/json", "Accept-Encoding": "gzip"})
    try:
        enc = urllib.request.urlopen(r, timeout=10).headers.get("Content-Encoding")
    except urllib.error.HTTPError as e:
        enc = e.headers.get("Content-Encoding")
    check("4.3 API answers are never compressed (BREACH)", enc is None, enc)
