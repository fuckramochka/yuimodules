#!/usr/bin/env python3
"""
Ноутбук-сервер бейджів AmeGram / Yumigram:
- Локальний первинний бекенд для бейджів з підтримкою ETag (304 Not Modified).
- Автоматична синхронізація з GitHub репозиторієм (Git push origin main).
- REST API та вбудований Web-дашборд для зручного керування бейджами з браузера.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BADGES_JSON_PATH = os.path.join(BASE_DIR, "badges", "badges.json")
SVG_DIR_PATH = os.path.join(BASE_DIR, "badges", "svg")


def load_badges_data():
    if not os.path.exists(BADGES_JSON_PATH):
        return {"version": 1, "updated": int(time.time()), "badges": []}
    with open(BADGES_JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_badges_data(data):
    data["updated"] = int(time.time())
    os.makedirs(os.path.dirname(BADGES_JSON_PATH), exist_ok=True)
    with open(BADGES_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def sync_git_to_github():
    """Синхронізувати локальні зміни badges.json та SVG з GitHub."""
    try:
        subprocess.run(["git", "add", "badges/"], cwd=BASE_DIR, check=True)
        status = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=BASE_DIR,
            capture_output=True,
            text=True,
            check=True
        )
        if "badges" in status.stdout:
            subprocess.run(
                ["git", "commit", "-m", "sync(badges): update badges from laptop server"],
                cwd=BASE_DIR,
                check=True
            )
            push = subprocess.run(
                ["git", "push", "origin", "main"],
                cwd=BASE_DIR,
                capture_output=True,
                text=True,
                check=True
            )
            return True, "Успішно запушено на GitHub: " + push.stdout.strip()
        else:
            return True, "Файли вже синхронізовані з останнім комітом"
    except Exception as e:
        return False, f"Помилка синхронізації Git: {e}"


def get_html_dashboard():
    data = load_badges_data()
    badges = data.get("badges", [])
    badges_rows = ""
    for b in badges:
        uid = b.get("user_id")
        btype = b.get("type", "original")
        title = b.get("title", "")
        custom_svg = b.get("custom_svg") or "—"
        verified = "✓ Так" if b.get("verified") else "Ні"
        badges_rows += f"""
        <tr>
            <td><code>{uid}</code></td>
            <td><span class="badge-type">{btype}</span></td>
            <td><b>{title}</b></td>
            <td><code>{custom_svg}</code></td>
            <td>{verified}</td>
            <td>
                <button class="btn-del" onclick="deleteBadge({uid})">Видалити</button>
            </td>
        </tr>
        """

    return f"""<!DOCTYPE html>
<html lang="uk">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Ноутбук-сервер бейджів ໒꒱ Miyumi</title>
    <style>
        :root {{
            --bg: #0F1117;
            --surface: #1A1D26;
            --surface-card: #222634;
            --primary: #A855F7;
            --primary-glow: rgba(168, 85, 247, 0.35);
            --accent: #00F0FF;
            --pink: #FF2A85;
            --text: #F1F5F9;
            --text-sub: #94A3B8;
            --border: #334155;
            --success: #10B981;
            --danger: #EF4444;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }}
        body {{ background: var(--bg); color: var(--text); padding: 24px; min-height: 100vh; }}
        .container {{ max-width: 900px; margin: 0 auto; }}
        header {{ display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border); }}
        h1 {{ font-size: 24px; font-weight: 700; color: var(--text); }}
        .status-pill {{ background: rgba(16, 185, 129, 0.15); color: var(--success); border: 1px solid var(--success); padding: 4px 12px; border-radius: 999px; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }}
        .status-dot {{ width: 8px; height: 8px; background: var(--success); border-radius: 50%; box-shadow: 0 0 8px var(--success); }}
        .card {{ background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 24px; box-shadow: 0 8px 24px rgba(0,0,0,0.3); }}
        .card h2 {{ font-size: 18px; margin-bottom: 14px; color: var(--accent); }}
        .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; margin-bottom: 16px; }}
        label {{ display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.5px; }}
        input, select, textarea {{ width: 100%; background: var(--surface-card); border: 1px solid var(--border); color: #fff; padding: 10px 12px; border-radius: 8px; font-size: 14px; outline: none; transition: 0.2s; }}
        input:focus, select:focus, textarea:focus {{ border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-glow); }}
        .btn {{ background: linear-gradient(135deg, var(--primary), var(--pink)); color: #fff; border: none; padding: 10px 20px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: 0.2s; }}
        .btn:hover {{ opacity: 0.9; transform: translateY(-1px); }}
        .btn-sync {{ background: linear-gradient(135deg, #0284C7, #06B6D4); margin-left: 10px; }}
        .btn-del {{ background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); padding: 4px 8px; border-radius: 6px; cursor: pointer; font-size: 12px; }}
        .btn-del:hover {{ background: var(--danger); color: #fff; }}
        table {{ width: 100%; border-collapse: collapse; margin-top: 10px; }}
        th, td {{ padding: 12px; text-align: left; border-bottom: 1px solid var(--border); font-size: 14px; }}
        th {{ color: var(--text-sub); font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; }}
        .badge-type {{ background: rgba(168, 85, 247, 0.2); color: #C084FC; padding: 2px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; border: 1px solid rgba(168, 85, 247, 0.4); }}
        .sync-banner {{ background: rgba(2, 132, 199, 0.15); border: 1px solid #0284C7; padding: 12px 16px; border-radius: 10px; font-size: 13px; margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center; }}
        #logToast {{ margin-top: 10px; font-size: 13px; color: var(--accent); }}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div>
                <h1>Ноутбук-сервер бейджів ໒꒱ Miyumi</h1>
                <p style="color: var(--text-sub); font-size: 13px; margin-top: 4px;">Первинний локальний бекенд екосистеми Miyumi з авто-фолбеком на GitHub</p>
            </div>
            <div class="status-pill">
                <div class="status-dot"></div> Ноутбук онлайн
            </div>
        </header>

        <div class="sync-banner">
            <div>
                <b>Режим подвійного джерела:</b> клієнт тягне бейджі з цього ноутбука. Якщо ноутбук вимкнено — клієнт миттєво підхоплює GitHub-дзеркало.
            </div>
            <button class="btn btn-sync" onclick="triggerSync()">Синхронізувати з GitHub</button>
        </div>

        <div class="card">
            <h2>Додати або змінити бейдж</h2>
            <form id="grantForm" onsubmit="submitGrant(event)">
                <div class="grid">
                    <div>
                        <label>User ID (Telegram):</label>
                        <input type="number" id="userId" required placeholder="8011880648">
                    </div>
                    <div>
                        <label>Стиль бейджа:</label>
                        <select id="badgeType">
                            <option value="original">01 — ORIGINAL (Класичне сердечко)</option>
                            <option value="angel">02 — ANGEL (Небесний ангел)</option>
                            <option value="dark">03 — DARK (Нічний обсидіан)</option>
                            <option value="glitch">04 — GLITCH (Кібер-глітч)</option>
                            <option value="pink">05 — PINK (Неоново-рожевий)</option>
                            <option value="cyan">06 — CYAN (Кібер-блакитний)</option>
                            <option value="devil">07 — DEVIL (Зухвалий чортик)</option>
                            <option value="rainbow">08 — RAINBOW (Призматичний спектр)</option>
                            <option value="outline">09 — OUTLINE (Кібер-вайрфрейм)</option>
                            <option value="premium">10 — PREMIUM (Золота корона)</option>
                        </select>
                    </div>
                    <div>
                        <label>Кастомний SVG файл (опціонально):</label>
                        <input type="text" id="customSvg" placeholder="8011880648.svg">
                    </div>
                </div>
                <div class="grid">
                    <div>
                        <label>Назва статусу (Заголовок):</label>
                        <input type="text" id="title" required placeholder="VIP Member ໒꒱">
                    </div>
                    <div>
                        <label>Опис / Lore:</label>
                        <input type="text" id="lore" placeholder="Особиста відзнака спільноти">
                    </div>
                </div>
                <div style="display: flex; gap: 10px; align-items: center;">
                    <label style="margin: 0; cursor: pointer; display: flex; align-items: center; gap: 6px;">
                        <input type="checkbox" id="verified" checked style="width: auto;"> Верифікований статус
                    </label>
                    <label style="margin: 0; cursor: pointer; display: flex; align-items: center; gap: 6px; margin-left: 16px;">
                        <input type="checkbox" id="autoPush" checked style="width: auto;"> Одразу пушити на GitHub
                    </label>
                </div>
                <div style="margin-top: 16px;">
                    <button type="submit" class="btn">Зберегти бейдж</button>
                    <span id="logToast"></span>
                </div>
            </form>
        </div>

        <div class="card">
            <h2>Список зареєстрованих бейджів ({len(badges)})</h2>
            <table>
                <thead>
                    <tr>
                        <th>User ID</th>
                        <th>Стиль</th>
                        <th>Заголовок</th>
                        <th>SVG</th>
                        <th>Верифікований</th>
                        <th>Дія</th>
                    </tr>
                </thead>
                <tbody>
                    {badges_rows}
                </tbody>
            </table>
        </div>
    </div>

    <script>
        async function submitGrant(e) {{
            e.preventDefault();
            const payload = {{
                user_id: parseInt(document.getElementById('userId').value),
                type: document.getElementById('badgeType').value,
                custom_svg: document.getElementById('customSvg').value.trim() || null,
                title: document.getElementById('title').value.trim(),
                lore: document.getElementById('lore').value.trim() || null,
                verified: document.getElementById('verified').checked,
                auto_push: document.getElementById('autoPush').checked
            }};

            document.getElementById('logToast').innerText = "Збереження...";
            const res = await fetch('/api/grant', {{
                method: 'POST',
                headers: {{ 'Content-Type': 'application/json' }},
                body: JSON.stringify(payload)
            }});
            const data = await res.json();
            if (data.ok) {{
                document.getElementById('logToast').innerText = "✓ Збережено!" + (data.synced ? " Та синхронізовано з GitHub!" : "");
                setTimeout(() => location.reload(), 1200);
            }} else {{
                alert("Помилка: " + (data.error || "Невідома"));
            }}
        }}

        async function deleteBadge(uid) {{
            if (!confirm("Видалити бейдж для користувача " + uid + "?")) return;
            const res = await fetch('/api/revoke', {{
                method: 'POST',
                headers: {{ 'Content-Type': 'application/json' }},
                body: JSON.stringify({{ user_id: uid, auto_push: true }})
            }});
            const data = await res.json();
            if (data.ok) {{
                location.reload();
            }} else {{
                alert("Помилка видалення: " + (data.error || "Невідома"));
            }}
        }}

        async function triggerSync() {{
            const btn = event.target;
            btn.disabled = true;
            btn.innerText = "Синхронізація...";
            try {{
                const res = await fetch('/api/sync', {{ method: 'POST' }});
                const data = await res.json();
                alert(data.message || "Синхронізація виконана");
            }} catch (e) {{
                alert("Помилка зв'язку: " + e);
            }} finally {{
                btn.disabled = false;
                btn.innerText = "Синхронізувати з GitHub";
            }}
        }}
    </script>
</body>
</html>
"""


class BadgeRequestHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        # Короткий чистий лог запитів
        sys.stderr.write(f"[{self.log_date_time_string()}] {self.command} {self.path} - {args[1]}\n")

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, If-None-Match")

    def do_OPTIONS(self):
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/dashboard"):
            html = get_html_dashboard().encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return

        if path in ("/badges.json", "/badges"):
            if not os.path.exists(BADGES_JSON_PATH):
                self.send_error(HTTPStatus.NOT_FOUND, "badges.json not found")
                return
            with open(BADGES_JSON_PATH, "rb") as f:
                content = f.read()

            etag = f'"{hashlib.sha256(content).hexdigest()[:16]}"'
            if_none_match = self.headers.get("If-None-Match")
            if if_none_match and if_none_match.strip() == etag:
                self.send_response(HTTPStatus.NOT_MODIFIED)
                self.send_header("ETag", etag)
                self.send_cors_headers()
                self.end_headers()
                return

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("ETag", etag)
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(content)
            return

        if path.startswith("/svg/"):
            fname = os.path.basename(path[5:])
            fpath = os.path.join(SVG_DIR_PATH, fname)
            if not os.path.isfile(fpath):
                self.send_error(HTTPStatus.NOT_FOUND, "SVG file not found")
                return
            with open(fpath, "rb") as f:
                svg_data = f.read()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/svg+xml")
            self.send_header("Content-Length", str(len(svg_data)))
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(svg_data)
            return

        if path == "/api/status":
            data = load_badges_data()
            resp = json.dumps({
                "status": "online",
                "badges_count": len(data.get("badges", [])),
                "last_updated": data.get("updated", 0),
                "timestamp": int(time.time())
            }).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp)
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"

        try:
            req_data = json.loads(body.decode("utf-8")) if body else {}
        except Exception:
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid JSON")
            return

        if path == "/api/grant":
            uid = req_data.get("user_id")
            if not uid:
                self.send_error(HTTPStatus.BAD_REQUEST, "user_id is required")
                return

            data = load_badges_data()
            badges = data.get("badges", [])

            new_badge = {
                "user_id": int(uid),
                "type": req_data.get("type", "original"),
                "title": req_data.get("title", "Користувач Miogram ໒꒱"),
                "lore": req_data.get("lore") or "Особиста відзнака спільноти",
                "date": req_data.get("date") or time.strftime("%d.%m.%Y"),
                "verified": bool(req_data.get("verified", True))
            }
            if req_data.get("custom_svg"):
                new_badge["custom_svg"] = req_data.get("custom_svg")

            # Замінити існуючий або додати новий
            badges = [b for b in badges if b.get("user_id") != int(uid)]
            badges.append(new_badge)
            data["badges"] = badges
            save_badges_data(data)

            synced = False
            sync_msg = ""
            if req_data.get("auto_push", True):
                synced, sync_msg = sync_git_to_github()

            resp = json.dumps({"ok": True, "synced": synced, "message": sync_msg}).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp)
            return

        if path == "/api/revoke":
            uid = req_data.get("user_id")
            if not uid:
                self.send_error(HTTPStatus.BAD_REQUEST, "user_id is required")
                return

            data = load_badges_data()
            badges = data.get("badges", [])
            data["badges"] = [b for b in badges if b.get("user_id") != int(uid)]
            save_badges_data(data)

            synced = False
            sync_msg = ""
            if req_data.get("auto_push", True):
                synced, sync_msg = sync_git_to_github()

            resp = json.dumps({"ok": True, "synced": synced, "message": sync_msg}).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp)
            return

        if path == "/api/sync":
            success, msg = sync_git_to_github()
            resp = json.dumps({"ok": success, "message": msg}).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp)
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not found")


def run_server(port=8765):
    server_address = ("0.0.0.0", port)
    httpd = HTTPServer(server_address, BadgeRequestHandler)
    print(f"=====================================================")
    print(f"  Ноутбук-сервер бейджів запущено на порті {port}")
    print(f"  Дашборд:      http://localhost:{port}/")
    print(f"  API бейджів:  http://localhost:{port}/badges.json")
    print(f"  Фолбек:       GitHub raw (main гілка)")
    print(f"=====================================================")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nЗупинка сервера...")
        httpd.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AmeGram Badge Laptop Server")
    parser.add_argument("--port", type=int, default=8765, help="Port to listen on (default: 8765)")
    args = parser.parse_args()
    run_server(args.port)
