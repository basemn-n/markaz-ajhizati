from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("MARKAZ_DB_PATH", str(APP_DIR / "markaz.db")))
TOKEN_TTL_SECONDS = 60 * 60 * 12
PASSWORD_ITERATIONS = 310_000

app = FastAPI(
    title="مركز أجهزتي API",
    version="0.3.0",
    description="Authenticated starter API for account-owned devices and messages.",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[os.environ.get("MARKAZ_WEB_ORIGIN", "https://markaz-ajhizati.onrender.com")],
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def init_db() -> None:
    with connect() as db:
        db.executescript("""
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
        CREATE INDEX IF NOT EXISTS idx_messages_pair
            ON messages(sender_id, recipient_id, id);
        """)
init_db()


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[A-Za-z0-9_.-]+$")
    password: str = Field(min_length=10, max_length=128)


class LoginRequest(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=1, max_length=128)


class DeviceCreate(BaseModel):
    device_name: str = Field(min_length=1, max_length=80)
    device_type: str = Field(default="android", min_length=1, max_length=40)


class MessageCreate(BaseModel):
    recipient_username: str = Field(min_length=3, max_length=32)
    body: str = Field(min_length=1, max_length=4000)


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


def current_user(authorization: Annotated[str | None, Header()] = None) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Bearer access token required")
    payload = verify_token(authorization[7:].strip())
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
        "version": "0.3.0",
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
                "INSERT INTO users(username, password_salt, password_hash, created_at) VALUES(?,?,?,?)",
                (username, salt, password_hash, now()),
            )
            user_id = cur.lastrowid
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail="Username is already registered")
    token = issue_token(user_id, username)
    return {"access_token": token, "token_type": "bearer", "expires_in": TOKEN_TTL_SECONDS,
            "user": {"id": user_id, "username": username}}


@app.post("/api/auth/login")
def login(payload: LoginRequest):
    auth_secret()  # Fail before authentication work if the server secret is missing.
    with connect() as db:
        user = db.execute("SELECT * FROM users WHERE username=? COLLATE NOCASE",
                          (payload.username.strip(),)).fetchone()
    if not user or not password_matches(payload.password, user["password_salt"], user["password_hash"]):
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    token = issue_token(user["id"], user["username"])
    return {"access_token": token, "token_type": "bearer", "expires_in": TOKEN_TTL_SECONDS,
            "user": {"id": user["id"], "username": user["username"]}}


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
            "INSERT INTO devices(owner_id, device_name, device_type, paired, created_at) VALUES(?,?,?,0,?)",
            (user["id"], payload.device_name.strip(), payload.device_type.strip(), now()),
        )
        row = db.execute(
            "SELECT id, device_name, device_type, paired, created_at FROM devices WHERE id=? AND owner_id=?",
            (cur.lastrowid, user["id"]),
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
        recipient = db.execute("SELECT id FROM users WHERE username=? COLLATE NOCASE",
                               (recipient_username,)).fetchone()
        if not recipient:
            raise HTTPException(status_code=404, detail="Recipient account not found")
        if recipient["id"] == user["id"]:
            raise HTTPException(status_code=422, detail="Cannot send a message to the same account")
        cur = db.execute(
            "INSERT INTO messages(sender_id, recipient_id, body, created_at) VALUES(?,?,?,?)",
            (user["id"], recipient["id"], payload.body.strip(), now()),
        )
        row = db.execute(
            """SELECT m.id, s.username AS sender, r.username AS recipient, m.body, m.created_at
               FROM messages m JOIN users s ON s.id=m.sender_id
               JOIN users r ON r.id=m.recipient_id WHERE m.id=?""",
            (cur.lastrowid,),
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
