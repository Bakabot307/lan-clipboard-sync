import base64
import hashlib
import json
import os
import queue
import random
import socket
import struct
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from tkinter import BooleanVar, END, IntVar, Listbox, StringVar, Tk, TclError, ttk


APP_ID = "com.gnaht.phoneclipboardsync.room"
DISCOVERY_PORT = 8788
DISCOVERY_INTERVAL_SECONDS = 1.2
DISCOVERY_TIMEOUT_SECONDS = 10
DEFAULT_PORT = 8787
if getattr(sys, "frozen", False):
    CONFIG_PATH = Path(sys.executable).with_name("config.json")
else:
    CONFIG_PATH = Path(__file__).with_name("config.json")
MAX_HISTORY = 10
REMOTE_CLIPBOARD_SUPPRESSION_SECONDS = 15
DUPLICATE_OUTGOING_SECONDS = 1
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def now_ms() -> int:
    return int(time.time() * 1000)


def local_ipv4() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            return probe.getsockname()[0]
    except OSError:
        try:
            addresses = socket.gethostbyname_ex(socket.gethostname())[2]
        except OSError:
            return ""
        for address in addresses:
            if not address.startswith("127.") and not address.startswith("169.254."):
                return address
        return ""


def new_pair_code() -> str:
    return str(random.randint(100000, 999999))


def is_android_room_advertisement(message: dict) -> bool:
    # Kotlin serialization can omit default fields, including appId.
    return message.get("appId", APP_ID) == APP_ID and bool(message.get("hasOpenRoom", False))


def recv_exact(sock: socket.socket, length: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < length:
        chunk = sock.recv(length - len(chunks))
        if not chunk:
            raise ConnectionError("socket closed")
        chunks.extend(chunk)
    return bytes(chunks)


class WebSocketConnection:
    def __init__(self, sock: socket.socket, is_client: bool):
        self.sock = sock
        self.is_client = is_client
        self.send_lock = threading.Lock()
        self.closed = False

    def send_json(self, message: dict) -> None:
        self.send_text(json.dumps(message, separators=(",", ":")))

    def send_text(self, text: str) -> None:
        payload = text.encode("utf-8")
        header = bytearray([0x81])
        mask_bit = 0x80 if self.is_client else 0
        if len(payload) < 126:
            header.append(mask_bit | len(payload))
        elif len(payload) < 65536:
            header.append(mask_bit | 126)
            header.extend(struct.pack("!H", len(payload)))
        else:
            header.append(mask_bit | 127)
            header.extend(struct.pack("!Q", len(payload)))

        if self.is_client:
            mask = os.urandom(4)
            masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
            frame = bytes(header) + mask + masked
        else:
            frame = bytes(header) + payload

        with self.send_lock:
            self.sock.sendall(frame)

    def recv_text(self) -> str | None:
        while True:
            first, second = recv_exact(self.sock, 2)
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", recv_exact(self.sock, 2))[0]
            elif length == 127:
                length = struct.unpack("!Q", recv_exact(self.sock, 8))[0]

            mask = recv_exact(self.sock, 4) if masked else b""
            payload = recv_exact(self.sock, length) if length else b""
            if masked:
                payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))

            if opcode == 0x1:
                return payload.decode("utf-8")
            if opcode == 0x8:
                self.close()
                return None
            if opcode == 0x9:
                self._send_control(0xA, payload)
                continue
            if opcode == 0xA:
                continue

    def _send_control(self, opcode: int, payload: bytes = b"") -> None:
        header = bytearray([0x80 | opcode])
        mask_bit = 0x80 if self.is_client else 0
        header.append(mask_bit | len(payload))
        if self.is_client:
            mask = os.urandom(4)
            payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
            frame = bytes(header) + mask + payload
        else:
            frame = bytes(header) + payload
        with self.send_lock:
            self.sock.sendall(frame)

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self._send_control(0x8)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def websocket_server_handshake(sock: socket.socket) -> None:
    request = bytearray()
    while b"\r\n\r\n" not in request:
        chunk = sock.recv(1024)
        if not chunk:
            raise ConnectionError("empty handshake")
        request.extend(chunk)
        if len(request) > 8192:
            raise ConnectionError("handshake too large")

    headers = {}
    for line in request.decode("iso-8859-1").split("\r\n")[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
    ws_key = headers.get("sec-websocket-key")
    if not ws_key:
        raise ConnectionError("missing websocket key")
    accept = base64.b64encode(hashlib.sha1((ws_key + WS_GUID).encode("ascii")).digest()).decode("ascii")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    )
    sock.sendall(response.encode("ascii"))


def websocket_client_connect(host: str, port: int, timeout: float = 6) -> WebSocketConnection:
    sock = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (
        "GET / HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(request.encode("ascii"))
    response = bytearray()
    while b"\r\n\r\n" not in response:
        chunk = sock.recv(1024)
        if not chunk:
            raise ConnectionError("no handshake response")
        response.extend(chunk)
        if len(response) > 8192:
            raise ConnectionError("handshake response too large")
    first_line = response.decode("iso-8859-1", errors="replace").split("\r\n", 1)[0]
    if "101" not in first_line:
        raise ConnectionError(first_line)
    sock.settimeout(None)
    return WebSocketConnection(sock, is_client=True)


@dataclass
class Peer:
    device_id: str
    device_name: str


@dataclass
class PendingRequest:
    device_id: str
    device_name: str
    host_ip: str
    port: int
    room_code: str
    is_invitation: bool
    connection: WebSocketConnection | None = None


@dataclass
class AppState:
    device_id: str
    device_name: str
    pair_code: str
    port: int = DEFAULT_PORT
    auto_accept: bool = False
    auto_copy: bool = True
    monitor: bool = True
    host_ip: str = ""
    role: str = "HOST"
    peers: dict[WebSocketConnection, Peer] = field(default_factory=dict)
    client: WebSocketConnection | None = None
    running: bool = False
    group_device_id: str | None = None
    group_members: list[Peer] = field(default_factory=list)


class LanClipboardWindowsApp(Tk):
    def __init__(self):
        super().__init__()
        self.title("LAN Clipboard Sync")
        self.geometry("980x700")
        self.minsize(860, 620)

        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.stop_event = threading.Event()
        self.server_socket: socket.socket | None = None
        self.pending_requests: list[PendingRequest] = []
        self.discovered: list[dict] = []
        self.history: list[dict[str, str]] = []
        self.suppressed_remote: dict[str, float] = {}
        self.last_clipboard_text = ""
        self.last_sent_text = ""
        self.last_sent_at = 0.0

        self.state = self.load_state()
        self.device_name = StringVar(value=self.state.device_name)
        self.pair_code = StringVar(value=self.state.pair_code)
        self.port = IntVar(value=self.state.port)
        self.manual_host_ip = StringVar(value=self.state.host_ip)
        self.auto_accept = BooleanVar(value=self.state.auto_accept)
        self.auto_copy = BooleanVar(value=self.state.auto_copy)
        self.monitor = BooleanVar(value=self.state.monitor)
        self.status = StringVar(value="Starting...")
        self.local_ip = StringVar(value=local_ipv4() or "unknown")

        self.create_widgets()
        self.protocol("WM_DELETE_WINDOW", self.close_app)
        self.start_server()
        self.start_advertiser()
        self.after(100, self.process_events)
        self.after(700, self.poll_clipboard)
        self.after(5000, self.save_config)

    def load_state(self) -> AppState:
        data = {}
        if CONFIG_PATH.exists():
            try:
                data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                data = {}
        return AppState(
            device_id=data.get("device_id") or str(uuid.uuid4()),
            device_name=data.get("device_name") or socket.gethostname() or "Windows PC",
            pair_code=str(data.get("pair_code") or new_pair_code())[:6],
            host_ip=str(data.get("host_ip") or ""),
            port=int(data.get("port") or DEFAULT_PORT),
            auto_accept=bool(data.get("auto_accept", False)),
            auto_copy=bool(data.get("auto_copy", True)),
            monitor=bool(data.get("monitor", True)),
        )

    def save_config(self) -> None:
        self.apply_form_to_state()
        data = {
            "device_id": self.state.device_id,
            "device_name": self.state.device_name,
            "pair_code": self.state.pair_code,
            "host_ip": self.state.host_ip,
            "port": self.state.port,
            "auto_accept": self.state.auto_accept,
            "auto_copy": self.state.auto_copy,
            "monitor": self.state.monitor,
        }
        try:
            CONFIG_PATH.write_text(json.dumps(data, indent=2), encoding="utf-8")
        except OSError as exc:
            self.set_status(f"Could not save config: {exc}")
        if not self.stop_event.is_set():
            self.after(5000, self.save_config)

    def create_widgets(self) -> None:
        self.configure(bg="#f5f7fb")
        style = ttk.Style(self)
        for theme_name in ("vista", "xpnative", "clam"):
            if theme_name in style.theme_names():
                style.theme_use(theme_name)
                break
        style.configure("TFrame", background="#f5f7fb")
        style.configure("Card.TFrame", background="#ffffff", relief="flat")
        style.configure("TLabel", background="#f5f7fb", foreground="#374151", font=("Segoe UI", 9))
        style.configure("Card.TLabel", background="#ffffff", foreground="#374151", font=("Segoe UI", 9))
        style.configure("Header.TLabel", background="#f5f7fb", foreground="#111827", font=("Segoe UI", 16, "bold"))
        style.configure("Section.TLabel", background="#ffffff", foreground="#111827", font=("Segoe UI", 10, "bold"))
        style.configure("Status.TLabel", background="#f5f7fb", foreground="#2563eb", font=("Segoe UI", 9, "bold"))
        style.configure("TButton", font=("Segoe UI", 9), padding=(10, 5))
        style.configure("Accent.TButton", font=("Segoe UI", 9, "bold"), padding=(12, 6))
        style.configure("TCheckbutton", background="#f5f7fb", foreground="#374151", font=("Segoe UI", 9))
        style.configure("Card.TCheckbutton", background="#ffffff", foreground="#374151", font=("Segoe UI", 9))

        self.columnconfigure(0, weight=1)
        self.rowconfigure(3, weight=1)
        self.rowconfigure(5, weight=0)

        header = ttk.Frame(self, padding=(16, 14, 16, 8))
        header.grid(row=0, column=0, sticky="ew")
        header.columnconfigure(1, weight=1)
        ttk.Label(header, text="LAN Clipboard Sync", style="Header.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(header, textvariable=self.status, style="Status.TLabel").grid(row=0, column=1, sticky="e")
        ttk.Label(header, text="Local IP").grid(row=1, column=0, sticky="w", pady=(4, 0))
        ttk.Label(header, textvariable=self.local_ip).grid(row=1, column=1, sticky="e", pady=(4, 0))

        controls = ttk.Frame(self, padding=(16, 0, 16, 12))
        controls.grid(row=1, column=0, sticky="ew")
        controls.columnconfigure(0, weight=1)
        controls.columnconfigure(1, weight=1)

        identity = ttk.Frame(controls, padding=12, style="Card.TFrame")
        identity.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        identity.columnconfigure(1, weight=1)
        ttk.Label(identity, text="This PC", style="Section.TLabel").grid(row=0, column=0, columnspan=7, sticky="w", pady=(0, 10))
        ttk.Label(identity, text="Name", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        ttk.Entry(identity, textvariable=self.device_name).grid(row=1, column=1, sticky="ew", padx=(8, 12))
        ttk.Label(identity, text="Room", style="Card.TLabel").grid(row=1, column=2, sticky="w")
        ttk.Entry(identity, textvariable=self.pair_code, width=9, justify="center").grid(row=1, column=3, sticky="w", padx=(8, 6))
        ttk.Button(identity, text="New", command=self.refresh_pair_code).grid(row=1, column=4, sticky="w")
        ttk.Label(identity, text="Port", style="Card.TLabel").grid(row=1, column=5, sticky="w", padx=(12, 0))
        ttk.Spinbox(identity, from_=DEFAULT_PORT, to=DEFAULT_PORT, textvariable=self.port, width=7, state="readonly").grid(row=1, column=6, sticky="w", padx=(8, 0))

        settings = ttk.Frame(controls, padding=12, style="Card.TFrame")
        settings.grid(row=0, column=1, sticky="ew", padx=(8, 0))
        ttk.Label(settings, text="Clipboard", style="Section.TLabel").pack(anchor="w", pady=(0, 10))
        ttk.Checkbutton(settings, text="Monitor", variable=self.monitor, command=self.save_config, style="Card.TCheckbutton").pack(side="left")
        ttk.Checkbutton(settings, text="Auto-copy received", variable=self.auto_copy, command=self.save_config, style="Card.TCheckbutton").pack(side="left", padx=14)

        actions = ttk.Frame(self, padding=12, style="Card.TFrame")
        actions.grid(row=2, column=0, sticky="ew", padx=16, pady=(0, 12))
        actions.columnconfigure(5, weight=1)
        ttk.Label(actions, text="Connection", style="Section.TLabel").grid(row=0, column=0, columnspan=8, sticky="w", pady=(0, 10))
        ttk.Button(actions, text="Scan", command=self.scan_lan, style="Accent.TButton").grid(row=1, column=0, sticky="w")
        ttk.Button(actions, text="Connect Selected", command=self.connect_selected).grid(row=1, column=1, sticky="w", padx=(8, 18))
        ttk.Label(actions, text="Android IP", style="Card.TLabel").grid(row=1, column=2, sticky="w")
        ttk.Entry(actions, textvariable=self.manual_host_ip, width=17).grid(row=1, column=3, sticky="w", padx=(8, 8))
        ttk.Button(actions, text="Connect IP", command=self.connect_manual_ip).grid(row=1, column=4, sticky="w")
        ttk.Button(actions, text="Send Clipboard", command=self.send_current_clipboard).grid(row=1, column=6, sticky="e", padx=(8, 0))
        ttk.Button(actions, text="Disconnect", command=self.disconnect).grid(row=1, column=7, sticky="e", padx=(8, 0))
        ttk.Button(actions, text="Firewall", command=self.open_firewall_helper).grid(row=1, column=8, sticky="e", padx=(8, 0))

        panes = ttk.Panedwindow(self, orient="horizontal")
        panes.grid(row=3, column=0, sticky="nsew", padx=16, pady=(0, 12))

        left = ttk.Frame(panes, padding=0)
        right = ttk.Frame(panes, padding=0)
        panes.add(left, weight=1)
        panes.add(right, weight=1)

        left.rowconfigure(1, weight=1)
        left.rowconfigure(4, weight=1)
        left.columnconfigure(0, weight=1)
        ttk.Label(left, text="Found Devices").grid(row=0, column=0, sticky="w")
        self.discovery_list = self.make_listbox(left, height=9)
        self.discovery_list.grid(row=1, column=0, sticky="nsew", pady=(4, 14))

        ttk.Label(left, text="Requests").grid(row=2, column=0, sticky="w")
        request_actions = ttk.Frame(left)
        request_actions.grid(row=3, column=0, sticky="ew", pady=(4, 4))
        ttk.Button(request_actions, text="Accept", command=self.accept_selected_request).pack(side="left")
        ttk.Button(request_actions, text="Reject", command=self.reject_selected_request).pack(side="left", padx=8)
        ttk.Checkbutton(request_actions, text="Auto-accept", variable=self.auto_accept, command=self.save_config).pack(side="left", padx=14)
        self.request_list = self.make_listbox(left, height=6)
        self.request_list.grid(row=4, column=0, sticky="nsew")

        right.rowconfigure(1, weight=1)
        right.rowconfigure(3, weight=1)
        right.columnconfigure(0, weight=1)
        ttk.Label(right, text="Connected Devices").grid(row=0, column=0, sticky="w")
        self.peer_list = self.make_listbox(right, height=7)
        self.peer_list.grid(row=1, column=0, sticky="nsew", pady=(4, 14))

        history_actions = ttk.Frame(right)
        history_actions.grid(row=2, column=0, sticky="ew")
        history_actions.columnconfigure(0, weight=1)
        ttk.Label(history_actions, text="Clipboard History").grid(row=0, column=0, sticky="w")
        ttk.Button(history_actions, text="Send Selected", command=self.send_selected_history).grid(row=0, column=1, sticky="e")
        self.history_list = self.make_listbox(right, height=10)
        self.history_list.bind("<<ListboxSelect>>", self.copy_selected_history)
        self.history_list.grid(row=3, column=0, sticky="nsew", pady=(4, 0))

        log_frame = ttk.Frame(self, padding=(12, 0, 12, 12))
        log_frame.grid(row=4, column=0, sticky="ew")
        log_frame.columnconfigure(0, weight=1)
        ttk.Label(log_frame, text="Log").grid(row=0, column=0, sticky="w")
        self.log_list = self.make_listbox(log_frame, height=4)
        self.log_list.grid(row=1, column=0, sticky="ew", pady=(4, 0))

    def make_listbox(self, parent, height: int) -> Listbox:
        return Listbox(
            parent,
            height=height,
            exportselection=False,
            activestyle="none",
            borderwidth=0,
            highlightthickness=1,
            highlightbackground="#d1d5db",
            highlightcolor="#2563eb",
            background="#ffffff",
            foreground="#111827",
            selectbackground="#dbeafe",
            selectforeground="#111827",
            font=("Segoe UI", 9),
            relief="flat",
        )

    def apply_form_to_state(self) -> None:
        self.state.device_name = self.device_name.get().strip() or "Windows PC"
        self.state.pair_code = "".join(ch for ch in self.pair_code.get() if ch.isdigit())[:6] or new_pair_code()
        self.pair_code.set(self.state.pair_code)
        self.state.host_ip = self.manual_host_ip.get().strip()
        try:
            self.state.port = int(self.port.get())
        except TclError:
            self.state.port = DEFAULT_PORT
            self.port.set(DEFAULT_PORT)
        self.state.auto_accept = self.auto_accept.get()
        self.state.auto_copy = self.auto_copy.get()
        self.state.monitor = self.monitor.get()

    def refresh_pair_code(self) -> None:
        self.pair_code.set(new_pair_code())
        self.save_config()
        self.set_status("Room code changed")

    def set_status(self, text: str) -> None:
        self.status.set(text)
        self.add_log(text)

    def add_log(self, text: str) -> None:
        timestamp = time.strftime("%H:%M:%S")
        self.log_list.insert(END, f"{timestamp}  {text}")
        while self.log_list.size() > 80:
            self.log_list.delete(0)
        self.log_list.see(END)

    def post(self, event: str, payload: object = None) -> None:
        self.events.put((event, payload))

    def process_events(self) -> None:
        while True:
            try:
                event, payload = self.events.get_nowait()
            except queue.Empty:
                break
            if event == "status":
                self.set_status(str(payload))
            elif event == "discovered":
                self.add_discovered(payload)
            elif event == "pending":
                self.add_pending(payload)
            elif event == "peers":
                self.refresh_peers()
            elif event == "remote_clip":
                self.receive_remote_clip(payload)
            elif event == "client_closed":
                self.handle_client_closed(str(payload))
            elif event == "local_ip":
                self.local_ip.set(str(payload) or "unknown")
            elif event == "log":
                self.add_log(str(payload))
        if not self.stop_event.is_set():
            self.after(100, self.process_events)

    def start_server(self) -> None:
        self.apply_form_to_state()
        if self.server_socket:
            return
        thread = threading.Thread(target=self.server_loop, daemon=True)
        thread.start()

    def restart_server(self) -> None:
        self.disconnect()
        if self.server_socket:
            try:
                self.server_socket.close()
            except OSError:
                pass
            self.server_socket = None
        self.start_server()

    def server_loop(self) -> None:
        try:
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("", self.state.port))
            server.listen()
            self.server_socket = server
            self.post("status", f"Ready on port {self.state.port}")
            self.post("log", f"TCP server listening on 0.0.0.0:{self.state.port}")
        except OSError as exc:
            self.post("status", f"Could not start server: {exc}")
            return

        while not self.stop_event.is_set():
            try:
                sock, address = server.accept()
            except OSError:
                break
            self.post("log", f"TCP connection from {address[0]}:{address[1]}")
            threading.Thread(target=self.handle_server_socket, args=(sock, address), daemon=True).start()

    def handle_server_socket(self, sock: socket.socket, address) -> None:
        conn = None
        try:
            websocket_server_handshake(sock)
            self.post("log", f"WebSocket handshake OK from {address[0]}")
            conn = WebSocketConnection(sock, is_client=False)
            while not self.stop_event.is_set():
                text = conn.recv_text()
                if text is None:
                    break
                message = json.loads(text)
                kind = message.get("kind")
                self.post("log", f"Received {kind or 'unknown'} from {address[0]}")
                if kind == "hello":
                    self.handle_hello(conn, message, address)
                elif kind == "clip":
                    self.handle_remote_clip_from_peer(conn, message.get("payload") or {})
                elif kind == "invite":
                    self.handle_invite(message)
                    break
        except (OSError, ConnectionError, json.JSONDecodeError) as exc:
            self.post("status", f"Connection error from {address[0]}: {exc}")
        finally:
            if conn:
                self.remove_peer(conn)

    def handle_hello(self, conn: WebSocketConnection, message: dict, address) -> None:
        if self.state.role == "CLIENT" and self.state.running:
            conn.send_json({"kind": "join_response", "accepted": False, "reason": "Device is already in another group"})
            conn.close()
            return
        if str(message.get("pairCode", "")) != self.state.pair_code:
            conn.send_json({"kind": "join_response", "accepted": False, "reason": "Invalid connection code"})
            conn.close()
            return

        peer = Peer(str(message.get("deviceId") or ""), str(message.get("deviceName") or "Unknown"))
        if self.state.auto_accept:
            self.accept_peer(conn, peer, auto=True)
            return

        request = PendingRequest(
            device_id=peer.device_id,
            device_name=peer.device_name,
            host_ip=address[0],
            port=self.state.port,
            room_code=self.state.pair_code,
            is_invitation=False,
            connection=conn,
        )
        self.post("pending", request)
        self.post("status", f"{peer.device_name} requested to connect")

    def handle_invite(self, message: dict) -> None:
        request = PendingRequest(
            device_id=str(message.get("inviterDeviceId") or ""),
            device_name=str(message.get("inviterDeviceName") or "Unknown"),
            host_ip=str(message.get("hostIp") or ""),
            port=int(message.get("port") or DEFAULT_PORT),
            room_code=str(message.get("roomCode") or ""),
            is_invitation=True,
        )
        if self.state.auto_accept:
            self.post("status", f"Auto-accepting invitation from {request.device_name}")
            self.connect_to_host(request.host_ip, request.port, request.room_code, group_device_id=request.device_id)
        else:
            self.post("pending", request)
            self.post("status", f"{request.device_name} invited you to connect")

    def accept_peer(self, conn: WebSocketConnection, peer: Peer, auto: bool = False) -> None:
        self.state.role = "HOST"
        self.state.running = True
        self.state.peers[conn] = peer
        conn.send_json({
            "kind": "join_response",
            "accepted": True,
            "reason": "",
            "hostDeviceName": self.state.device_name,
            "hostDeviceId": self.state.device_id,
            "peers": self.participant_wires(),
        })
        self.broadcast_peer_list()
        self.post("peers")
        self.post("status", f"{'Auto-accepted' if auto else 'Connected'}: {peer.device_name}")

    def remove_peer(self, conn: WebSocketConnection) -> None:
        if conn in self.state.peers:
            del self.state.peers[conn]
            if not self.state.peers:
                self.state.running = False
                self.state.role = "HOST"
                self.state.group_members = []
            self.broadcast_peer_list()
            self.post("peers")

    def participant_wires(self) -> list[dict]:
        peers = [{"deviceId": "host", "deviceName": self.state.device_name}]
        peers.extend({"deviceId": peer.device_id, "deviceName": peer.device_name} for peer in self.state.peers.values())
        return peers

    def broadcast_peer_list(self) -> None:
        message = {"kind": "peers", "peers": self.participant_wires()}
        for conn in list(self.state.peers.keys()):
            try:
                conn.send_json(message)
            except OSError:
                self.remove_peer(conn)

    def start_advertiser(self) -> None:
        threading.Thread(target=self.advertise_loop, daemon=True).start()

    def advertise_loop(self) -> None:
        while not self.stop_event.is_set():
            host_ip = local_ipv4()
            self.post("local_ip", host_ip or "unknown")
            sorted_members = sorted(peer.device_name for peer in self.current_group_members())
            message = {
                "appId": APP_ID,
                "deviceId": self.advertised_device_id(),
                "roomCode": self.state.pair_code,
                "deviceName": self.advertised_name(sorted_members),
                "hostIp": self.advertised_host_ip(host_ip),
                "port": self.state.port,
                "hasOpenRoom": True,
                "memberNames": sorted_members,
            }
            try:
                data = json.dumps(message, separators=(",", ":")).encode("utf-8")
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    sock.sendto(data, ("255.255.255.255", DISCOVERY_PORT))
            except OSError as exc:
                self.post("status", f"Could not advertise: {exc}")
            self.stop_event.wait(DISCOVERY_INTERVAL_SECONDS)

    def advertised_device_id(self) -> str:
        if self.state.role == "CLIENT" and self.state.running:
            return self.state.group_device_id or self.state.host_ip
        return self.state.device_id

    def advertised_host_ip(self, host_ip: str) -> str:
        if self.state.role == "CLIENT" and self.state.running:
            return self.state.host_ip
        # Let Android use the packet source address. Windows can have VPN,
        # virtual, or stale adapters, so a guessed hostIp may be unreachable.
        return ""

    def advertised_name(self, sorted_member_names: list = None) -> str:
        if sorted_member_names is None:
            sorted_member_names = sorted(peer.device_name for peer in self.current_group_members())
        if len(sorted_member_names) > 1:
            return "Group " + ", ".join(sorted_member_names)
        return self.state.device_name

    def current_group_members(self) -> list[Peer]:
        if self.state.running and self.state.group_members:
            return self.state.group_members
        return [Peer(self.state.device_id, self.state.device_name)]

    def scan_lan(self) -> None:
        self.apply_form_to_state()
        self.discovered.clear()
        self.discovery_list.delete(0, END)
        self.set_status(f"Scanning LAN on UDP {DISCOVERY_PORT}...")
        threading.Thread(target=self.scan_loop, daemon=True).start()

    def scan_loop(self) -> None:
        started = time.time()
        seen = set()
        packet_count = 0
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                if hasattr(socket, "SO_REUSEPORT"):
                    try:
                        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                    except OSError:
                        pass
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.settimeout(1)
                sock.bind(("", DISCOVERY_PORT))
                while time.time() - started < DISCOVERY_TIMEOUT_SECONDS and not self.stop_event.is_set():
                    try:
                        data, address = sock.recvfrom(4096)
                    except socket.timeout:
                        continue
                    packet_count += 1
                    try:
                        message = json.loads(data.decode("utf-8"))
                    except json.JSONDecodeError:
                        continue
                    packet_name = str(message.get("deviceName") or "?")
                    packet_device_id = str(message.get("deviceId") or "")
                    packet_host_ip = str(message.get("hostIp") or address[0])
                    packet_app_id = str(message.get("appId", "<missing>"))
                    packet_open = message.get("hasOpenRoom", "<missing>")
                    self.post(
                        "log",
                        f"UDP from {address[0]} name={packet_name} host={packet_host_ip} open={packet_open} appId={packet_app_id}",
                    )
                    if not is_android_room_advertisement(message):
                        self.post("log", f"Ignored UDP from {address[0]}: not a compatible room advertisement")
                        continue
                    device_id = str(message.get("deviceId") or "")
                    if device_id in {self.state.device_id, self.state.group_device_id} or device_id in seen:
                        reason = "self" if device_id == self.state.device_id else "duplicate/group"
                        self.post("log", f"Ignored UDP from {address[0]}: {reason}")
                        continue
                    seen.add(device_id)
                    item = {
                        "deviceId": device_id,
                        "deviceName": str(message.get("deviceName") or "Unknown"),
                        "hostIp": str(message.get("hostIp") or address[0]),
                        "port": int(message.get("port") or DEFAULT_PORT),
                        "roomCode": str(message.get("roomCode") or ""),
                        "memberNames": list(message.get("memberNames") or []),
                    }
                    self.post("discovered", item)
                    self.post("log", f"Found {item['deviceName']} at {item['hostIp']}:{item['port']}")
        except OSError as exc:
            self.post("status", f"Could not scan LAN: {exc}")
            return
        if seen:
            self.post("status", f"Found {len(seen)} devices or groups")
        elif packet_count:
            self.post("status", f"No compatible devices found ({packet_count} UDP packets received)")
        else:
            self.post("status", "No devices found (no UDP packets received)")

    def add_discovered(self, item: dict) -> None:
        self.discovered = [existing for existing in self.discovered if existing["deviceId"] != item["deviceId"]]
        self.discovered.append(item)
        self.discovery_list.delete(0, END)
        for entry in self.discovered:
            members = ", ".join(entry.get("memberNames") or [entry["deviceName"]])
            self.discovery_list.insert(END, f"{entry['deviceName']}  {entry['hostIp']}:{entry['port']}  [{members}]")

    def connect_selected(self) -> None:
        selection = self.discovery_list.curselection()
        if not selection:
            self.set_status("Select a device first")
            return
        item = self.discovered[selection[0]]
        self.connect_to_host(item["hostIp"], item["port"], item["roomCode"], group_device_id=item["deviceId"])

    def connect_manual_ip(self) -> None:
        self.apply_form_to_state()
        host_ip = self.state.host_ip
        if not host_ip:
            self.set_status("Enter Android IP first")
            return
        self.save_config()
        self.connect_to_host(host_ip, DEFAULT_PORT, self.state.pair_code, group_device_id=host_ip)

    def connect_to_host(self, host_ip: str, port: int, room_code: str, group_device_id: str | None = None) -> None:
        self.apply_form_to_state()
        self.disconnect()
        self.set_status(f"Connecting to {host_ip}:{port}")
        threading.Thread(
            target=self.client_loop,
            args=(host_ip, port, room_code, group_device_id),
            daemon=True,
        ).start()

    def client_loop(self, host_ip: str, port: int, room_code: str, group_device_id: str | None) -> None:
        conn = None
        final_reason = "Disconnected"
        try:
            conn = websocket_client_connect(host_ip, port)
            self.state.client = conn
            self.state.host_ip = host_ip
            self.state.group_device_id = group_device_id or host_ip
            conn.send_json({
                "kind": "hello",
                "deviceId": self.state.device_id,
                "deviceName": self.state.device_name,
                "pairCode": room_code,
            })
            self.post("status", "Waiting for host approval")
            while not self.stop_event.is_set():
                text = conn.recv_text()
                if text is None:
                    break
                message = json.loads(text)
                kind = message.get("kind")
                if kind == "join_response":
                    if message.get("accepted"):
                        self.state.role = "CLIENT"
                        self.state.running = True
                        host_device_id = message.get("hostDeviceId")
                        if host_device_id:
                            self.state.group_device_id = host_device_id
                        self.update_group_members(message.get("peers") or [])
                        self.post("status", f"Connected to {message.get('hostDeviceName') or 'host'}")
                        self.post("peers")
                    else:
                        final_reason = message.get("reason") or "Connection rejected"
                        self.post("status", final_reason)
                        break
                elif kind == "peers":
                    self.update_group_members(message.get("peers") or [])
                    self.post("peers")
                elif kind == "clip":
                    self.post("remote_clip", message.get("payload") or {})
        except (OSError, ConnectionError, json.JSONDecodeError) as exc:
            final_reason = f"Connection failed: {exc}"
            self.post("status", final_reason)
        finally:
            if conn:
                conn.close()
            self.post("client_closed", final_reason)

    def update_group_members(self, wires: list[dict]) -> None:
        members = []
        for wire in wires:
            device_id = str(wire.get("deviceId") or "")
            device_name = str(wire.get("deviceName") or "Unknown")
            if device_id != self.state.device_id:
                members.append(Peer(device_id, device_name))
        self.state.group_members = members

    def handle_client_closed(self, reason: str) -> None:
        if self.state.role == "CLIENT":
            self.state.client = None
            self.state.running = False
            self.state.role = "HOST"
            self.state.group_members = []
            self.state.group_device_id = None
            self.refresh_peers()
            self.set_status(reason)

    def add_pending(self, request: PendingRequest) -> None:
        self.pending_requests = [
            existing for existing in self.pending_requests
            if not (existing.device_id == request.device_id and existing.is_invitation == request.is_invitation)
        ]
        self.pending_requests.append(request)
        self.refresh_pending()

    def refresh_pending(self) -> None:
        self.request_list.delete(0, END)
        for request in self.pending_requests:
            action = "invited you" if request.is_invitation else "wants to connect"
            self.request_list.insert(END, f"{request.device_name} {action} ({request.host_ip}:{request.port})")
        if self.pending_requests:
            newest_index = len(self.pending_requests) - 1
            self.request_list.selection_clear(0, END)
            self.request_list.selection_set(newest_index)
            self.request_list.activate(newest_index)
            self.request_list.see(newest_index)

    def accept_selected_request(self) -> None:
        selection = self.request_list.curselection()
        if not selection:
            if not self.pending_requests:
                self.set_status("No request to accept")
                return
            index = len(self.pending_requests) - 1
        else:
            index = selection[0]
        request = self.pending_requests.pop(index)
        self.refresh_pending()
        if request.is_invitation:
            self.connect_to_host(request.host_ip, request.port, request.room_code, group_device_id=request.device_id)
        elif request.connection:
            self.accept_peer(request.connection, Peer(request.device_id, request.device_name))

    def reject_selected_request(self) -> None:
        selection = self.request_list.curselection()
        if not selection:
            if not self.pending_requests:
                self.set_status("No request to reject")
                return
            index = len(self.pending_requests) - 1
        else:
            index = selection[0]
        request = self.pending_requests.pop(index)
        self.refresh_pending()
        if request.connection:
            try:
                request.connection.send_json({"kind": "join_response", "accepted": False, "reason": "Connection rejected"})
            finally:
                request.connection.close()
        self.set_status(f"Rejected {request.device_name}")

    def refresh_peers(self) -> None:
        self.peer_list.delete(0, END)
        if self.state.role == "HOST":
            peers = list(self.state.peers.values())
        else:
            peers = self.state.group_members
        for peer in peers:
            self.peer_list.insert(END, peer.device_name)
        if not peers:
            self.peer_list.insert(END, "No connected devices")

    def poll_clipboard(self) -> None:
        if self.monitor.get():
            text = self.read_clipboard()
            if text and text != self.last_clipboard_text:
                self.last_clipboard_text = text
                self.publish_clipboard(text, automatic=True)
        if not self.stop_event.is_set():
            self.after(700, self.poll_clipboard)

    def read_clipboard(self) -> str:
        try:
            return self.clipboard_get()
        except TclError:
            return ""

    def write_clipboard(self, text: str) -> None:
        self.clipboard_clear()
        self.clipboard_append(text)
        self.update()

    def send_current_clipboard(self) -> None:
        text = self.read_clipboard()
        if not text.strip():
            self.set_status("Clipboard is empty")
            return
        self.publish_clipboard(text, automatic=False)

    def publish_clipboard(self, text: str, automatic: bool) -> None:
        cleaned = text.strip("\x00")
        if not cleaned.strip():
            return
        current_time = time.time()
        self.prune_suppressions(current_time)
        if cleaned in self.suppressed_remote:
            return
        if cleaned == self.last_sent_text and current_time - self.last_sent_at < DUPLICATE_OUTGOING_SECONDS:
            return
        payload = {
            "clipId": str(uuid.uuid4()),
            "sourceDeviceId": self.state.device_id,
            "sourceDeviceName": self.state.device_name,
            "text": cleaned,
            "timestamp": now_ms(),
        }
        delivered = False
        message = {"kind": "clip", "payload": payload}
        if self.state.role == "HOST" and self.state.peers:
            for conn in list(self.state.peers.keys()):
                try:
                    conn.send_json(message)
                    delivered = True
                except OSError:
                    self.remove_peer(conn)
        elif self.state.role == "CLIENT" and self.state.client:
            try:
                self.state.client.send_json(message)
                delivered = True
            except OSError:
                delivered = False
        if delivered:
            self.last_sent_text = cleaned
            self.last_sent_at = current_time
            self.add_history("Sent", self.state.device_name, cleaned)
            self.set_status("Clipboard sent")
        elif not automatic:
            self.set_status("No connected devices to receive clipboard")

    def handle_remote_clip_from_peer(self, conn: WebSocketConnection, payload: dict) -> None:
        if conn not in self.state.peers:
            conn.close()
            return
        self.post("remote_clip", payload)
        message = {"kind": "clip", "payload": payload}
        for peer_conn in list(self.state.peers.keys()):
            if peer_conn is conn:
                continue
            try:
                peer_conn.send_json(message)
            except OSError:
                self.remove_peer(peer_conn)

    def receive_remote_clip(self, payload: dict) -> None:
        if payload.get("sourceDeviceId") == self.state.device_id:
            return
        text = str(payload.get("text") or "")
        source = str(payload.get("sourceDeviceName") or "Remote")
        if not text:
            return
        self.add_history("Received", source, text)
        self.set_status(f"Received clipboard from {source}")
        if self.auto_copy.get():
            self.suppressed_remote[text] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
            self.write_clipboard(text)
            self.last_clipboard_text = text

    def prune_suppressions(self, current_time: float) -> None:
        expired = [text for text, expires_at in self.suppressed_remote.items() if expires_at <= current_time]
        for text in expired:
            del self.suppressed_remote[text]

    def add_history(self, direction: str, source: str, text: str) -> None:
        preview = text.replace("\n", " ")
        if len(preview) > 90:
            preview = preview[:87] + "..."
        self.history.insert(0, {"direction": direction, "source": source, "text": text, "preview": preview})
        self.history = self.history[:MAX_HISTORY]
        self.history_list.delete(0, END)
        for entry in self.history:
            self.history_list.insert(END, f"{entry['direction']} - {entry['source']}: {entry['preview']}")

    def copy_selected_history(self, event=None) -> None:
        selection = self.history_list.curselection()
        if not selection:
            return
        text = self.history[selection[0]]["text"]
        self.suppressed_remote[text] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
        self.write_clipboard(text)
        self.last_clipboard_text = text
        self.set_status("Copied history item")

    def send_selected_history(self) -> None:
        selection = self.history_list.curselection()
        if not selection:
            self.set_status("Select a history item first")
            return
        self.publish_clipboard(self.history[selection[0]]["text"], automatic=False)

    def disconnect(self, close_server_peers: bool = True) -> None:
        if self.state.client:
            self.state.client.close()
            self.state.client = None
        if close_server_peers:
            peers = list(self.state.peers.keys())
            for conn in peers:
                try:
                    conn.send_json({"kind": "peers", "peers": []})
                except OSError:
                    pass
            if peers:
                time.sleep(0.08)
            for conn in list(self.state.peers.keys()):
                conn.close()
            self.state.peers.clear()
        self.state.running = False
        self.state.role = "HOST"
        self.state.group_members = []
        self.state.group_device_id = None
        self.refresh_peers()
        self.set_status("Disconnected")

    def open_firewall_helper(self) -> None:
        script = Path(__file__).with_name("allow_windows_firewall.bat")
        if not script.exists():
            self.set_status("Firewall helper not found")
            return
        try:
            os.startfile(script)
            self.set_status("Opened firewall helper")
        except OSError as exc:
            self.set_status(f"Could not open firewall helper: {exc}")

    def close_app(self) -> None:
        self.stop_event.set()
        self.save_config()
        self.disconnect()
        if self.server_socket:
            try:
                self.server_socket.close()
            except OSError:
                pass
        self.destroy()


if __name__ == "__main__":
    app = LanClipboardWindowsApp()
    app.mainloop()
