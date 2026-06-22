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
import ctypes
from ctypes import wintypes
import mimetypes
import shutil
import subprocess
import tempfile
import winsound
from dataclasses import dataclass, field
from pathlib import Path
from tkinter import BooleanVar, END, IntVar, Listbox, StringVar, Tk, TclError, Toplevel, filedialog, ttk

CF_HDROP = 15
CF_BITMAP = 2
CF_DIB = 8
CF_DIBV5 = 17
BI_BITFIELDS = 3
BI_ALPHABITFIELDS = 6


def is_format_available(fmt: int) -> bool:
    try:
        return ctypes.windll.user32.IsClipboardFormatAvailable(fmt) != 0
    except Exception:
        return False


def is_image_format_available() -> bool:
    return any(is_format_available(fmt) for fmt in (CF_DIB, CF_DIBV5, CF_BITMAP))


def get_clipboard_sequence_number() -> int:
    try:
        return int(ctypes.windll.user32.GetClipboardSequenceNumber())
    except Exception:
        return 0


def dib_to_bmp(dib: bytes) -> bytes | None:
    if len(dib) < 40:
        return None
    header_size = struct.unpack_from("<I", dib, 0)[0]
    if header_size < 12 or header_size > len(dib):
        return None

    palette_bytes = 0
    mask_bytes = 0
    if header_size == 12:
        bit_count = struct.unpack_from("<H", dib, 10)[0]
        if bit_count <= 8:
            palette_bytes = (1 << bit_count) * 3
    else:
        bit_count = struct.unpack_from("<H", dib, 14)[0]
        compression = struct.unpack_from("<I", dib, 16)[0]
        colors_used = struct.unpack_from("<I", dib, 32)[0] if len(dib) >= 36 else 0
        if bit_count <= 8:
            palette_bytes = (colors_used or (1 << bit_count)) * 4
        if header_size == 40 and compression in (BI_BITFIELDS, BI_ALPHABITFIELDS):
            mask_bytes = 16 if compression == BI_ALPHABITFIELDS else 12

    pixel_offset = 14 + header_size + palette_bytes + mask_bytes
    file_size = 14 + len(dib)
    return b"BM" + struct.pack("<IHHI", file_size, 0, 0, pixel_offset) + dib



APP_ID = "com.gnaht.phoneclipboardsync.room"
APP_NAME = "LAN Clipboard Sync"
WINDOWS_APP_USER_MODEL_ID = "com.gnaht.phoneclipboardsync.windows"
DISCOVERY_PORT = 8788
DISCOVERY_INTERVAL_SECONDS = 1.2
DISCOVERY_TIMEOUT_SECONDS = 10
DEFAULT_PORT = 8787
if getattr(sys, "frozen", False):
    APP_DIR = Path(sys.executable).parent
else:
    APP_DIR = Path(__file__).parent
CONFIG_PATH = APP_DIR / "config.json"
ICON_PATH = APP_DIR / "lan_clipboard.ico"
DEFAULT_IMAGE_DIR = APP_DIR / "images"
MAX_HISTORY = 10
REMOTE_CLIPBOARD_SUPPRESSION_SECONDS = 15
DUPLICATE_OUTGOING_SECONDS = 1
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
WM_TRAYICON = 0x0400 + 20
TRAY_OPEN_ID = 1001
TRAY_EXIT_ID = 1002


if os.name == "nt":
    LRESULT = ctypes.c_longlong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_long
    LPARAM = ctypes.c_ssize_t
    WPARAM = ctypes.c_size_t
    HBRUSH = getattr(wintypes, "HBRUSH", wintypes.HANDLE)
    HCURSOR = getattr(wintypes, "HCURSOR", wintypes.HANDLE)
    HICON = getattr(wintypes, "HICON", wintypes.HANDLE)
    HINSTANCE = getattr(wintypes, "HINSTANCE", wintypes.HANDLE)
    HMENU = getattr(wintypes, "HMENU", wintypes.HANDLE)
    HMODULE = getattr(wintypes, "HMODULE", wintypes.HANDLE)
    WNDPROC = ctypes.WINFUNCTYPE(
        LRESULT,
        wintypes.HWND,
        wintypes.UINT,
        WPARAM,
        LPARAM,
    )

    class WNDCLASSW(ctypes.Structure):
        _fields_ = [
            ("style", wintypes.UINT),
            ("lpfnWndProc", WNDPROC),
            ("cbClsExtra", ctypes.c_int),
            ("cbWndExtra", ctypes.c_int),
            ("hInstance", HINSTANCE),
            ("hIcon", HICON),
            ("hCursor", HCURSOR),
            ("hbrBackground", HBRUSH),
            ("lpszMenuName", wintypes.LPCWSTR),
            ("lpszClassName", wintypes.LPCWSTR),
        ]

    class NOTIFYICONDATAW(ctypes.Structure):
        _fields_ = [
            ("cbSize", wintypes.DWORD),
            ("hWnd", wintypes.HWND),
            ("uID", wintypes.UINT),
            ("uFlags", wintypes.UINT),
            ("uCallbackMessage", wintypes.UINT),
            ("hIcon", HICON),
            ("szTip", wintypes.WCHAR * 128),
            ("dwState", wintypes.DWORD),
            ("dwStateMask", wintypes.DWORD),
            ("szInfo", wintypes.WCHAR * 256),
            ("uTimeoutOrVersion", wintypes.UINT),
            ("szInfoTitle", wintypes.WCHAR * 64),
            ("dwInfoFlags", wintypes.DWORD),
            ("guidItem", ctypes.c_byte * 16),
            ("hBalloonIcon", HICON),
        ]


class WindowsTrayIcon:
    NIM_ADD = 0x00000000
    NIM_MODIFY = 0x00000001
    NIM_DELETE = 0x00000002
    NIF_MESSAGE = 0x00000001
    NIF_ICON = 0x00000002
    NIF_TIP = 0x00000004
    NIF_INFO = 0x00000010
    NIIF_INFO = 0x00000001
    WM_CLOSE = 0x0010
    WM_COMMAND = 0x0111
    WM_RBUTTONUP = 0x0205
    WM_LBUTTONDBLCLK = 0x0203
    TPM_RETURNCMD = 0x0100
    TPM_NONOTIFY = 0x0080
    MF_STRING = 0x0000
    IMAGE_ICON = 1
    LR_LOADFROMFILE = 0x0010
    LR_DEFAULTSIZE = 0x0040
    IDI_APPLICATION = 32512

    def __init__(self, on_open, on_exit, icon_path: Path | None = None):
        self.on_open = on_open
        self.on_exit = on_exit
        self.icon_path = icon_path
        self.hwnd = None
        self.hicon = None
        self.hinstance = None
        self.class_name = f"LanClipboardTray{uuid.uuid4().hex}"
        self.ready = threading.Event()
        self.thread: threading.Thread | None = None
        self.wndproc = None

    def start(self) -> None:
        if os.name != "nt" or self.thread:
            return
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        self.ready.wait(timeout=2)

    def stop(self) -> None:
        if os.name != "nt" or not self.hwnd:
            return
        ctypes.windll.user32.PostMessageW(self.hwnd, self.WM_CLOSE, 0, 0)

    def show_balloon(self, title: str, message: str) -> None:
        if os.name != "nt" or not self.hwnd:
            return
        nid = self._notify_data(self.NIF_INFO)
        nid.szInfoTitle = title[:63]
        nid.szInfo = message[:255]
        nid.dwInfoFlags = self.NIIF_INFO
        ctypes.windll.shell32.Shell_NotifyIconW(self.NIM_MODIFY, ctypes.byref(nid))

    def _run(self) -> None:
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        kernel32.GetModuleHandleW.restype = HMODULE
        user32.LoadIconW.restype = HICON
        user32.LoadImageW.restype = wintypes.HANDLE
        user32.LoadImageW.argtypes = [
            HINSTANCE,
            wintypes.LPCWSTR,
            wintypes.UINT,
            ctypes.c_int,
            ctypes.c_int,
            wintypes.UINT,
        ]
        user32.CreateWindowExW.restype = wintypes.HWND
        user32.CreateWindowExW.argtypes = [
            wintypes.DWORD,
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            wintypes.DWORD,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            wintypes.HWND,
            HMENU,
            HINSTANCE,
            wintypes.LPVOID,
        ]
        user32.DefWindowProcW.restype = LRESULT
        user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, WPARAM, LPARAM]
        self.hinstance = kernel32.GetModuleHandleW(None)
        if self.icon_path and self.icon_path.exists():
            self.hicon = user32.LoadImageW(
                None,
                str(self.icon_path),
                self.IMAGE_ICON,
                0,
                0,
                self.LR_LOADFROMFILE | self.LR_DEFAULTSIZE,
            )
        if not self.hicon:
            self.hicon = user32.LoadIconW(None, self.IDI_APPLICATION)
        self.wndproc = WNDPROC(self._wndproc)

        wndclass = WNDCLASSW()
        wndclass.lpfnWndProc = self.wndproc
        wndclass.hInstance = self.hinstance
        wndclass.hIcon = self.hicon
        wndclass.lpszClassName = self.class_name
        user32.RegisterClassW(ctypes.byref(wndclass))

        self.hwnd = user32.CreateWindowExW(
            0,
            self.class_name,
            f"{APP_NAME} Tray",
            0,
            0,
            0,
            0,
            0,
            None,
            None,
            self.hinstance,
            None,
        )
        if self.hwnd:
            nid = self._notify_data(self.NIF_MESSAGE | self.NIF_ICON | self.NIF_TIP)
            ctypes.windll.shell32.Shell_NotifyIconW(self.NIM_ADD, ctypes.byref(nid))
        self.ready.set()

        msg = wintypes.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

    def _notify_data(self, flags: int) -> NOTIFYICONDATAW:
        nid = NOTIFYICONDATAW()
        nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        nid.hWnd = self.hwnd
        nid.uID = 1
        nid.uFlags = flags
        nid.uCallbackMessage = WM_TRAYICON
        nid.hIcon = self.hicon
        nid.szTip = APP_NAME
        return nid

    def _wndproc(self, hwnd, msg, wparam, lparam):
        user32 = ctypes.windll.user32
        shell32 = ctypes.windll.shell32
        if msg == WM_TRAYICON:
            if lparam == self.WM_LBUTTONDBLCLK:
                self.on_open()
            elif lparam == self.WM_RBUTTONUP:
                menu = user32.CreatePopupMenu()
                user32.AppendMenuW(menu, self.MF_STRING, TRAY_OPEN_ID, "Open")
                user32.AppendMenuW(menu, self.MF_STRING, TRAY_EXIT_ID, "Exit")
                point = wintypes.POINT()
                user32.GetCursorPos(ctypes.byref(point))
                user32.SetForegroundWindow(hwnd)
                command = user32.TrackPopupMenu(
                    menu,
                    self.TPM_RETURNCMD | self.TPM_NONOTIFY,
                    point.x,
                    point.y,
                    0,
                    hwnd,
                    None,
                )
                user32.DestroyMenu(menu)
                if command == TRAY_OPEN_ID:
                    self.on_open()
                elif command == TRAY_EXIT_ID:
                    self.on_exit()
            return 0
        if msg == self.WM_COMMAND:
            command = wparam & 0xFFFF
            if command == TRAY_OPEN_ID:
                self.on_open()
            elif command == TRAY_EXIT_ID:
                self.on_exit()
            return 0
        if msg == self.WM_CLOSE:
            nid = self._notify_data(0)
            shell32.Shell_NotifyIconW(self.NIM_DELETE, ctypes.byref(nid))
            user32.DestroyWindow(hwnd)
            return 0
        if msg == 0x0002:
            user32.PostQuitMessage(0)
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)


def configure_windows_app_identity() -> None:
    if os.name != "nt":
        return
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(WINDOWS_APP_USER_MODEL_ID)
    except Exception:
        pass


configure_windows_app_identity()


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

    def recv_message(self) -> tuple[str, object] | None:
        while True:
            try:
                first, second = recv_exact(self.sock, 2)
            except OSError:
                return None
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                try:
                    length = struct.unpack("!H", recv_exact(self.sock, 2))[0]
                except OSError:
                    return None
            elif length == 127:
                try:
                    length = struct.unpack("!Q", recv_exact(self.sock, 8))[0]
                except OSError:
                    return None

            try:
                mask = recv_exact(self.sock, 4) if masked else b""
                payload = recv_exact(self.sock, length) if length else b""
            except OSError:
                return None
            if masked:
                payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))

            if opcode == 0x1:
                try:
                    return "text", json.loads(payload.decode("utf-8"))
                except Exception:
                    continue
            if opcode == 0x2:
                try:
                    if len(payload) >= 4:
                        json_len = struct.unpack("!I", payload[:4])[0]
                        if len(payload) >= 4 + json_len:
                            metadata_json = payload[4:4+json_len].decode("utf-8")
                            metadata = json.loads(metadata_json)
                            file_bytes = payload[4+json_len:]
                            return "binary", (metadata, file_bytes)
                except Exception:
                    continue
            if opcode == 0x8:
                self.close()
                return None
            if opcode == 0x9:
                self._send_control(0xA, payload)
                continue
            if opcode == 0xA:
                continue

    def send_binary(self, payload: bytes) -> None:
        header = bytearray([0x82])
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
    notify_sound: bool = True
    notify_popup: bool = False
    notify_popup_seconds: int = 4
    image_save_permanent: bool = True
    image_save_dir: str = ""
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
        self.title(APP_NAME)
        if ICON_PATH.exists():
            self.iconbitmap(default=str(ICON_PATH))
        self.geometry("980x700")
        self.minsize(860, 620)

        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.stop_event = threading.Event()
        self.server_socket: socket.socket | None = None
        self.client_generation = 0
        self.connecting_device_id = ""
        self.pending_requests: list[PendingRequest] = []
        self.discovered: list[dict] = []
        self.history: list[dict] = []
        self.suppressed_remote: dict[str, float] = {}
        self.suppressed_remote_files: dict[str, float] = {}
        self.suppressed_remote_image_hashes: set[str] = set()
        self.last_clipboard_files: list[str] = []
        self.last_image_hash = ""
        self.last_clipboard_sequence = 0
        self.last_clipboard_text = ""
        self.last_sent_text = ""
        self.last_sent_at = 0.0
        self.popup_windows: list[Toplevel] = []
        self.temp_image_dir = Path(tempfile.gettempdir()) / f"lan_clipboard_sync_{uuid.uuid4().hex}"
        self.tray_icon = WindowsTrayIcon(
            on_open=lambda: self.post("tray_open"),
            on_exit=lambda: self.post("tray_exit"),
            icon_path=ICON_PATH,
        )

        self.state = self.load_state()
        self.device_name = StringVar(value=self.state.device_name)
        self.pair_code = StringVar(value=self.state.pair_code)
        self.port = IntVar(value=self.state.port)
        self.manual_host_ip = StringVar(value=self.state.host_ip)
        self.auto_accept = BooleanVar(value=self.state.auto_accept)
        self.auto_copy = BooleanVar(value=self.state.auto_copy)
        self.monitor = BooleanVar(value=self.state.monitor)
        self.notify_sound = BooleanVar(value=self.state.notify_sound)
        self.notify_popup = BooleanVar(value=self.state.notify_popup)
        self.notify_popup_seconds = IntVar(value=self.state.notify_popup_seconds)
        self.image_save_permanent = BooleanVar(value=self.state.image_save_permanent)
        self.image_save_dir = StringVar(value=self.state.image_save_dir)
        self.status = StringVar(value="Starting...")
        self.local_ip = StringVar(value=local_ipv4() or "unknown")

        self.create_widgets()
        self.tray_icon.start()
        self.protocol("WM_DELETE_WINDOW", self.hide_to_tray)
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
        try:
            popup_seconds = int(data.get("notify_popup_seconds") or 4)
        except (TypeError, ValueError):
            popup_seconds = 4
        return AppState(
            device_id=data.get("device_id") or str(uuid.uuid4()),
            device_name=data.get("device_name") or socket.gethostname() or "Windows PC",
            pair_code=str(data.get("pair_code") or new_pair_code())[:6],
            host_ip=str(data.get("host_ip") or ""),
            port=int(data.get("port") or DEFAULT_PORT),
            auto_accept=bool(data.get("auto_accept", False)),
            auto_copy=bool(data.get("auto_copy", True)),
            monitor=bool(data.get("monitor", True)),
            notify_sound=bool(data.get("notify_sound", True)),
            notify_popup=bool(data.get("notify_popup", False)),
            notify_popup_seconds=max(1, min(60, popup_seconds)),
            image_save_permanent=bool(data.get("image_save_permanent", True)),
            image_save_dir=str(data.get("image_save_dir") or DEFAULT_IMAGE_DIR),
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
            "notify_sound": self.state.notify_sound,
            "notify_popup": self.state.notify_popup,
            "notify_popup_seconds": self.state.notify_popup_seconds,
            "image_save_permanent": self.state.image_save_permanent,
            "image_save_dir": self.state.image_save_dir,
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
        style.configure("Popup.TFrame", background="#ffffff", relief="flat")
        style.configure("PopupTitle.TLabel", background="#ffffff", foreground="#111827", font=("Segoe UI", 10, "bold"))
        style.configure("PopupBody.TLabel", background="#ffffff", foreground="#374151", font=("Segoe UI", 9))
        style.configure("TButton", font=("Segoe UI", 9), padding=(10, 5))
        style.configure("Accent.TButton", font=("Segoe UI", 9, "bold"), padding=(12, 6))
        style.configure("TCheckbutton", background="#f5f7fb", foreground="#374151", font=("Segoe UI", 9))
        style.configure("Card.TCheckbutton", background="#ffffff", foreground="#374151", font=("Segoe UI", 9))
        style.configure("TNotebook", background="#f5f7fb", borderwidth=0)
        style.configure("TNotebook.Tab", font=("Segoe UI", 9, "bold"), padding=(16, 8))

        self.columnconfigure(0, weight=1)
        self.rowconfigure(1, weight=1)

        header = ttk.Frame(self, padding=(16, 14, 16, 8))
        header.grid(row=0, column=0, sticky="ew")
        header.columnconfigure(1, weight=1)
        ttk.Label(header, text="LAN Clipboard Sync", style="Header.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(header, textvariable=self.status, style="Status.TLabel").grid(row=0, column=1, sticky="e")
        ttk.Label(header, text="Local IP").grid(row=1, column=0, sticky="w", pady=(4, 0))
        ttk.Label(header, textvariable=self.local_ip).grid(row=1, column=1, sticky="e", pady=(4, 0))

        notebook = ttk.Notebook(self)
        notebook.grid(row=1, column=0, sticky="nsew", padx=16, pady=(0, 12))

        main_tab = ttk.Frame(notebook, padding=(0, 12, 0, 0))
        settings_tab = ttk.Frame(notebook, padding=(0, 12, 0, 0))
        notebook.add(main_tab, text="Sync")
        notebook.add(settings_tab, text="Settings")

        main_tab.columnconfigure(0, weight=1)
        main_tab.rowconfigure(2, weight=1)
        settings_tab.columnconfigure(0, weight=1)

        controls = ttk.Frame(main_tab, padding=(0, 0, 0, 12))
        controls.grid(row=0, column=0, sticky="ew")
        controls.columnconfigure(0, weight=1)

        identity = ttk.Frame(controls, padding=12, style="Card.TFrame")
        identity.grid(row=0, column=0, sticky="ew")
        identity.columnconfigure(1, weight=1)
        ttk.Label(identity, text="This PC", style="Section.TLabel").grid(row=0, column=0, columnspan=7, sticky="w", pady=(0, 10))
        ttk.Label(identity, text="Name", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        ttk.Entry(identity, textvariable=self.device_name).grid(row=1, column=1, sticky="ew", padx=(8, 12))
        ttk.Label(identity, text="Room", style="Card.TLabel").grid(row=1, column=2, sticky="w")
        ttk.Entry(identity, textvariable=self.pair_code, width=9, justify="center").grid(row=1, column=3, sticky="w", padx=(8, 6))
        ttk.Button(identity, text="New", command=self.refresh_pair_code).grid(row=1, column=4, sticky="w")
        ttk.Label(identity, text="Port", style="Card.TLabel").grid(row=1, column=5, sticky="w", padx=(12, 0))
        ttk.Spinbox(identity, from_=DEFAULT_PORT, to=DEFAULT_PORT, textvariable=self.port, width=7, state="readonly").grid(row=1, column=6, sticky="w", padx=(8, 0))

        settings = ttk.Frame(settings_tab, padding=12, style="Card.TFrame")
        settings.grid(row=0, column=0, sticky="ew")
        settings.columnconfigure(0, weight=1)
        ttk.Label(settings, text="Clipboard", style="Section.TLabel").pack(anchor="w", pady=(0, 10))
        clipboard_options = ttk.Frame(settings, style="Card.TFrame")
        clipboard_options.pack(anchor="w", fill="x")
        ttk.Checkbutton(clipboard_options, text="Monitor", variable=self.monitor, command=self.save_config, style="Card.TCheckbutton").pack(side="left")
        ttk.Checkbutton(clipboard_options, text="Auto-copy received", variable=self.auto_copy, command=self.save_config, style="Card.TCheckbutton").pack(side="left", padx=14)
        ttk.Label(settings, text="Notifications", style="Section.TLabel").pack(anchor="w", pady=(12, 8))
        notification_options = ttk.Frame(settings, style="Card.TFrame")
        notification_options.pack(anchor="w", fill="x")
        ttk.Checkbutton(notification_options, text="Ting on receive", variable=self.notify_sound, command=self.save_config, style="Card.TCheckbutton").pack(side="left")
        ttk.Checkbutton(notification_options, text="Windows popup", variable=self.notify_popup, command=self.save_config, style="Card.TCheckbutton").pack(side="left", padx=14)
        ttk.Spinbox(
            notification_options,
            from_=1,
            to=60,
            textvariable=self.notify_popup_seconds,
            width=4,
            command=self.save_config,
        ).pack(side="left")
        ttk.Label(notification_options, text="sec", style="Card.TLabel").pack(side="left", padx=(4, 0))
        ttk.Label(settings, text="Images", style="Section.TLabel").pack(anchor="w", pady=(12, 8))
        ttk.Checkbutton(
            settings,
            text="Save images to folder",
            variable=self.image_save_permanent,
            command=self.on_image_save_permanent_changed,
            style="Card.TCheckbutton",
        ).pack(anchor="w")
        image_options = ttk.Frame(settings, style="Card.TFrame")
        image_options.pack(anchor="w", fill="x", pady=(8, 0))
        image_options.columnconfigure(0, weight=1)
        self.image_dir_entry = ttk.Entry(image_options, textvariable=self.image_save_dir)
        self.image_dir_entry.grid(row=0, column=0, sticky="ew")
        self.image_browse_button = ttk.Button(image_options, text="Browse", command=self.choose_image_save_dir)
        self.image_browse_button.grid(row=0, column=1, sticky="e", padx=(8, 0))
        self.image_open_button = ttk.Button(image_options, text="Open", command=self.open_image_save_dir)
        self.image_open_button.grid(row=0, column=2, sticky="e", padx=(8, 0))
        self.update_image_save_controls()

        connection_settings = ttk.Frame(settings_tab, padding=12, style="Card.TFrame")
        connection_settings.grid(row=1, column=0, sticky="ew", pady=(12, 0))
        ttk.Label(connection_settings, text="Connection", style="Section.TLabel").pack(anchor="w", pady=(0, 10))
        ttk.Checkbutton(
            connection_settings,
            text="Auto-accept pairing requests",
            variable=self.auto_accept,
            command=self.save_config,
            style="Card.TCheckbutton",
        ).pack(anchor="w")

        actions = ttk.Frame(main_tab, padding=12, style="Card.TFrame")
        actions.grid(row=1, column=0, sticky="ew", pady=(0, 12))
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

        panes = ttk.Panedwindow(main_tab, orient="horizontal")
        panes.grid(row=2, column=0, sticky="nsew", pady=(0, 12))

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

        log_frame = ttk.Frame(main_tab, padding=(0, 0, 0, 0))
        log_frame.grid(row=3, column=0, sticky="ew")
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
        self.state.notify_sound = self.notify_sound.get()
        self.state.notify_popup = self.notify_popup.get()
        try:
            popup_seconds = int(self.notify_popup_seconds.get())
        except TclError:
            popup_seconds = 4
        popup_seconds = max(1, min(60, popup_seconds))
        self.state.notify_popup_seconds = popup_seconds
        self.notify_popup_seconds.set(popup_seconds)
        self.state.image_save_permanent = self.image_save_permanent.get()
        image_dir = self.image_save_dir.get().strip() or str(DEFAULT_IMAGE_DIR)
        self.state.image_save_dir = str(self.resolve_image_save_dir(image_dir))
        self.image_save_dir.set(self.state.image_save_dir)

    def on_image_save_permanent_changed(self) -> None:
        self.update_image_save_controls()
        self.save_config()
        self.set_status(
            "Images will be saved to the selected folder"
            if self.image_save_permanent.get()
            else "Images will be kept temporarily until exit"
        )

    def update_image_save_controls(self) -> None:
        state = "normal" if self.image_save_permanent.get() else "disabled"
        for widget in (
            getattr(self, "image_dir_entry", None),
            getattr(self, "image_browse_button", None),
            getattr(self, "image_open_button", None),
        ):
            if widget is not None:
                widget.configure(state=state)

    def refresh_pair_code(self) -> None:
        self.pair_code.set(new_pair_code())
        self.save_config()
        self.set_status("Room code changed")

    def choose_image_save_dir(self) -> None:
        if not self.image_save_permanent.get():
            return
        current_dir = self.resolve_image_save_dir(self.image_save_dir.get())
        selected = filedialog.askdirectory(
            parent=self,
            initialdir=str(current_dir if current_dir.exists() else APP_DIR),
            title="Choose image folder",
        )
        if not selected:
            return
        self.image_save_dir.set(str(Path(selected)))
        self.save_config()
        self.set_status("Image folder updated")

    def open_image_save_dir(self) -> None:
        if not self.image_save_permanent.get():
            self.set_status("Temporary image folder is used until exit")
            return
        image_dir = self.ensure_image_save_dir()
        try:
            os.startfile(image_dir)
        except OSError as exc:
            self.set_status(f"Could not open image folder: {exc}")

    def resolve_image_save_dir(self, value: str = "") -> Path:
        raw = (value or "").strip()
        path = Path(raw).expanduser() if raw else DEFAULT_IMAGE_DIR
        if not path.is_absolute():
            path = APP_DIR / path
        return path.resolve()

    def ensure_image_save_dir(self) -> str:
        image_dir = self.resolve_image_save_dir(self.image_save_dir.get())
        image_dir.mkdir(parents=True, exist_ok=True)
        return str(image_dir)

    def ensure_temp_image_dir(self) -> str:
        self.temp_image_dir.mkdir(parents=True, exist_ok=True)
        return str(self.temp_image_dir)

    def save_image_bytes(self, file_name: str, file_bytes: bytes) -> str:
        save_dir = Path(
            self.ensure_image_save_dir()
            if self.image_save_permanent.get()
            else self.ensure_temp_image_dir()
        )
        safe_name = self.sanitize_file_name(file_name)
        file_path = save_dir / safe_name
        if file_path.exists():
            stem = file_path.stem
            suffix = file_path.suffix
            counter = 1
            while file_path.exists():
                file_path = save_dir / f"{stem}_{counter}{suffix}"
                counter += 1
        file_path.write_bytes(file_bytes)
        return str(file_path)

    def sanitize_file_name(self, file_name: str) -> str:
        safe_name = os.path.basename(file_name).strip()
        safe_name = "".join("_" if ch in '<>:"/\\|?*' or ord(ch) < 32 else ch for ch in safe_name)
        return safe_name or f"ClipboardImage_{time.strftime('%Y%m%d_%H%M%S')}.png"

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
            elif event == "remote_binary_clip":
                metadata, file_bytes = payload
                self.receive_remote_binary_clip(metadata, file_bytes)
            elif event == "client_closed":
                generation, reason = payload
                self.handle_client_closed(int(generation), str(reason))
            elif event == "local_ip":
                self.local_ip.set(str(payload) or "unknown")
            elif event == "log":
                self.add_log(str(payload))
            elif event == "tray_open":
                self.show_from_tray()
            elif event == "tray_exit":
                self.close_app()
            elif event == "remove_pending_connection":
                self.remove_pending_connection(payload)
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
                res = conn.recv_message()
                if res is None:
                    break
                msg_type, payload = res
                if msg_type == "text":
                    message = payload
                    kind = message.get("kind")
                    self.post("log", f"Received {kind or 'unknown'} from {address[0]}")
                    if kind == "hello":
                        self.handle_hello(conn, message, address)
                    elif kind == "clip":
                        self.handle_remote_clip_from_peer(conn, message.get("payload") or {})
                    elif kind == "invite":
                        self.handle_invite(message)
                        break
                elif msg_type == "binary":
                    metadata, file_bytes = payload
                    self.post("log", f"Received binary clip {metadata.get('fileName')} from {address[0]}")
                    self.handle_remote_binary_clip_from_peer(conn, metadata, file_bytes)
        except (OSError, ConnectionError, json.JSONDecodeError) as exc:
            self.post("status", f"Connection error from {address[0]}: {exc}")
        finally:
            if conn:
                self.post("remove_pending_connection", conn)
                self.remove_peer(conn)

    def handle_remote_binary_clip_from_peer(self, conn: WebSocketConnection, metadata: dict, file_bytes: bytes) -> None:
        if conn not in self.state.peers:
            conn.close()
            return
        if not str(metadata.get("mimeType") or "").startswith("image/"):
            return
        self.post("remote_binary_clip", (metadata, file_bytes))
        
        json_bytes = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        payload = struct.pack("!I", len(json_bytes)) + json_bytes + file_bytes
        for peer_conn in list(self.state.peers.keys()):
            if peer_conn is conn:
                continue
            peer = self.state.peers.get(peer_conn)
            if peer and peer.device_id == metadata.get("sourceDeviceId"):
                continue
            try:
                peer_conn.send_binary(payload)
            except OSError:
                self.remove_peer(peer_conn)

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
        if self.is_simultaneous_connection(peer.device_id):
            if self.should_accept_simultaneous_connection(peer.device_id):
                self.cancel_outbound_for_inbound_connection(peer.device_id)
                self.accept_peer(conn, peer, auto=True)
            else:
                conn.send_json({"kind": "join_response", "accepted": False, "reason": "Using outbound connection"})
                conn.close()
            return

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

    def is_simultaneous_connection(self, device_id: str) -> bool:
        return bool(
            device_id
            and self.connecting_device_id == device_id
            and not self.state.running
        )

    def should_accept_simultaneous_connection(self, device_id: str) -> bool:
        return bool(device_id and self.state.device_id > device_id)

    def cancel_outbound_for_inbound_connection(self, device_id: str) -> None:
        self.client_generation += 1
        self.connecting_device_id = ""
        if self.state.client:
            self.state.client.close()
            self.state.client = None
        self.state.role = "HOST"
        self.state.running = False
        self.state.group_device_id = None
        self.state.group_members = []
        self.post("status", f"Resolving simultaneous connection with {device_id}")

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
        self.client_generation += 1
        generation = self.client_generation
        self.disconnect(pending_reason="Connected to another device")
        self.connecting_device_id = group_device_id or ""
        self.set_status(f"Connecting to {host_ip}:{port}")
        threading.Thread(
            target=self.client_loop,
            args=(generation, host_ip, port, room_code, group_device_id),
            daemon=True,
        ).start()

    def client_loop(self, generation: int, host_ip: str, port: int, room_code: str, group_device_id: str | None) -> None:
        conn = None
        final_reason = "Disconnected"
        try:
            conn = websocket_client_connect(host_ip, port)
            if generation != self.client_generation:
                final_reason = "Connection superseded"
                return
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
                if generation != self.client_generation:
                    final_reason = "Connection superseded"
                    break
                res = conn.recv_message()
                if res is None:
                    break
                msg_type, payload = res
                if msg_type == "text":
                    message = payload
                    kind = message.get("kind")
                    if kind == "join_response":
                        if message.get("accepted"):
                            self.state.role = "CLIENT"
                            self.state.running = True
                            self.connecting_device_id = ""
                            host_device_id = message.get("hostDeviceId")
                            if host_device_id:
                                self.state.group_device_id = host_device_id
                            self.update_group_members(message.get("peers") or [])
                            self.post("status", f"Connected to {message.get('hostDeviceName') or 'host'}")
                            self.post("peers")
                        else:
                            final_reason = message.get("reason") or "Connection rejected"
                            self.connecting_device_id = ""
                            self.post("status", final_reason)
                            break
                    elif kind == "peers":
                        self.update_group_members(message.get("peers") or [])
                        self.post("peers")
                    elif kind == "clip":
                        self.post("remote_clip", message.get("payload") or {})
                elif msg_type == "binary":
                    metadata, file_bytes = payload
                    self.post("remote_binary_clip", (metadata, file_bytes))
        except (OSError, ConnectionError, json.JSONDecodeError) as exc:
            final_reason = f"Connection failed: {exc}"
            self.post("status", final_reason)
        finally:
            if conn:
                conn.close()
            self.post("client_closed", (generation, final_reason))

    def update_group_members(self, wires: list[dict]) -> None:
        members = []
        for wire in wires:
            device_id = str(wire.get("deviceId") or "")
            device_name = str(wire.get("deviceName") or "Unknown")
            if device_id != self.state.device_id:
                members.append(Peer(device_id, device_name))
        self.state.group_members = members

    def handle_client_closed(self, generation: int, reason: str) -> None:
        if generation != self.client_generation:
            return
        if self.state.client:
            self.state.client = None
        self.connecting_device_id = ""
        if self.state.role == "CLIENT":
            self.state.running = False
            self.state.role = "HOST"
            self.state.group_members = []
            self.state.group_device_id = None
            self.refresh_peers()
            self.set_status(reason)
        elif not self.state.running:
            self.set_status(reason)

    def add_pending(self, request: PendingRequest) -> None:
        replaced = [
            existing for existing in self.pending_requests
            if existing.device_id == request.device_id and existing.is_invitation == request.is_invitation
        ]
        for existing in replaced:
            self.close_pending_request(existing, "Connection request replaced")
        self.pending_requests = [
            existing for existing in self.pending_requests
            if not (existing.device_id == request.device_id and existing.is_invitation == request.is_invitation)
        ]
        self.pending_requests.append(request)
        self.refresh_pending()

    def remove_pending_connection(self, conn: WebSocketConnection) -> None:
        before = len(self.pending_requests)
        self.pending_requests = [request for request in self.pending_requests if request.connection is not conn]
        if len(self.pending_requests) != before:
            self.refresh_pending()

    def close_pending_request(self, request: PendingRequest, reason: str) -> None:
        if not request.connection:
            return
        try:
            request.connection.send_json({"kind": "join_response", "accepted": False, "reason": reason})
        except OSError:
            pass
        finally:
            request.connection.close()

    def cancel_pending_requests(self, reason: str) -> None:
        pending = self.pending_requests
        self.pending_requests = []
        for request in pending:
            self.close_pending_request(request, reason)
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
            self.close_pending_request(request, "Connection rejected")
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
            try:
                clipboard_sequence = get_clipboard_sequence_number()
                if clipboard_sequence and clipboard_sequence == self.last_clipboard_sequence:
                    if not self.stop_event.is_set():
                        self.after(700, self.poll_clipboard)
                    return

                handled = False
                if is_format_available(CF_HDROP):
                    files = self.read_clipboard_files()
                    if files:
                        self.last_clipboard_files = files
                        if clipboard_sequence:
                            self.last_clipboard_sequence = clipboard_sequence
                        handled = True

                if not handled and is_image_format_available():
                    image = self.get_clipboard_image_bytes()
                    if image:
                        img_bytes, mime_type, extension = image
                        img_hash = hashlib.md5(img_bytes).hexdigest()
                        if img_hash != self.last_image_hash:
                            self.publish_image_clipboard(
                                img_bytes,
                                automatic=True,
                                mime_type=mime_type,
                                extension=extension,
                            )
                            self.last_image_hash = img_hash
                        if clipboard_sequence:
                            self.last_clipboard_sequence = clipboard_sequence
                        handled = True

                if not handled:
                    text = self.read_clipboard()
                    if text:
                        if text != self.last_clipboard_text:
                            self.last_clipboard_text = text
                            self.publish_clipboard(text, automatic=True)
                        if clipboard_sequence:
                            self.last_clipboard_sequence = clipboard_sequence
            except Exception as exc:
                self.add_log(f"Clipboard poll error: {exc}")
        if not self.stop_event.is_set():
            self.after(700, self.poll_clipboard)

    def read_clipboard_files(self) -> list[str]:
        if not is_format_available(CF_HDROP):
            return []
        try:
            OpenClipboard = ctypes.windll.user32.OpenClipboard
            GetClipboardData = ctypes.windll.user32.GetClipboardData
            CloseClipboard = ctypes.windll.user32.CloseClipboard
            DragQueryFileW = ctypes.windll.shell32.DragQueryFileW
            GetClipboardData.restype = ctypes.c_void_p
            DragQueryFileW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_wchar_p, ctypes.c_uint]
            DragQueryFileW.restype = ctypes.c_uint
            
            files = []
            if OpenClipboard(None):
                try:
                    h_drop = GetClipboardData(CF_HDROP)
                    if h_drop:
                        count = DragQueryFileW(h_drop, -1, None, 0)
                        for i in range(count):
                            length = DragQueryFileW(h_drop, i, None, 0)
                            buf = ctypes.create_unicode_buffer(length + 1)
                            DragQueryFileW(h_drop, i, buf, length + 1)
                            files.append(buf.value)
                finally:
                    CloseClipboard()
            return files
        except Exception:
            return []

    def get_clipboard_image_hash(self) -> str | None:
        if not is_format_available(CF_DIB):
            return None
        try:
            OpenClipboard = ctypes.windll.user32.OpenClipboard
            GetClipboardData = ctypes.windll.user32.GetClipboardData
            CloseClipboard = ctypes.windll.user32.CloseClipboard
            GlobalLock = ctypes.windll.kernel32.GlobalLock
            GlobalUnlock = ctypes.windll.kernel32.GlobalUnlock
            GlobalSize = ctypes.windll.kernel32.GlobalSize
            GetClipboardData.restype = ctypes.c_void_p
            GlobalLock.argtypes = [ctypes.c_void_p]
            GlobalLock.restype = ctypes.c_void_p
            GlobalUnlock.argtypes = [ctypes.c_void_p]
            GlobalUnlock.restype = ctypes.c_int
            GlobalSize.argtypes = [ctypes.c_void_p]
            GlobalSize.restype = ctypes.c_size_t
            
            if OpenClipboard(None):
                try:
                    h_data = GetClipboardData(CF_DIB)
                    if h_data:
                        size = GlobalSize(h_data)
                        ptr = GlobalLock(h_data)
                        if ptr:
                            try:
                                buffer = ctypes.string_at(ptr, size)
                                return hashlib.md5(buffer).hexdigest()
                            finally:
                                GlobalUnlock(h_data)
                finally:
                    CloseClipboard()
        except Exception:
            pass
        return None

    def get_clipboard_dib_image_bytes(self) -> bytes | None:
        for fmt in (CF_DIBV5, CF_DIB):
            if not is_format_available(fmt):
                continue
            try:
                OpenClipboard = ctypes.windll.user32.OpenClipboard
                GetClipboardData = ctypes.windll.user32.GetClipboardData
                CloseClipboard = ctypes.windll.user32.CloseClipboard
                GlobalLock = ctypes.windll.kernel32.GlobalLock
                GlobalUnlock = ctypes.windll.kernel32.GlobalUnlock
                GlobalSize = ctypes.windll.kernel32.GlobalSize
                GetClipboardData.restype = ctypes.c_void_p
                GlobalLock.argtypes = [ctypes.c_void_p]
                GlobalLock.restype = ctypes.c_void_p
                GlobalUnlock.argtypes = [ctypes.c_void_p]
                GlobalUnlock.restype = ctypes.c_int
                GlobalSize.argtypes = [ctypes.c_void_p]
                GlobalSize.restype = ctypes.c_size_t

                if OpenClipboard(None):
                    try:
                        h_data = GetClipboardData(fmt)
                        if h_data:
                            size = GlobalSize(h_data)
                            ptr = GlobalLock(h_data)
                            if ptr:
                                try:
                                    dib = ctypes.string_at(ptr, size)
                                    return dib_to_bmp(dib)
                                finally:
                                    GlobalUnlock(h_data)
                    finally:
                        CloseClipboard()
            except Exception as exc:
                self.add_log(f"Error reading clipboard bitmap: {exc}")
        return None

    def get_clipboard_image_bytes(self, allow_bmp_fallback: bool = False) -> tuple[bytes, str, str] | None:
        temp_path = os.path.join(
            os.path.expanduser("~"),
            "AppData",
            "Local",
            "Temp",
            f"clip_image_sync_{uuid.uuid4().hex}.png",
        )
        escaped_path = temp_path.replace("'", "''")
        cmd = (
            "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
            "$img = [System.Windows.Forms.Clipboard]::GetImage(); "
            f"if ($img) {{ $img.Save('{escaped_path}', [System.Drawing.Imaging.ImageFormat]::Png); $img.Dispose() }}"
        )
        try:
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-Sta", "-Command", cmd],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if result.returncode != 0:
                error = (result.stderr or result.stdout or "").strip()
                self.add_log(f"Error reading clipboard image: {error or 'PowerShell failed'}")
                return None
            if os.path.exists(temp_path):
                with open(temp_path, "rb") as f:
                    data = f.read()
                os.remove(temp_path)
                return data, "image/png", "png"
            self.add_log("Clipboard image was detected, but Windows did not return image bytes")
        except subprocess.TimeoutExpired:
            self.add_log("Error reading clipboard image: PowerShell timed out")
        except Exception as exc:
            self.add_log(f"Error reading clipboard image: {exc}")
        finally:
            try:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
            except OSError:
                pass
        if allow_bmp_fallback:
            bmp_bytes = self.get_clipboard_dib_image_bytes()
            if bmp_bytes:
                return bmp_bytes, "image/bmp", "bmp"
        return None

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
        clipboard_sequence = get_clipboard_sequence_number()
        if is_format_available(CF_HDROP):
            files = self.read_clipboard_files()
            if files:
                self.last_clipboard_files = files
                if clipboard_sequence:
                    self.last_clipboard_sequence = clipboard_sequence
                self.set_status("Only text and images are supported")
                return

        if is_image_format_available():
            image = self.get_clipboard_image_bytes()
            if image:
                img_bytes, mime_type, extension = image
                self.publish_image_clipboard(
                    img_bytes,
                    automatic=False,
                    mime_type=mime_type,
                    extension=extension,
                )
                self.last_image_hash = hashlib.md5(img_bytes).hexdigest()
                if clipboard_sequence:
                    self.last_clipboard_sequence = clipboard_sequence
                return

        text = self.read_clipboard()
        if not text.strip():
            self.set_status("Clipboard is empty")
            return
        self.publish_clipboard(text, automatic=False)
        self.last_clipboard_text = text
        if clipboard_sequence:
            self.last_clipboard_sequence = clipboard_sequence

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

    def publish_file_clipboard(self, file_path: str, automatic: bool) -> None:
        if not os.path.exists(file_path):
            return
        abs_path = os.path.abspath(file_path)
        current_time = time.time()
        self.prune_suppressions(current_time)
        if abs_path in self.suppressed_remote_files:
            return
        
        file_name = os.path.basename(abs_path)
        try:
            file_size = os.path.getsize(abs_path)
            if file_size > 35 * 1024 * 1024:
                self.set_status(f"File too large to sync (>35MB): {file_name}")
                return
        except Exception:
            return
        
        try:
            with open(abs_path, "rb") as f:
                file_bytes = f.read()
        except OSError as exc:
            self.add_log(f"Error reading file: {exc}")
            return
        mime_type, _ = mimetypes.guess_type(abs_path)
        if not mime_type:
            mime_type = "application/octet-stream"
        if not mime_type.startswith("image/"):
            if not automatic:
                self.set_status("Only text and images are supported")
            return

        metadata = {
            "clipId": str(uuid.uuid4()),
            "sourceDeviceId": self.state.device_id,
            "sourceDeviceName": self.state.device_name,
            "fileName": file_name,
            "mimeType": mime_type,
            "timestamp": now_ms(),
        }
        
        delivered = False
        json_bytes = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        payload = struct.pack("!I", len(json_bytes)) + json_bytes + file_bytes
        
        if self.state.role == "HOST" and self.state.peers:
            for conn in list(self.state.peers.keys()):
                try:
                    conn.send_binary(payload)
                    delivered = True
                except OSError:
                    self.remove_peer(conn)
        elif self.state.role == "CLIENT" and self.state.client:
            try:
                self.state.client.send_binary(payload)
                delivered = True
            except OSError:
                delivered = False
                
        if delivered:
            preview_text = f"[Image] {file_name}"
            self.add_history("Sent", self.state.device_name, preview_text, clip_type="image", file_path=abs_path)
            self.set_status("Image sent")
        elif not automatic:
            self.set_status("No connected devices to receive image")

    def publish_image_clipboard(
        self,
        img_bytes: bytes,
        automatic: bool,
        mime_type: str = "image/png",
        extension: str = "png",
    ) -> None:
        img_hash = hashlib.md5(img_bytes).hexdigest()
        if img_hash in self.suppressed_remote_image_hashes:
            return
            
        file_name = f"ClipboardImage_{time.strftime('%Y%m%d_%H%M%S')}.{extension}"
        metadata = {
            "clipId": str(uuid.uuid4()),
            "sourceDeviceId": self.state.device_id,
            "sourceDeviceName": self.state.device_name,
            "fileName": file_name,
            "mimeType": mime_type,
            "timestamp": now_ms(),
        }
        
        delivered = False
        json_bytes = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        payload = struct.pack("!I", len(json_bytes)) + json_bytes + img_bytes
        
        if self.state.role == "HOST" and self.state.peers:
            for conn in list(self.state.peers.keys()):
                try:
                    conn.send_binary(payload)
                    delivered = True
                except OSError:
                    self.remove_peer(conn)
        elif self.state.role == "CLIENT" and self.state.client:
            try:
                self.state.client.send_binary(payload)
                delivered = True
            except OSError:
                delivered = False
                
        if delivered:
            try:
                file_path = self.save_image_bytes(file_name, img_bytes)
            except OSError as exc:
                self.set_status(f"Could not save sent image: {exc}")
                return
                
            preview_text = f"[Image] {file_name}"
            self.add_history("Sent", self.state.device_name, preview_text, clip_type="image", file_path=file_path)
            self.set_status("Image sent")
        elif not automatic:
            self.set_status("No connected devices to receive image")

    def handle_remote_clip_from_peer(self, conn: WebSocketConnection, payload: dict) -> None:
        if conn not in self.state.peers:
            conn.close()
            return
        self.post("remote_clip", payload)
        message = {"kind": "clip", "payload": payload}
        for peer_conn in list(self.state.peers.keys()):
            if peer_conn is conn:
                continue
            peer = self.state.peers.get(peer_conn)
            if peer and peer.device_id == payload.get("sourceDeviceId"):
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
        preview = text.replace("\n", " ")
        self.notify_received(APP_NAME, f"Received text from {source}: {preview[:120]}")
        if self.auto_copy.get():
            self.suppressed_remote[text] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
            self.write_clipboard(text)
            self.last_clipboard_text = text

    def receive_remote_binary_clip(self, metadata: dict, file_bytes: bytes) -> None:
        if metadata.get("sourceDeviceId") == self.state.device_id:
            return
        file_name = str(metadata.get("fileName") or "file")
        mime_type = str(metadata.get("mimeType") or "")
        source = str(metadata.get("sourceDeviceName") or "Remote")
        if not mime_type.startswith("image/"):
            return
        
        try:
            file_path = self.save_image_bytes(file_name, file_bytes)
        except OSError as exc:
            self.set_status(f"Could not save received file: {exc}")
            return

        is_image = mime_type.startswith("image/")
        preview_text = f"[Image] {file_name}"
        self.add_history("Received", source, preview_text, clip_type="image", file_path=file_path)
        self.set_status(f"Received {file_name} from {source}")
        self.notify_received(APP_NAME, f"Received image from {source}: {file_name}")

        if self.auto_copy.get():
            self.suppressed_remote_files[file_path] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
            img_hash = hashlib.md5(file_bytes).hexdigest()
            self.suppressed_remote_image_hashes.add(img_hash)
            self.last_image_hash = img_hash
            self.copy_image_to_windows_clipboard(file_path)
            
            self.last_clipboard_files = [file_path]

    def copy_image_to_windows_clipboard(self, image_path: str) -> None:
        abs_path = os.path.abspath(image_path)
        escaped_path = abs_path.replace("'", "''")
        cmd = (
            f"Add-Type -AssemblyName System.Windows.Forms, System.Drawing; "
            f"$img = [System.Drawing.Image]::FromFile('{escaped_path}'); "
            f"[System.Windows.Forms.Clipboard]::SetImage($img); "
            f"$img.Dispose()"
        )
        try:
            subprocess.run(["powershell.exe", "-NoProfile", "-Command", cmd], shell=True, capture_output=True)
        except Exception as exc:
            self.add_log(f"PowerShell error copying image: {exc}")

    def prune_suppressions(self, current_time: float) -> None:
        expired = [text for text, expires_at in self.suppressed_remote.items() if expires_at <= current_time]
        for text in expired:
            del self.suppressed_remote[text]
        expired_files = [path for path, expires_at in self.suppressed_remote_files.items() if expires_at <= current_time]
        for path in expired_files:
            del self.suppressed_remote_files[path]

    def add_history(self, direction: str, source: str, text: str, clip_type: str = "text", file_path: str = "") -> None:
        preview = text.replace("\n", " ")
        if len(preview) > 90:
            preview = preview[:87] + "..."
        self.history.insert(0, {
            "direction": direction,
            "source": source,
            "text": text,
            "preview": preview,
            "type": clip_type,
            "path": file_path
        })
        self.history = self.history[:MAX_HISTORY]
        self.history_list.delete(0, END)
        for entry in self.history:
            self.history_list.insert(END, f"{entry['direction']} - {entry['source']}: {entry['preview']}")

    def copy_selected_history(self, event=None) -> None:
        selection = self.history_list.curselection()
        if not selection:
            return
        item = self.history[selection[0]]
        if item["type"] == "text":
            text = item["text"]
            self.suppressed_remote[text] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
            self.write_clipboard(text)
            self.last_clipboard_text = text
        elif item["type"] == "image":
            path = item["path"]
            if os.path.exists(path):
                self.suppressed_remote_files[path] = time.time() + REMOTE_CLIPBOARD_SUPPRESSION_SECONDS
                self.copy_image_to_windows_clipboard(path)
                self.last_clipboard_files = [path]
        self.set_status("Copied history item")

    def send_selected_history(self) -> None:
        selection = self.history_list.curselection()
        if not selection:
            self.set_status("Select a history item first")
            return
        item = self.history[selection[0]]
        if item["type"] == "text":
            self.publish_clipboard(item["text"], automatic=False)
        elif item["type"] == "image":
            path = item["path"]
            if os.path.exists(path):
                self.publish_file_clipboard(path, automatic=False)
            else:
                self.set_status("File no longer exists")

    def disconnect(self, close_server_peers: bool = True, pending_reason: str = "Disconnected") -> None:
        self.connecting_device_id = ""
        if self.state.client:
            self.state.client.close()
            self.state.client = None
        self.cancel_pending_requests(pending_reason)
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

    def hide_to_tray(self) -> None:
        self.withdraw()
        self.set_status("Running in system tray")

    def show_from_tray(self) -> None:
        self.deiconify()
        self.lift()
        self.focus_force()

    def notify_received(self, title: str, message: str) -> None:
        if self.notify_sound.get():
            try:
                winsound.MessageBeep(winsound.MB_ICONASTERISK)
            except RuntimeError:
                pass
        if self.notify_popup.get():
            self.show_popup_notification(title, message)

    def show_popup_notification(self, title: str, message: str) -> None:
        popup = Toplevel(self)
        popup.title(APP_NAME)
        popup.transient(self)
        popup.withdraw()
        popup.overrideredirect(True)
        popup.attributes("-topmost", True)
        if ICON_PATH.exists():
            try:
                popup.iconbitmap(default=str(ICON_PATH))
            except TclError:
                pass

        frame = ttk.Frame(popup, padding=(14, 12), style="Popup.TFrame")
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text=title, style="PopupTitle.TLabel").pack(anchor="w")
        ttk.Label(
            frame,
            text=message,
            style="PopupBody.TLabel",
            wraplength=300,
            justify="left",
        ).pack(anchor="w", pady=(4, 0))

        popup.update_idletasks()
        width = max(320, popup.winfo_reqwidth())
        height = popup.winfo_reqheight()
        self.popup_windows = [item for item in self.popup_windows if item.winfo_exists()]
        offset = len(self.popup_windows) * (height + 10)
        x = popup.winfo_screenwidth() - width - 18
        y = popup.winfo_screenheight() - height - 54 - offset
        popup.geometry(f"{width}x{height}+{max(0, x)}+{max(0, y)}")
        popup.deiconify()
        popup.bind("<Button-1>", lambda _event: self.close_popup_notification(popup))
        self.popup_windows.append(popup)

        duration_ms = self.current_popup_seconds() * 1000
        popup.after(duration_ms, lambda: self.close_popup_notification(popup))

    def current_popup_seconds(self) -> int:
        try:
            return max(1, min(60, int(self.notify_popup_seconds.get())))
        except TclError:
            return 4

    def close_popup_notification(self, popup: Toplevel) -> None:
        if popup in self.popup_windows:
            self.popup_windows.remove(popup)
        if popup.winfo_exists():
            popup.destroy()

    def close_app(self) -> None:
        self.stop_event.set()
        self.save_config()
        self.disconnect()
        self.tray_icon.stop()
        if self.temp_image_dir.exists():
            shutil.rmtree(self.temp_image_dir, ignore_errors=True)
        if self.server_socket:
            try:
                self.server_socket.close()
            except OSError:
                pass
        self.destroy()


if __name__ == "__main__":
    app = LanClipboardWindowsApp()
    app.mainloop()
