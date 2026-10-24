from datetime import datetime, timezone
from pathlib import Path
import secrets
import sqlite3

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

DB_PATH = Path(__file__).with_name("markaz.db")
app = FastAPI(title="Markaz Ajhizati API", version="0.2.0",
              description="Prototype API for messaging and consent-based device pairing.")

def connect():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn

def now():
    return datetime.now(timezone.utc).isoformat()

def init_db():
    with connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_name TEXT NOT NULL,
            device_name TEXT NOT NULL,
            device_type TEXT NOT NULL DEFAULT 'android',
            pairing_code TEXT NOT NULL UNIQUE,
            paired INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender TEXT NOT NULL,
            recipient TEXT NOT NULL,
            body TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        """)
init_db()

class DeviceCreate(BaseModel):
    owner_name: str = Field(min_length=1, max_length=80)
    device_name: str = Field(min_length=1, max_length=80)
    device_type: str = Field(default="android", max_length=40)

class PairRequest(BaseModel):
    pairing_code: str = Field(min_length=6, max_length=32)
    approving_device_name: str = Field(min_length=1, max_length=80)

class MessageCreate(BaseModel):
    sender: str = Field(min_length=1, max_length=80)
    recipient: str = Field(min_length=1, max_length=80)
    body: str = Field(min_length=1, max_length=4000)

@app.get("/")
def root():
    return {
        "app": "مركز أجهزتي",
        "version": "0.2.0",
        "status": "starter",
        "note": "Prototype only; add authentication, TLS, authorization and rate limits before public deployment."
    }

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/api/devices/register")
def register_device(payload: DeviceCreate):
    # Demo only: production needs short-lived codes and authenticated approval.
    code = secrets.token_urlsafe(9)[:12].upper()
    with connect() as db:
        while db.execute("SELECT 1 FROM devices WHERE pairing_code=?", (code,)).fetchone():
            code = secrets.token_urlsafe(9)[:12].upper()
        cur = db.execute(
            "INSERT INTO devices(owner_name, device_name, device_type, pairing_code, paired, created_at) VALUES(?,?,?,?,0,?)",
            (payload.owner_name, payload.device_name, payload.device_type, code, now())
        )
        device_id = cur.lastrowid
    return {
        "device_id": device_id,
        "device_name": payload.device_name,
        "pairing_code": code,
        "paired": False,
        "message": "Demo pairing code created. Confirm pairing on the target device."
    }

@app.post("/api/devices/pair")
def pair_device(payload: PairRequest):
    with connect() as db:
        row = db.execute("SELECT * FROM devices WHERE pairing_code=?", (payload.pairing_code.upper(),)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Invalid pairing code")
        db.execute("UPDATE devices SET paired=1 WHERE id=?", (row["id"],))
    return {
        "device_id": row["id"],
        "device_name": row["device_name"],
        "paired": True,
        "approved_by": payload.approving_device_name,
        "message": "Demo pairing recorded; production authentication is still required."
    }

@app.get("/api/devices")
def list_devices(owner_name: str):
    with connect() as db:
        rows = db.execute(
            "SELECT id, owner_name, device_name, device_type, paired, created_at FROM devices WHERE owner_name=? ORDER BY id DESC",
            (owner_name,)
        ).fetchall()
    return [dict(r) for r in rows]

@app.post("/api/messages")
def send_message(payload: MessageCreate):
    with connect() as db:
        cur = db.execute(
            "INSERT INTO messages(sender, recipient, body, created_at) VALUES(?,?,?,?)",
            (payload.sender, payload.recipient, payload.body, now())
        )
        row = db.execute("SELECT * FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
    return dict(row)

@app.get("/api/messages")
def get_messages(user: str, with_user: str, limit: int = 100):
    limit = max(1, min(limit, 200))
    with connect() as db:
        rows = db.execute(
            "SELECT * FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?) ORDER BY id DESC LIMIT ?",
            (user, with_user, with_user, user, limit)
        ).fetchall()
    return [dict(r) for r in reversed(rows)]

@app.websocket("/ws/chat/{username}")
async def chat_socket(websocket: WebSocket, username: str):
    # Prototype echo/ack socket, not production message delivery.
    await websocket.accept()
    try:
        while True:
            data = await websocket.receive_json()
            if not isinstance(data, dict) or not data.get("recipient") or not data.get("body"):
                await websocket.send_json({"type": "error", "message": "recipient and body are required"})
                continue
            await websocket.send_json({
                "type": "accepted",
                "message": {
                    "sender": username,
                    "recipient": str(data["recipient"])[:80],
                    "body": str(data["body"])[:4000],
                    "created_at": now()
                }
            })
    except WebSocketDisconnect:
        return
