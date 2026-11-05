import json, os, threading, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FILES = {"/video": [("Movies", True, 0), ("clip.mp4", False, 12345)], "/video/Movies": [("a.mkv", False, 999)]}
LOG = open("/tmp/harbor-mock-requests.log", "a")

def log(*a):
    LOG.write(" ".join(map(str, a)) + "\n"); LOG.flush()

class Syno(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, obj, code=200):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def route(self, m):
        u = urllib.parse.urlparse(self.path); q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        body = b""
        if m == "POST":
            n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n) if n else b""
            if self.headers.get("Content-Type", "").startswith("application/x-www-form-urlencoded"):
                q.update({k: v[0] for k, v in urllib.parse.parse_qs(body.decode()).items()})
        log("SYNO", m, u.path, q.get("api"), q.get("method"))
        if u.path.endswith("query.cgi"):
            return self.send({"success": True, "data": {"SYNO.API.Auth": {"path": "auth.cgi"}}})
        if u.path.endswith("auth.cgi"):
            if q.get("method") == "login":
                if q.get("passwd") == "secret": return self.send({"success": True, "data": {"sid": "SID123"}})
                return self.send({"success": False, "error": {"code": 400}})
            return self.send({"success": True})
        if q.get("_sid") != "SID123": return self.send({"success": False, "error": {"code": 106}})
        api, meth = q.get("api"), q.get("method")
        d = {}
        if api == "SYNO.DSM.Info": d = {"model": "DS920+", "version_string": "DSM 7.2.1-69057", "uptime": 86400 * 3, "ram": 4096}
        elif api == "SYNO.Core.Network": d = {"server_name": "diskstation"}
        elif api == "SYNO.Core.System.Utilization": d = {"cpu": {"user_load": 12, "system_load": 5}, "memory": {"total_real": 4000000, "avail_real": 1000000, "cached": 500000}, "network": [{"device": "total", "rx": 2500000, "tx": 400000}]}
        elif api == "SYNO.Storage.CGI.Storage": d = {"volumes": [{"id": "volume_1", "status": "normal", "size": {"total": "8000000000000", "used": "3000000000000"}}], "disks": [{"id": "sda", "name": "Drive 1", "model": "WD80EFAX", "size_total": "8001563222016", "temp": 34}]}
        elif api == "SYNO.Docker.Container" and meth == "list": d = {"containers": [{"name": "plex", "status": "running", "image": "linuxserver/plex"}, {"name": "sonarr", "status": "stopped", "image": "linuxserver/sonarr"}]}
        elif api == "SYNO.Docker.Container": log("DOCKER", meth, q.get("name")); d = {}
        elif api == "SYNO.Core.System": log("POWER", meth); d = {}
        elif api == "SYNO.FileStation.List" and meth == "list_share": d = {"shares": [{"name": "video", "path": "/video", "isdir": True}, {"name": "docs", "path": "/docs", "isdir": True}]}
        elif api == "SYNO.FileStation.List": p = q.get("folder_path"); d = {"files": [{"name": n, "path": p + "/" + n, "isdir": i, "additional": {"size": s}} for n, i, s in FILES.get(p, [])]}
        elif api == "SYNO.FileStation.CreateFolder": log("MKDIR", q.get("folder_path"), q.get("name")); d = {}
        elif api == "SYNO.FileStation.Download":
            self.send_response(200); self.send_header("Content-Length", "5"); self.end_headers(); self.wfile.write(b"hello"); return
        elif api == "SYNO.FileStation.Upload": log("UPLOAD", len(body)); d = {}
        return self.send({"success": True, "data": d})

class Omv(Syno):
    def route(self, m):
        n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n) or b"{}")
        s, me, p = body.get("service"), body.get("method"), body.get("params") or {}
        log("OMV", s, me)
        if (s, me) == ("Session", "isAuthenticated"): return self.send({"response": False, "error": None})
        if (s, me) == ("Session", "login"):
            ok = p.get("password") == "secret"
            return self.send({"response": {"authenticated": ok}, "error": None})
        r = None
        if (s, me) == ("System", "getInformation"): r = {"hostname": "omv-box", "version": "7.4.2-1", "uptime": 7200.5, "cpuUtilization": 23.4, "memTotal": "8000000000", "memUsed": "3000000000"}
        elif (s, me) == ("FileSystemMgmt", "enumerateFilesystems"): r = [{"devicefile": "/dev/sda1", "label": "data", "mounted": True, "size": "2000000000000", "used": "500000000000", "available": "1500000000000"}]
        elif (s, me) == ("DiskMgmt", "enumerateDevices"): r = [{"devicefile": "/dev/sda", "model": "ST2000", "size": "2000398934016"}]
        elif (s, me) == ("Smart", "getList"): r = {"total": 1, "data": [{"devicefile": "/dev/sda", "temperature": 31}]}
        elif s == "System": log("POWER", me); r = None
        return self.send({"response": r, "error": None})

class Unraid(Syno):
    def route(self, m):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n) if n else b""
        if self.path.startswith("/login"):
            b = b"<html>Unraid login</html>"; self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        if self.headers.get("x-api-key") != "ukey": return self.send({"errors": [{"message": "Unauthorized"}]}, 401)
        q = json.loads(body)["query"]; log("UNRAID", q[:40].replace("\n", " "))
        if "__typename" in q: return self.send({"data": {"__typename": "Query"}})
        if "info" in q: return self.send({"data": {"info": {"os": {"hostname": "tower", "uptime": "2026-10-30T00:00:00.000Z", "release": "7.2.0"}}}})
        if "array" in q: return self.send({"data": {"array": {"state": "STARTED", "capacity": {"kilobytes": {"free": "1000000", "used": "3000000", "total": "4000000"}}, "disks": [{"name": "disk1", "size": 4000000, "temp": 33, "status": "DISK_OK"}], "caches": []}}})
        if "docker" in q and "mutation" not in q: return self.send({"data": {"docker": {"containers": [{"id": "c1", "names": ["/jellyfin"], "state": "RUNNING", "image": "jellyfin/jellyfin"}]}}})
        if "mutation" in q: log("DOCKERMUT", q); return self.send({"data": {"docker": {}}})
        if "metrics" in q: return self.send({"data": {"metrics": {"cpu": {"percentTotal": 41.5}, "memory": {"total": 16000000000, "used": 6000000000}}}})
        return self.send({"errors": [{"message": "bad"}]})

UMBREL_APPS = [{"id": "jellyfin", "name": "Jellyfin", "version": "10", "port": 9100, "path": "", "state": "ready", "icon": "https://example.invalid/i.svg"},
               {"id": "immich", "name": "Immich", "version": "1", "port": 9101, "path": "/web", "state": "stopped"}]

class Umbrel(Syno):
    """tRPC answers in umbrelOS's shape. Delete /tmp/harbor-mock-jellyfin to 'uninstall' Jellyfin; creating it restores."""
    def route(self, m):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n) if n else b""
        proc = urllib.parse.urlparse(self.path).path.split("/")[-1]; log("UMBREL", m, proc)
        ok = lambda d: self.send({"result": {"data": d}})
        if proc == "system.online": return ok(True)
        if proc == "user.login":
            if json.loads(body or b"{}").get("password") == "secret": return ok("JWT123")
            return self.send({"error": {"message": "Incorrect password", "data": {"code": "UNAUTHORIZED", "httpStatus": 401}}}, 401)
        if self.headers.get("Authorization") != "Bearer JWT123":
            return self.send({"error": {"message": "Invalid token", "data": {"code": "UNAUTHORIZED", "httpStatus": 401}}}, 401)
        apps = [a for a in UMBREL_APPS if a["id"] != "jellyfin" or os.path.exists("/tmp/harbor-mock-jellyfin")]
        return ok({"system.cpuUsage": {"totalUsed": 23.5, "threads": 8, "apps": []}, "system.systemMemoryUsage": {"size": 16e9, "totalUsed": 6e9},
                   "system.systemDiskUsage": {"size": 1e12, "totalUsed": 4e11}, "system.uptime": 3600, "system.deviceName": "Umbrel Home",
                   "system.version": {"version": "2.1", "name": "umbrelOS 2.1"}, "apps.list": apps, "apps.updates": [{"id": "immich", "version": "2"}]}.get(proc))

class Casa(Syno):
    def route(self, m):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n) if n else b""
        path = urllib.parse.urlparse(self.path).path; log("CASA", m, path, body[:40])
        if path == "/v1/users/status": return self.send({"success": 200, "data": {"initialized": True}})
        if path == "/v1/users/login":
            if json.loads(body).get("password") == "secret": return self.send({"success": 200, "data": {"token": {"access_token": "CASATOK"}}})
            return self.send({"success": 10001, "message": "bad"}, 400)
        if self.headers.get("Authorization") != "CASATOK": return self.send({"success": 401}, 401)
        if path == "/v1/sys/utilization": return self.send({"success": 200, "data": {"cpu": {"percent": 12.5, "num": 4}, "mem": {"total": 8e9, "used": 3e9}, "sys_disk": {"size": 5e11, "used": 1e11}}})
        if path == "/v2/app_management/web/appgrid": return self.send({"success": 200, "data": [{"name": "syncthing", "title": {"en_us": "Syncthing"}, "status": "running", "port": "8384", "scheme": "http", "index": "/", "icon": "https://example.invalid/s.png"}]})
        if path.endswith("/status"): log("CASASTATUS", body); return self.send({"success": 200})
        if path == "/v1/folder": return self.send({"success": 200, "data": {"content": [{"name": "Media", "path": "/DATA/Media", "is_dir": True, "size": 0}, {"name": "a.txt", "path": "/DATA/a.txt", "is_dir": False, "size": 5}]}})
        return self.send({"success": 404}, 404)

class Proxmox(Syno):
    def route(self, m):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n) if n else b""
        path = urllib.parse.urlparse(self.path).path.replace("/api2/json", ""); log("PVE", m, path)
        if path == "/version": return self.send({"data": None}, 401)
        if path == "/access/ticket":
            q = urllib.parse.parse_qs(body.decode())
            if q.get("password") == ["secret"] and q.get("username") == ["root@pam"]: return self.send({"data": {"ticket": "TKT", "CSRFPreventionToken": "CSRF"}})
            return self.send({"data": None}, 401)
        if "PVEAuthCookie=TKT" not in self.headers.get("Cookie", ""): return self.send({"data": None}, 401)
        d = {"/nodes": [{"node": "pve"}], "/nodes/pve/status": {"cpu": 0.31, "uptime": 7200, "memory": {"total": 32e9, "used": 9e9}, "pveversion": "pve-manager/8.2", "cpuinfo": {"cpus": 12}},
             "/nodes/pve/storage": [{"storage": "local-zfs", "active": 1, "total": 2e12, "used": 5e11}], "/nodes/pve/qemu": [{"vmid": 100, "name": "homeassistant", "status": "running"}],
             "/nodes/pve/lxc": [{"vmid": 101, "name": "pihole", "status": "stopped"}]}
        return self.send({"data": d.get(path)})

class Qnap(Syno):
    def route(self, m):
        path = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(path.query); log("QNAP", m, path.path)
        x = lambda s: (self.send_response(200), self.send_header("Content-Type", "text/xml"), self.send_header("Content-Length", str(len(s))), self.end_headers(), self.wfile.write(s.encode()))
        if path.path.endswith("authLogin.cgi"):
            if "pwd" not in q: return x("<QDocRoot><authPassed><![CDATA[0]]></authPassed></QDocRoot>")
            ok = q["pwd"] == ["c2VjcmV0"]
            return x(f"<QDocRoot><authPassed><![CDATA[{int(ok)}]]></authPassed><authSid><![CDATA[S1]]></authSid></QDocRoot>")
        return x("<QDocRoot><authPassed><![CDATA[1]]></authPassed><func><ownContent><root><hostname><![CDATA[qnas]]></hostname><version><![CDATA[5.1]]></version><cpu_usage><![CDATA[9.5 %]]></cpu_usage><total_memory><![CDATA[7900]]></total_memory><free_memory><![CDATA[3000]]></free_memory><uptime_day><![CDATA[2]]></uptime_day><uptime_hour><![CDATA[1]]></uptime_hour><uptime_min><![CDATA[5]]></uptime_min></root></ownContent></func></QDocRoot>")

for port, h in [(5000, Syno), (8081, Omv), (8082, Unraid), (8083, Umbrel), (8084, Casa), (8085, Proxmox), (8086, Qnap)]:
    s = ThreadingHTTPServer(("0.0.0.0", port), h); threading.Thread(target=s.serve_forever, daemon=True).start()
print("mock up", flush=True)
threading.Event().wait()
