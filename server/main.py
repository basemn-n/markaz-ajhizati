from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3

import psycopg
from psycopg.rows import dict_row
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("MARKAZ_DB_PATH", str(APP_DIR / "markaz.db")))
DATABASE_URL = os.environ.get("DATABASE_URL", "").strip()
IS_POSTGRES = bool(DATABASE_URL)
if os.environ.get("RENDER") and not DATABASE_URL:
    raise RuntimeError("DATABASE_URL is required on Render so user data is stored persistently.")
TOKEN_TTL_SECONDS = 60 * 60 * 12
PASSWORD_ITERATIONS = 310_000

app = FastAPI(
    title="مركز أجهزتي API",
    version="0.4.0",
    description="Authenticated starter API for account-owned devices and messages.",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[os.environ.get("MARKAZ_WEB_ORIGIN", "https://markaz-ajhizati.onrender.com")],
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)


class DatabaseConnection:
    """Small compatibility layer so the same API queries work with SQLite and PostgreSQL."""
    def __init__(self):
        if IS_POSTGRES:
            url = DATABASE_URL
            if url.startswith("postgres://"):
                url = "postgresql://" + url[len("postgres://"):]
            self.connection = psycopg.connect(url, row_factory=dict_row, connect_timeout=15)
        else:
            DB_PATH.parent.mkdir(parents=True, exist_ok=True)
            self.connection = sqlite3.connect(DB_PATH, timeout=10)
            self.connection.row_factory = sqlite3.Row
            self.connection.execute("PRAGMA foreign_keys = ON")

    def __enter__(self):
        self.connection.__enter__()
        return self

    def __exit__(self, exc_type, exc, tb):
        return self.connection.__exit__(exc_type, exc, tb)

    def execute(self, sql, params=()):
        if IS_POSTGRES:
            sql = sql.replace(" COLLATE NOCASE", "").replace("?", "%s")
        return self.connection.execute(sql, params)

    def executescript(self, sql):
        if IS_POSTGRES:
            for statement in sql.split(";"):
                if statement.strip():
                    self.connection.execute(statement)
        else:
            self.connection.executescript(sql)


def connect() -> DatabaseConnection:
    return DatabaseConnection()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def init_db() -> None:
    if IS_POSTGRES:
        schema = """
        CREATE TABLE IF NOT EXISTS users (
            id BIGSERIAL PRIMARY KEY,
            username TEXT NOT NULL,
            password_salt TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_ci ON users (lower(username));
        CREATE TABLE IF NOT EXISTS devices (
            id BIGSERIAL PRIMARY KEY,
            owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_name TEXT NOT NULL,
            device_type TEXT NOT NULL DEFAULT 'android',
            paired INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_devices_owner ON devices(owner_id, id DESC);
        CREATE TABLE IF NOT EXISTS messages (
            id BIGSERIAL PRIMARY KEY,
            sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            recipient_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            body TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_messages_pair ON messages(sender_id, recipient_id, id);
        CREATE TABLE IF NOT EXISTS pairing_codes (
            id BIGSERIAL PRIMARY KEY,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            code_hash TEXT NOT NULL UNIQUE,
            expires_at TEXT NOT NULL,
            used_at TEXT,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_pairing_codes_target ON pairing_codes(target_id, id DESC);
        CREATE TABLE IF NOT EXISTS device_links (
            id BIGSERIAL PRIMARY KEY,
            controller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT NOT NULL,
            approved_at TEXT,
            UNIQUE(controller_id, target_id),
            CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
            CHECK (controller_id <> target_id)
        );
        CREATE INDEX IF NOT EXISTS idx_device_links_target ON device_links(target_id, status, id DESC);
        CREATE INDEX IF NOT EXISTS idx_device_links_controller ON device_links(controller_id, status, id DESC);
        CREATE TABLE IF NOT EXISTS screen_sessions (
            id BIGSERIAL PRIMARY KEY,
            link_id BIGINT NOT NULL REFERENCES device_links(id) ON DELETE CASCADE,
            controller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status TEXT NOT NULL CHECK (status IN ('requested','accepted','rejected','active','stopped')),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_screen_sessions_participants ON screen_sessions(controller_id, target_id, status, id DESC);
        CREATE TABLE IF NOT EXISTS screen_signals (
            id BIGSERIAL PRIMARY KEY,
            session_id BIGINT NOT NULL REFERENCES screen_sessions(id) ON DELETE CASCADE,
            sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            recipient_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL CHECK (kind IN ('offer','answer','ice','stop')),
            payload TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_screen_signals_poll ON screen_signals(session_id, recipient_id, id);
        CREATE TABLE IF NOT EXISTS device_commands (
            id BIGSERIAL PRIMARY KEY,
            sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            command TEXT NOT NULL,
            args_json TEXT NOT NULL DEFAULT '{}',
            status TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'claimed', 'completed', 'failed', 'expired')),
            result_json TEXT,
            created_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            completed_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_device_commands_target
            ON device_commands(target_id, status, id);
        CREATE INDEX IF NOT EXISTS idx_device_commands_sender
            ON device_commands(sender_id, id DESC);
        CREATE TABLE IF NOT EXISTS user_presence (
            user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            last_seen_at TEXT NOT NULL
        );
        """
    else:
        schema = """
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password_salt TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_name TEXT NOT NULL,
            device_type TEXT NOT NULL DEFAULT 'android',
            paired INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_devices_owner ON devices(owner_id, id DESC);
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            recipient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            body TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_messages_pair ON messages(sender_id, recipient_id, id);
        CREATE TABLE IF NOT EXISTS pairing_codes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            target_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            code_hash TEXT NOT NULL UNIQUE,
            expires_at TEXT NOT NULL,
            used_at TEXT,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_pairing_codes_target ON pairing_codes(target_id, id DESC);
        CREATE TABLE IF NOT EXISTS device_links (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            controller_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
            created_at TEXT NOT NULL,
            approved_at TEXT,
            UNIQUE(controller_id, target_id),
            CHECK (controller_id <> target_id)
        );
        CREATE INDEX IF NOT EXISTS idx_device_links_target ON device_links(target_id, status, id DESC);
        CREATE INDEX IF NOT EXISTS idx_device_links_controller ON device_links(controller_id, status, id DESC);
        CREATE TABLE IF NOT EXISTS screen_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            link_id BIGINT NOT NULL REFERENCES device_links(id) ON DELETE CASCADE,
            controller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status TEXT NOT NULL CHECK (status IN ('requested','accepted','rejected','active','stopped')),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_screen_sessions_participants ON screen_sessions(controller_id, target_id, status, id DESC);
        CREATE TABLE IF NOT EXISTS screen_signals (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id BIGINT NOT NULL REFERENCES screen_sessions(id) ON DELETE CASCADE,
            sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            recipient_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL CHECK (kind IN ('offer','answer','ice','stop')),
            payload TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_screen_signals_poll ON screen_signals(session_id, recipient_id, id);
        CREATE TABLE IF NOT EXISTS device_commands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            command TEXT NOT NULL,
            args_json TEXT NOT NULL DEFAULT '{}',
            status TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'claimed', 'completed', 'failed', 'expired')),
            result_json TEXT,
            created_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            completed_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_device_commands_target
            ON device_commands(target_id, status, id);
        CREATE INDEX IF NOT EXISTS idx_device_commands_sender
            ON device_commands(sender_id, id DESC);
        CREATE TABLE IF NOT EXISTS user_presence (
            user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            last_seen_at TEXT NOT NULL
        );
        """
    with connect() as db:
        db.executescript(schema)
init_db()


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[A-Za-z0-9_.-]+$")
    password: str = Field(min_length=10, max_length=128)


class LoginRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=1, max_length=128)


class PasswordRecoveryRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    recovery_key: str = Field(min_length=32, max_length=256)
    new_password: str = Field(min_length=10, max_length=128)


class DeviceCreate(BaseModel):
    device_name: str = Field(min_length=1, max_length=80)
    device_type: str = Field(default="android", min_length=1, max_length=40)


class MessageCreate(BaseModel):
    recipient_username: str = Field(min_length=3, max_length=32)
    body: str = Field(min_length=1, max_length=4000)


class CommandCreate(BaseModel):
    target_user_id: int = Field(gt=0)
    command: str = Field(min_length=1, max_length=40)
    args: dict = Field(default_factory=dict)


class CommandResultCreate(BaseModel):
    status: str = Field(pattern=r"^(completed|failed)$")
    result: dict = Field(default_factory=dict)


ALLOWED_DEVICE_COMMANDS = {"/status", "/device", "/help"}
COMMAND_TTL_MINUTES = 5


def auth_secret() -> bytes:
    value = os.environ.get("MARKAZ_AUTH_SECRET", "")
    if len(value) < 32:
        raise HTTPException(
            status_code=503,
            detail="Server authentication is not configured. Set MARKAZ_AUTH_SECRET to a random secret of at least 32 characters.",
        )
    return value.encode("utf-8")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def issue_token(user_id: int, username: str) -> str:
    secret = auth_secret()
    payload = {"sub": user_id, "username": username, "exp": int(time.time()) + TOKEN_TTL_SECONDS}
    encoded = b64url(json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode())
    signature = b64url(hmac.new(secret, encoded.encode("ascii"), hashlib.sha256).digest())
    return encoded + "." + signature


def verify_token(token: str) -> dict:
    try:
        encoded, supplied_signature = token.split(".", 1)
        expected = b64url(hmac.new(auth_secret(), encoded.encode("ascii"), hashlib.sha256).digest())
        if not hmac.compare_digest(supplied_signature, expected):
            raise ValueError("bad signature")
        payload = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        if int(payload["exp"]) <= int(time.time()) or int(payload["sub"]) <= 0:
            raise ValueError("expired token")
        return payload
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired access token")


def hash_password(password: str, salt: bytes | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS)
    return b64url(salt), b64url(digest)


def password_matches(password: str, salt_text: str, hash_text: str) -> bool:
    salt = base64.urlsafe_b64decode(salt_text + "=" * (-len(salt_text) % 4))
    _, candidate = hash_password(password, salt)
    return hmac.compare_digest(candidate, hash_text)


bearer_scheme = HTTPBearer(auto_error=False)


def current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
) -> dict:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="Bearer access token required")
    payload = verify_token(credentials.credentials.strip())
    with connect() as db:
        user = db.execute(
            "SELECT id, username, created_at FROM users WHERE id=?",
            (payload["sub"],),
        ).fetchone()
    if not user:
        raise HTTPException(status_code=401, detail="Account no longer exists")
    return dict(user)


CurrentUser = Annotated[dict, Depends(current_user)]


@app.get("/")
def root():
    return {
        "app": "مركز أجهزتي",
        "version": "0.4.0",
        "status": "authenticated-starter",
        "note": "Use HTTPS, configure MARKAZ_AUTH_SECRET, and configure persistent storage before production use.",
    }


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/api/auth/register", status_code=201)
def register(payload: RegisterRequest):
    auth_secret()  # Fail before writing an account if the server secret is missing.
    username = payload.username.strip()
    if len(username) < 3:
        raise HTTPException(status_code=422, detail="Username must contain at least 3 characters")
    salt, password_hash = hash_password(payload.password)
    try:
        with connect() as db:
            cur = db.execute(
                "INSERT INTO users(username, password_salt, password_hash, created_at) VALUES(?,?,?,?) RETURNING id",
                (username, salt, password_hash, now()),
            )
            user_id = cur.fetchone()["id"]
    except (sqlite3.IntegrityError, psycopg.errors.UniqueViolation):
        raise HTTPException(status_code=409, detail="Username is already registered")
    token = issue_token(user_id, username)
    return {"access_token": token, "token_type": "bearer", "expires_in": TOKEN_TTL_SECONDS,
            "user": {"id": user_id, "username": username}}


@app.post("/api/auth/login")
def login(payload: LoginRequest):
    auth_secret()  # Fail before authentication work if the server secret is missing.
    with connect() as db:
        user = db.execute("SELECT * FROM users WHERE lower(username)=lower(?)",
                          (payload.username.strip(),)).fetchone()
    if not user or not password_matches(payload.password, user["password_salt"], user["password_hash"]):
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    token = issue_token(user["id"], user["username"])
    return {"access_token": token, "token_type": "bearer", "expires_in": TOKEN_TTL_SECONDS,
            "user": {"id": user["id"], "username": user["username"]}}


@app.post("/api/auth/recover-password")
def recover_password(payload: PasswordRecoveryRequest):
    configured_key = os.environ.get("MARKAZ_RECOVERY_KEY", "")
    if len(configured_key) < 32:
        raise HTTPException(
            status_code=503,
            detail="Password recovery is not configured. Set MARKAZ_RECOVERY_KEY to a random secret of at least 32 characters.",
        )
    if not hmac.compare_digest(payload.recovery_key, configured_key):
        raise HTTPException(status_code=403, detail="Invalid recovery key")

    username = payload.username.strip()
    salt, password_hash = hash_password(payload.new_password)
    with connect() as db:
        user = db.execute(
            "SELECT id FROM users WHERE lower(username)=lower(?)",
            (username,),
        ).fetchone()
        if not user:
            raise HTTPException(
                status_code=404,
                detail="Account not found in this database. Register a new account or check persistent storage.",
            )
        db.execute(
            "UPDATE users SET password_salt=?, password_hash=? WHERE id=?",
            (salt, password_hash, user["id"]),
        )
    return {"ok": True, "message": "Password updated. Sign in with the new password."}


@app.get("/api/auth/me")
def me(user: CurrentUser):
    return user


@app.get("/api/devices")
def list_devices(user: CurrentUser):
    with connect() as db:
        rows = db.execute(
            "SELECT id, device_name, device_type, paired, created_at FROM devices WHERE owner_id=? ORDER BY id DESC",
            (user["id"],),
        ).fetchall()
    return [dict(row) for row in rows]


@app.post("/api/devices", status_code=201)
def create_device(payload: DeviceCreate, user: CurrentUser):
    with connect() as db:
        cur = db.execute(
            "INSERT INTO devices(owner_id, device_name, device_type, paired, created_at) VALUES(?,?,?,0,?) RETURNING id",
            (user["id"], payload.device_name.strip(), payload.device_type.strip(), now()),
        )
        inserted_id = cur.fetchone()["id"]
        row = db.execute(
            "SELECT id, device_name, device_type, paired, created_at FROM devices WHERE id=? AND owner_id=?",
            (inserted_id, user["id"]),
        ).fetchone()
    return dict(row)


@app.delete("/api/devices/{device_id}", status_code=204)
def delete_device(device_id: int, user: CurrentUser):
    with connect() as db:
        cur = db.execute("DELETE FROM devices WHERE id=? AND owner_id=?", (device_id, user["id"]))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Device not found")


@app.post("/api/messages", status_code=201)
def send_message(payload: MessageCreate, user: CurrentUser):
    recipient_username = payload.recipient_username.strip()
    with connect() as db:
        recipient = db.execute("SELECT id FROM users WHERE lower(username)=lower(?)",
                               (recipient_username,)).fetchone()
        if not recipient:
            raise HTTPException(status_code=404, detail="Recipient account not found")
        if recipient["id"] == user["id"]:
            raise HTTPException(status_code=422, detail="Cannot send a message to the same account")
        cur = db.execute(
            "INSERT INTO messages(sender_id, recipient_id, body, created_at) VALUES(?,?,?,?) RETURNING id",
            (user["id"], recipient["id"], payload.body.strip(), now()),
        )
        inserted_id = cur.fetchone()["id"]
        row = db.execute(
            """SELECT m.id, s.username AS sender, r.username AS recipient, m.body, m.created_at
               FROM messages m JOIN users s ON s.id=m.sender_id
               JOIN users r ON r.id=m.recipient_id WHERE m.id=?""",
            (inserted_id,),
        ).fetchone()
    return dict(row)


@app.get("/api/messages")
def get_messages(
    user: CurrentUser,
    with_user: str = Query(min_length=3, max_length=32),
    limit: int = Query(default=100, ge=1, le=200),
):
    with connect() as db:
        other = db.execute("SELECT id FROM users WHERE username=? COLLATE NOCASE",
                           (with_user.strip(),)).fetchone()
        if not other:
            raise HTTPException(status_code=404, detail="Account not found")
        rows = db.execute(
            """SELECT m.id, s.username AS sender, r.username AS recipient, m.body, m.created_at
               FROM messages m JOIN users s ON s.id=m.sender_id
               JOIN users r ON r.id=m.recipient_id
               WHERE (m.sender_id=? AND m.recipient_id=?)
                  OR (m.sender_id=? AND m.recipient_id=?)
               ORDER BY m.id DESC LIMIT ?""",
            (user["id"], other["id"], other["id"], user["id"], limit),
        ).fetchall()
    return [dict(row) for row in reversed(rows)]


@app.post("/api/commands", status_code=201)
def create_device_command(payload: CommandCreate, user: CurrentUser):
    """Queue a bounded command for a target account with an approved link."""
    command = payload.command.strip().lower()
    if command not in ALLOWED_DEVICE_COMMANDS:
        raise HTTPException(status_code=422, detail="This device command is not allowed")
    if payload.args:
        raise HTTPException(status_code=422, detail="Arguments are not supported for this command")
    if int(payload.target_user_id) == int(user["id"]):
        raise HTTPException(status_code=422, detail="Cannot send a command to the same account")

    created = datetime.now(timezone.utc)
    created_text = created.isoformat()
    expires_text = (created + timedelta(minutes=COMMAND_TTL_MINUTES)).isoformat()

    with connect() as db:
        link = db.execute(
            """SELECT id FROM device_links
               WHERE controller_id=? AND target_id=? AND status='approved'""",
            (user["id"], payload.target_user_id),
        ).fetchone()
        if not link:
            raise HTTPException(status_code=403, detail="An approved link is required")

        cur = db.execute(
            """INSERT INTO device_commands
               (sender_id, target_id, command, args_json, status, created_at, expires_at)
               VALUES (?, ?, ?, '{}', 'pending', ?, ?) RETURNING id""",
            (user["id"], payload.target_user_id, command, created_text, expires_text),
        )
        command_id = cur.fetchone()["id"]

    return {
        "id": command_id,
        "command": command,
        "status": "pending",
        "expires_at": expires_text,
    }


@app.get("/api/commands/pending")
def poll_pending_commands(user: CurrentUser):
    """Claim pending commands addressed only to the authenticated target account."""
    current_time = now()
    with connect() as db:
        db.execute(
            """UPDATE device_commands SET status='expired'
               WHERE target_id=? AND status='pending' AND expires_at<=?""",
            (user["id"], current_time),
        )
        rows = db.execute(
            """SELECT c.id, c.sender_id, c.command, c.args_json, c.created_at, c.expires_at
               FROM device_commands c
               JOIN device_links l
                 ON l.controller_id=c.sender_id
                AND l.target_id=c.target_id
                AND l.status='approved'
               WHERE c.target_id=? AND c.status='pending' AND c.expires_at>?
               ORDER BY c.id LIMIT 20""",
            (user["id"], current_time),
        ).fetchall()

        commands = []
        for row in rows:
            item = dict(row)
            claimed = db.execute(
                """UPDATE device_commands SET status='claimed'
                   WHERE id=? AND target_id=? AND status='pending' AND expires_at>?""",
                (item["id"], user["id"], current_time),
            )
            if claimed.rowcount != 1:
                continue
            try:
                args = json.loads(item["args_json"])
            except (TypeError, ValueError):
                args = {}
            commands.append({
                "id": item["id"],
                "command": item["command"],
                "args": args,
                "created_at": item["created_at"],
                "expires_at": item["expires_at"],
            })
    return commands


@app.post("/api/commands/{command_id}/result")
def submit_command_result(
    command_id: int,
    payload: CommandResultCreate,
    user: CurrentUser,
):
    result_json = json.dumps(payload.result, ensure_ascii=False, separators=(",", ":"))
    if len(result_json) > 4000:
        raise HTTPException(status_code=413, detail="Command result is too large")

    completed_time = now()
    with connect() as db:
        command = db.execute(
            """SELECT id, sender_id, target_id, status, expires_at
               FROM device_commands WHERE id=?""",
            (command_id,),
        ).fetchone()
        if not command or int(command["target_id"]) != int(user["id"]):
            raise HTTPException(status_code=404, detail="Command not found")
        if command["status"] != "claimed":
            raise HTTPException(status_code=409, detail="Command is not awaiting a result")
        if command["expires_at"] <= completed_time:
            db.execute(
                "UPDATE device_commands SET status='expired' WHERE id=? AND status='claimed'",
                (command_id,),
            )
            # Raise after the context would roll back this update; expiry is still
            # enforced by refusing the result, and a later poll/cleanup can expire it.
            raise HTTPException(status_code=409, detail="Command has expired")

        link = db.execute(
            """SELECT id FROM device_links
               WHERE controller_id=? AND target_id=? AND status='approved'""",
            (command["sender_id"], user["id"]),
        ).fetchone()
        if not link:
            raise HTTPException(status_code=403, detail="Approved link is required")

        changed = db.execute(
            """UPDATE device_commands
               SET status=?, result_json=?, completed_at=?
               WHERE id=? AND target_id=? AND status='claimed'""",
            (payload.status, result_json, completed_time, command_id, user["id"]),
        )
        if changed.rowcount != 1:
            raise HTTPException(status_code=409, detail="Command result was already submitted")

    return {"id": command_id, "status": payload.status}


@app.get("/api/commands")
def list_my_commands(
    user: CurrentUser,
    limit: int = Query(default=50, ge=1, le=100),
):
    """List commands created by the signed-in controller and their results."""
    with connect() as db:
        rows = db.execute(
            """SELECT c.id, c.target_id, u.username AS target_username,
                      c.command, c.status, c.result_json,
                      c.created_at, c.expires_at, c.completed_at
               FROM device_commands c
               JOIN users u ON u.id=c.target_id
               WHERE c.sender_id=?
               ORDER BY c.id DESC LIMIT ?""",
            (user["id"], limit),
        ).fetchall()

    output = []
    for row in rows:
        item = dict(row)
        try:
            raw_result = item.pop("result_json")
            item["result"] = json.loads(raw_result) if raw_result else None
        except (TypeError, ValueError):
            item["result"] = None
        output.append(item)
    return output


class PairingCodeRequest(BaseModel):
    code: str = Field(min_length=6, max_length=6, pattern=r"^[0-9]{6}$")


@app.post("/api/pairing-codes")
def create_pairing_code(user: CurrentUser):
    """Create a short-lived code on the phone that will be managed."""
    code = f"{secrets.randbelow(1_000_000):06d}"
    created = datetime.now(timezone.utc)
    expires = created + timedelta(minutes=10)
    code_hash = hashlib.sha256(code.encode("ascii")).hexdigest()
    with connect() as db:
        db.execute(
            "UPDATE pairing_codes SET used_at=? WHERE target_id=? AND used_at IS NULL",
            (created.isoformat(), user["id"]),
        )
        db.execute(
            "INSERT INTO pairing_codes(target_id, code_hash, expires_at, used_at, created_at) VALUES(?,?,?,?,?)",
            (user["id"], code_hash, expires.isoformat(), None, created.isoformat()),
        )
    return {
        "pairing_code": code,
        "expires_at": expires.isoformat(),
        "message": "Share this code with the person you want to authorize. They must request access, and you must approve it.",
    }


@app.post("/api/pairings", status_code=201)
def request_pairing(payload: PairingCodeRequest, user: CurrentUser):
    """Request access using a code displayed by the target user; does not grant access."""
    now_text = now()
    code_hash = hashlib.sha256(payload.code.encode("ascii")).hexdigest()
    with connect() as db:
        code_row = db.execute(
            "SELECT id, target_id FROM pairing_codes WHERE code_hash=? AND used_at IS NULL AND expires_at>?",
            (code_hash, now_text),
        ).fetchone()
        if not code_row:
            raise HTTPException(status_code=400, detail="Pairing code is invalid or expired")
        target_id = code_row["target_id"]
        if int(target_id) == int(user["id"]):
            raise HTTPException(status_code=422, detail="You cannot pair an account with itself")
        existing = db.execute(
            "SELECT id, status FROM device_links WHERE controller_id=? AND target_id=?",
            (user["id"], target_id),
        ).fetchone()
        if existing and existing["status"] in ("pending", "approved"):
            raise HTTPException(status_code=409, detail="A pairing request or approved link already exists")
        if existing:
            db.execute(
                "UPDATE device_links SET status='pending', created_at=?, approved_at=NULL WHERE id=?",
                (now_text, existing["id"]),
            )
            link_id = existing["id"]
        else:
            cur = db.execute(
                "INSERT INTO device_links(controller_id, target_id, status, created_at) VALUES(?,?, 'pending', ?) RETURNING id",
                (user["id"], target_id, now_text),
            )
            link_id = cur.fetchone()["id"]
        db.execute("UPDATE pairing_codes SET used_at=? WHERE id=?", (now_text, code_row["id"]))
    return {"id": link_id, "status": "pending", "message": "Request sent. The target user must approve it."}


@app.get("/api/pairings/incoming")
def incoming_pairings(user: CurrentUser):
    """List pending access requests for the signed-in target account."""
    with connect() as db:
        rows = db.execute(
            """SELECT l.id, l.status, l.created_at, u.username AS controller_username
               FROM device_links l JOIN users u ON u.id=l.controller_id
               WHERE l.target_id=? AND l.status='pending'
               ORDER BY l.id DESC""",
            (user["id"],),
        ).fetchall()
    return [dict(row) for row in rows]


class PairingDecision(BaseModel):
    approved: bool


@app.post("/api/pairings/{pairing_id}/decision")
def decide_pairing(pairing_id: int, payload: PairingDecision, user: CurrentUser):
    """Only the target account can approve or reject a request."""
    with connect() as db:
        row = db.execute(
            "SELECT id, status FROM device_links WHERE id=? AND target_id=?",
            (pairing_id, user["id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Pairing request not found")
        if row["status"] != "pending":
            raise HTTPException(status_code=409, detail="Pairing request is no longer pending")
        status = "approved" if payload.approved else "rejected"
        approved_at = now() if payload.approved else None
        db.execute(
            "UPDATE device_links SET status=?, approved_at=? WHERE id=? AND target_id=?",
            (status, approved_at, pairing_id, user["id"]),
        )
    return {"id": pairing_id, "status": status}


@app.post("/api/presence/heartbeat")
def presence_heartbeat(user: CurrentUser):
    """Record that this account's web/PWA session is currently active."""
    seen_at = now()
    with connect() as db:
        existing = db.execute("SELECT user_id FROM user_presence WHERE user_id=?", (user["id"],)).fetchone()
        if existing:
            db.execute("UPDATE user_presence SET last_seen_at=? WHERE user_id=?", (seen_at, user["id"]))
        else:
            db.execute("INSERT INTO user_presence(user_id, last_seen_at) VALUES(?,?)", (user["id"], seen_at))
    return {"ok": True, "last_seen_at": seen_at}


@app.get("/api/pairings")
def list_pairings(user: CurrentUser):
    """List each link involving this account from either side, with a consistent status."""
    with connect() as db:
        rows = db.execute(
            """SELECT l.id, l.status, l.created_at, l.approved_at,
                      CASE WHEN l.controller_id=? THEN target.id ELSE controller.id END AS target_user_id,
                      CASE WHEN l.controller_id=? THEN target.username ELSE controller.username END AS target_username,
                      p.last_seen_at AS last_seen_at
               FROM device_links l
               JOIN users controller ON controller.id=l.controller_id
               JOIN users target ON target.id=l.target_id
               LEFT JOIN user_presence p
                 ON p.user_id = CASE WHEN l.controller_id=? THEN l.target_id ELSE l.controller_id END
               WHERE l.controller_id=? OR l.target_id=?
               ORDER BY l.id DESC""",
            (user["id"], user["id"], user["id"], user["id"], user["id"]),
        ).fetchall()
    result = []
    current_time = datetime.now(timezone.utc)
    seen_pairs = set()
    for row in rows:
        item = dict(row)
        if item["status"] == "rejected":
            continue
        # If an old revoked row and a newer approved row exist for the same two users,
        # show only the newest link for this account pair.
        pair_key = (min(user["id"], item["target_user_id"]), max(user["id"], item["target_user_id"]))
        if pair_key in seen_pairs:
            continue
        seen_pairs.add(pair_key)
        seen = item.get("last_seen_at")
        is_online = False
        if seen:
            try:
                seen_dt = datetime.fromisoformat(str(seen).replace("Z", "+00:00"))
                if seen_dt.tzinfo is None:
                    seen_dt = seen_dt.replace(tzinfo=timezone.utc)
                is_online = (current_time - seen_dt).total_seconds() <= 90
            except (TypeError, ValueError):
                is_online = False
        item["session_status"] = "active" if is_online else "inactive"
        result.append(item)
    return result


@app.post("/api/pairings/{pairing_id}/revoke")
def revoke_pairing(pairing_id: int, user: CurrentUser):
    """Allow either side to revoke a previously approved link."""
    with connect() as db:
        row = db.execute(
            "SELECT id, controller_id, target_id, status FROM device_links WHERE id=? AND (controller_id=? OR target_id=?)",
            (pairing_id, user["id"], user["id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Pairing link not found")
        if row["status"] != "approved":
            raise HTTPException(status_code=409, detail="Only an approved link can be revoked")
        db.execute("UPDATE device_links SET status='revoked' WHERE id=?", (pairing_id,))
    return {"id": pairing_id, "status": "revoked"}


# Screen-sharing signaling foundation. This API relays WebRTC negotiation only;
# it does not capture a screen or bypass Android's MediaProjection consent dialog.
class ScreenSessionCreate(BaseModel):
    target_user_id: int = Field(gt=0)


class ScreenSignalCreate(BaseModel):
    kind: str = Field(pattern=r"^(offer|answer|ice|stop)$")
    payload: dict = Field(default_factory=dict)


def _approved_screen_link(db, actor_id: int, peer_id: int):
    link = db.execute(
        """SELECT id, controller_id, target_id FROM device_links
           WHERE status='approved'
             AND ((controller_id=? AND target_id=?) OR (controller_id=? AND target_id=?))
           ORDER BY id DESC LIMIT 1""",
        (actor_id, peer_id, peer_id, actor_id),
    ).fetchone()
    if not link:
        raise HTTPException(status_code=403, detail="An approved pairing is required for screen sharing")
    return dict(link)


@app.post("/api/screen-sessions", status_code=201)
def create_screen_session(payload: ScreenSessionCreate, user: CurrentUser):
    """Controller requests a screen session. The target must explicitly accept on its device."""
    if int(payload.target_user_id) == int(user["id"]):
        raise HTTPException(status_code=422, detail="Cannot share a screen with the same account")
    with connect() as db:
        link = _approved_screen_link(db, int(user["id"]), int(payload.target_user_id))
        cur = db.execute(
            """INSERT INTO screen_sessions(link_id, controller_id, target_id, status, created_at, updated_at)
               VALUES(?,?,?,'requested',?,?) RETURNING id""",
            (link["id"], user["id"], payload.target_user_id, now(), now()),
        )
        session_id = cur.fetchone()["id"]
    return {"id": session_id, "status": "requested",
            "message": "Request sent. The target must explicitly accept screen sharing on the Android device."}


@app.get("/api/screen-sessions")
def list_screen_sessions(user: CurrentUser):
    with connect() as db:
        rows = db.execute(
            """SELECT s.id, s.link_id, s.controller_id, s.target_id, s.status, s.created_at, s.updated_at,
                      c.username AS controller_username, t.username AS target_username
               FROM screen_sessions s
               JOIN users c ON c.id=s.controller_id JOIN users t ON t.id=s.target_id
               WHERE (s.controller_id=? OR s.target_id=?) AND s.status IN ('requested','accepted','active')
               ORDER BY s.id DESC LIMIT 50""",
            (user["id"], user["id"]),
        ).fetchall()
    return [dict(row) for row in rows]


class ScreenSessionDecision(BaseModel):
    accepted: bool


@app.post("/api/screen-sessions/{session_id}/decision")
def decide_screen_session(session_id: int, payload: ScreenSessionDecision, user: CurrentUser):
    """Only the target account can accept a request; acceptance alone does not start capture."""
    status = "accepted" if payload.accepted else "rejected"
    with connect() as db:
        row = db.execute(
            "SELECT id, link_id, controller_id, target_id, status FROM screen_sessions WHERE id=? AND target_id=?",
            (session_id, user["id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Screen-sharing request not found")
        _approved_screen_link(db, int(user["id"]), int(row["controller_id"]))
        if row["status"] != "requested":
            raise HTTPException(status_code=409, detail="Screen-sharing request is no longer pending")
        db.execute("UPDATE screen_sessions SET status=?, updated_at=? WHERE id=?",
                   (status, now(), session_id))
    return {"id": session_id, "status": status}


@app.post("/api/screen-sessions/{session_id}/signals", status_code=201)
def send_screen_signal(session_id: int, payload: ScreenSignalCreate, user: CurrentUser):
    """Store a bounded WebRTC signaling message for the other participant."""
    serialized = json.dumps(payload.payload, separators=(",", ":"), ensure_ascii=True)
    if len(serialized) > 12000:
        raise HTTPException(status_code=413, detail="Signaling payload is too large")
    with connect() as db:
        session = db.execute(
            "SELECT id, link_id, controller_id, target_id, status FROM screen_sessions WHERE id=?",
            (session_id,),
        ).fetchone()
        if not session or int(user["id"]) not in (int(session["controller_id"]), int(session["target_id"])):
            raise HTTPException(status_code=404, detail="Screen-sharing session not found")
        _approved_screen_link(db, int(user["id"]),
                              int(session["target_id"] if int(user["id"]) == int(session["controller_id"]) else session["controller_id"]))
        if session["status"] not in ("accepted", "active"):
            raise HTTPException(status_code=409, detail="The target has not accepted this screen-sharing request")
        recipient_id = session["target_id"] if int(user["id"]) == int(session["controller_id"]) else session["controller_id"]
        cur = db.execute(
            """INSERT INTO screen_signals(session_id, sender_id, recipient_id, kind, payload, created_at)
               VALUES(?,?,?,?,?,?) RETURNING id""",
            (session_id, user["id"], recipient_id, payload.kind, serialized, now()),
        )
        signal_id = cur.fetchone()["id"]
        if payload.kind == "stop":
            db.execute("UPDATE screen_sessions SET status='stopped', updated_at=? WHERE id=?",
                       (now(), session_id))
    return {"id": signal_id, "ok": True}


@app.get("/api/screen-sessions/{session_id}/signals")
def poll_screen_signals(session_id: int, user: CurrentUser, after_id: int = Query(default=0, ge=0)):
    with connect() as db:
        session = db.execute(
            "SELECT id, controller_id, target_id, status FROM screen_sessions WHERE id=?",
            (session_id,),
        ).fetchone()
        if not session or int(user["id"]) not in (int(session["controller_id"]), int(session["target_id"])):
            raise HTTPException(status_code=404, detail="Screen-sharing session not found")
        peer_id = session["target_id"] if int(user["id"]) == int(session["controller_id"]) else session["controller_id"]
        _approved_screen_link(db, int(user["id"]), int(peer_id))
        rows = db.execute(
            """SELECT id, sender_id, kind, payload, created_at FROM screen_signals
               WHERE session_id=? AND recipient_id=? AND id>? ORDER BY id LIMIT 100""",
            (session_id, user["id"], after_id),
        ).fetchall()
    result = []
    for row in rows:
        item = dict(row)
        try:
            item["payload"] = json.loads(item["payload"])
        except (TypeError, ValueError):
            item["payload"] = {}
        result.append(item)
    return {"status": session["status"], "signals": result}


@app.post("/api/screen-sessions/{session_id}/stop")
def stop_screen_session(session_id: int, user: CurrentUser):
    with connect() as db:
        session = db.execute(
            "SELECT id, controller_id, target_id FROM screen_sessions WHERE id=? AND (controller_id=? OR target_id=?)",
            (session_id, user["id"], user["id"]),
        ).fetchone()
        if not session:
            raise HTTPException(status_code=404, detail="Screen-sharing session not found")
        peer_id = session["target_id"] if int(user["id"]) == int(session["controller_id"]) else session["controller_id"]
        # A revoked pairing must not be able to send stop/signaling messages.
        _approved_screen_link(db, int(user["id"]), int(peer_id))
        db.execute("UPDATE screen_sessions SET status='stopped', updated_at=? WHERE id=?", (now(), session_id))
        db.execute(
            """INSERT INTO screen_signals(session_id, sender_id, recipient_id, kind, payload, created_at)
               VALUES(?,?,?,'stop','{}',?)""",
            (session_id, user["id"], peer_id, now()),
        )
    return {"id": session_id, "status": "stopped"}
