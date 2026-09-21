# 147 — Two transport settings short of the requirement

**Status:** **Fixed and deployed 2026-09-21.** Both came out of the §12
self-assessment pass, where they were the two rows marked ⚠️ for configuration
rather than code. Aquaticism: *"the fix the T-02 and T-03."*

## 1. T-02 — the server-wide TLS default still named 1.0 and 1.1

The reverse proxy's global block carried

```
ssl_protocols TLSv1 TLSv1.1 TLSv1.2 TLSv1.3;   # the distribution default
```

The site itself was never exposed by this: it includes Certbot's
`options-ssl-nginx.conf`, which sets `TLSv1.2 TLSv1.3`, and `ssl_protocols` is
resolved from the default server of the listening socket — which is this site.
So the finding was never "the deployment accepts TLS 1.0"; it was that the value
a **future** site inherits, if nobody remembers to include the Certbot file,
is the weak one. That is the kind of thing that is true for a year and then
suddenly matters on the day someone adds a second vhost.

Converged to `TLSv1.2 TLSv1.3` globally.

## 2. T-03 — HSTS at thirty days

`max-age=2592000`. HSTS's whole value is the window in which a browser refuses
to be downgraded, so a month is a month of protection and then nothing for
anyone who has not visited since. Raised to `max-age=31536000` — one year, the
usual figure.

Deliberately **not** added: `includeSubDomains` and `preload`. Neither belongs
to a school deployment that does not yet have its production domain (§12.1,
P-06) — `preload` in particular is a list you ask to be added to and cannot
quickly leave, and committing a domain to it before the school owns the
deployment would be someone else's decision to make.

## 3. Verification

The client has to be made to *offer* the old versions, or this proves nothing —
a modern OpenSSL refuses to send a TLS 1.0 ClientHello on its own, and the
resulting failure looks identical to the server rejecting one. With
`-cipher 'ALL:@SECLEVEL=0'` forcing the offer:

```
tls1     handshake failed — alert 70 (protocol version), Cipher: 0000
tls1_1   handshake failed — alert 70 (protocol version), Cipher: 0000
tls1_2   completed — Cipher ECDHE-ECDSA-AES256-GCM-SHA384, chain verified
tls1_3   completed
```

`Cipher: 0000` is the tell. The summary line still prints `Protocol: TLSv1` for
a refused attempt, which reads like success at a glance — the first pass of this
check misread exactly that.

And the header as actually served:

```
Strict-Transport-Security: max-age=31536000
```

Note this does not measure a before/after difference for TLS: the endpoint
refused 1.0 and 1.1 before the change too, because of the site-level Certbot
include. The change is to what a new vhost would inherit, and the evidence for
it is the configuration.

## 4. Where the requirement now lives

Both settings were only ever on the server, so the numbers are now written into
the design's transport table (§9) as values that can be checked, rather than
"HTTPS enforced" and "HSTS enabled", which cannot. `Docs/operations/Configuration.md`
carries the same two lines for whoever rebuilds the box.

## 5. What it must not do

- **Must not leave a weak protocol reachable by inheritance.** §1.
- **Must not claim a refusal the client caused.** §3.
- **Must not commit the domain to HSTS preload** before the school owns it. §2.
