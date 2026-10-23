#!/usr/bin/env python3
"""Configure the dedicated Ubuntu nginx IP HTTPS edge and six-hour certificate renewal."""
import argparse
import ipaddress
import os
import pwd
import subprocess
import tempfile
from pathlib import Path

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def install(content, target):
    with tempfile.NamedTemporaryFile(mode="w") as f:
        f.write(content)
        f.flush()
        run("sudo", "-n", "install", "-m", "644", f.name, target)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ip", required=True)
    parser.add_argument("--email", required=True)
    parser.add_argument("--web-port", type=int, default=18443)
    parser.add_argument("--certbot", type=Path, default=Path.home()/".local/tools/certbot/bin/certbot")
    parser.add_argument("--state", type=Path, default=Path.home()/".local/state/campus-study-room/tls")
    args = parser.parse_args()
    ip = str(ipaddress.IPv4Address(args.ip))
    if not 1024 <= args.web_port <= 65535 or not args.certbot.is_file():
        raise SystemExit("Invalid upstream port or missing certbot")
    state = args.state.resolve()
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    user = pwd.getpwuid(os.getuid()).pw_name
    webroot = Path("/var/lib/campus-study-room/acme")
    run("sudo", "-n", "install", "-d", "-o", user, "-m", "755", webroot)
    challenge = f"location ^~ /.well-known/acme-challenge/ {{ root {webroot}; default_type text/plain; }}"
    target = "/etc/nginx/conf.d/campus-study-room.conf"
    http = f"server {{ listen 80; server_name {ip}; {challenge} location / {{ return 503; }} }}\n"
    cert = state/"config/live"/ip/"fullchain.pem"
    key = cert.with_name("privkey.pem")
    if not cert.exists():
        install(http, target)
        run("sudo", "-n", "nginx", "-t")
        run("sudo", "-n", "systemctl", "reload", "nginx")
    common = [args.certbot, "--config-dir", state/"config", "--work-dir", state/"work", "--logs-dir", state/"logs"]
    run(*common, "certonly", "--non-interactive", "--agree-tos", "--email", args.email,
        "--preferred-profile", "shortlived", "--webroot", "--webroot-path", webroot,
        "--ip-address", ip, "--cert-name", ip)
    config = f"""server {{
  listen 80;
  server_name {ip};
  {challenge}
  location / {{ return 308 https://{ip}$request_uri; }}
}}
server {{
  listen 443 ssl http2;
  server_name {ip};
  ssl_certificate {cert};
  ssl_certificate_key {key};
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_session_cache shared:studyroom_tls:1m;
  ssl_session_tickets off;
  server_tokens off;
  client_max_body_size 1m;
  add_header Strict-Transport-Security "max-age=2592000" always;
  location / {{
    proxy_pass http://127.0.0.1:{args.web_port};
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
  }}
}}
"""
    install(config, target)
    run("sudo", "-n", "nginx", "-t")
    run("sudo", "-n", "systemctl", "reload", "nginx")
    service = f"""[Unit]
Description=Renew campus study room short-lived IP certificate
After=network-online.target nginx.service
[Service]
Type=oneshot
User={user}
ExecStart={args.certbot} renew --quiet --config-dir {state}/config --work-dir {state}/work --logs-dir {state}/logs --deploy-hook "/usr/bin/sudo -n /usr/sbin/nginx -s reload"
"""
    timer = """[Unit]
Description=Check campus study room IP certificate renewal every six hours
[Timer]
OnCalendar=*-*-* 00,06,12,18:00:00
RandomizedDelaySec=600
Persistent=true
[Install]
WantedBy=timers.target
"""
    install(service, "/etc/systemd/system/campus-study-room-certificate.service")
    install(timer, "/etc/systemd/system/campus-study-room-certificate.timer")
    run("sudo", "-n", "systemctl", "daemon-reload")
    run("sudo", "-n", "systemctl", "enable", "--now", "campus-study-room-certificate.timer")
    print("HTTPS edge and renewal configured for https://" + ip)

if __name__ == "__main__":
    main()
