# TimeBank maintenance mode — included from inside the `server {}` block in
# /etc/nginx/sites-enabled/<the real site file> (see ops/maintenance/README.md).
#
# Gated on a marker file's existence, not time math inside nginx — two
# systemd timers (ops/maintenance/*.timer) touch/remove it at 18:00/08:00
# Asia/Shanghai. `touch`/`rm` the file by hand any time to override the
# schedule immediately.
#
# __BYPASS_KEY__ below is a placeholder — ops/maintenance/deploy.sh
# substitutes it from $TIMEBANK_MAINTENANCE_BYPASS_KEY at deploy time. This
# file as checked into the repo never contains the real key.

set $maintenance_active 0;
if (-f /var/www/timebank-maintenance.flag) {
    set $maintenance_active 1;
}
# A valid bypass cookie (set by /__ops/enter below) lifts the gate for that browser.
if ($cookie_tb_ops_bypass = "__BYPASS_KEY__") {
    set $maintenance_active 0;
}
# Reserved-prefix requests (the bypass entry point itself, and the internal
# maintenance-page redirect target below) must always be reachable —
# otherwise turning the gate off, or even rendering the gate page, is
# impossible once it's on. Deliberately $uri, not $request_uri: the
# error_page-triggered internal redirect to /__maintenance.html below
# changes $uri but leaves $request_uri frozen at the *original* request
# ("/"), so checking $request_uri here never matches that internal
# redirect — the redirected request re-enters this same gate, gets a
# second 503, and nginx falls back to its own bare default error page
# instead of ours (caught live: curl got nginx's stock 503 HTML, not
# index.html).
set $maintenance_gate $maintenance_active;
if ($uri ~ ^/__) {
    set $maintenance_gate 0;
}
if ($maintenance_gate) {
    return 503;
}

# Visit once with the real key to set a 30-day bypass cookie; the key check
# itself is a plain string compare (fine for an internal ops toggle on a
# test server, not a cryptographic secret comparison).
location = /__ops/enter {
    if ($arg_key != "__BYPASS_KEY__") {
        return 404;
    }
    add_header Set-Cookie "tb_ops_bypass=__BYPASS_KEY__; Path=/; Max-Age=2592000; HttpOnly; Secure; SameSite=Lax";
    return 302 /;
}

error_page 503 /__maintenance.html;
location = /__maintenance.html {
    internal;
    root /var/www/timebank-maintenance;
    rewrite ^ /index.html break;
    add_header Retry-After 3600 always;
}
