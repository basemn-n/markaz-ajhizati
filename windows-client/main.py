"""
Markaz Ajhizati Windows client.
Receives only the server's read-only allowlisted commands; it does not control a phone.
Requires Python 3.10+ on Windows. Run: py main.py
"""
import json
import os
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

API = os.environ.get("MARKAZ_API_URL", "https://markaz-ajhizati-api.onrender.com").rstrip("/")
ALLOWED_COMMANDS = {"/status", "/device", "/help"}
POLL_MS = 3000


class Client(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("مركز أجهزتي — Windows")
        self.geometry("820x650")
        self.token = None
        self.username_value = None
        self.links = []
        self.receiver_running = False
        self.receiver_after_id = None
        self._build_login()
        self.protocol("WM_DELETE_WINDOW", self.close_app)

    def call(self, method, path, data=None):
        raw = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json"}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = Request(API + path, data=raw, headers=headers, method=method)
        try:
            with urlopen(req, timeout=15) as res:
                body = res.read().decode("utf-8")
                return json.loads(body) if body else None
        except HTTPError as e:
            try:
                detail = json.loads(e.read().decode("utf-8")).get("detail", str(e))
            except Exception:
                detail = str(e)
            raise RuntimeError(str(detail))
        except URLError as e:
            raise RuntimeError("تعذر الاتصال بالخادم: " + str(e.reason))

    def guarded(self, fn):
        try:
            fn()
        except Exception as e:
            messagebox.showerror("مركز أجهزتي", str(e))

    def _build_login(self):
        box = ttk.Frame(self, padding=28)
        box.pack(fill="both", expand=True)
        ttk.Label(box, text="مركز أجهزتي", font=("Segoe UI", 22, "bold")).pack(pady=(35, 8))
        ttk.Label(box, text="تسجيل الدخول لعميل Windows واستقبال أوامر الحساب", font=("Segoe UI", 11)).pack(pady=8)
        form = ttk.Frame(box)
        form.pack(pady=18)
        ttk.Label(form, text="اسم المستخدم").grid(row=0, column=0, sticky="e", padx=8, pady=8)
        self.username = ttk.Entry(form, width=32)
        self.username.grid(row=0, column=1, pady=8)
        ttk.Label(form, text="كلمة المرور").grid(row=1, column=0, sticky="e", padx=8, pady=8)
        self.password = ttk.Entry(form, width=32, show="•")
        self.password.grid(row=1, column=1, pady=8)
        buttons = ttk.Frame(box)
        buttons.pack(pady=10)
        ttk.Button(buttons, text="دخول", command=lambda: self.guarded(self.login)).pack(side="right", padx=6)
        ttk.Button(buttons, text="إنشاء حساب جديد", command=lambda: self.guarded(self.register)).pack(side="right", padx=6)
        ttk.Label(box, text="استقبال الأوامر لا يتيح التحكم بالشاشة أو الوصول للملفات.", foreground="#a33").pack(pady=14)

    def login(self):
        result = self.call("POST", "/api/auth/login", {
            "username": self.username.get().strip(),
            "password": self.password.get()
        })
        self.token = result["access_token"]
        self.username_value = result.get("user", {}).get("username", self.username.get().strip())
        self._build_dashboard()

    def register(self):
        username = self.username.get().strip()
        password = self.password.get()
        if len(password) < 10:
            raise RuntimeError("كلمة المرور يجب أن تكون 10 أحرف على الأقل.")
        result = self.call("POST", "/api/auth/register", {"username": username, "password": password})
        self.token = result["access_token"]
        self.username_value = result.get("user", {}).get("username", username)
        messagebox.showinfo("تم إنشاء الحساب", "تم إنشاء الحساب. لا تحتاج لإنشاء حساب جديد إذا كان لديك حساب معتمد.")
        self._build_dashboard()

    def _build_dashboard(self):
        for child in self.winfo_children():
            child.destroy()
        top = ttk.Frame(self, padding=12)
        top.pack(fill="x")
        ttk.Label(top, text=f"عميل Windows — الحساب: {self.username_value}", font=("Segoe UI", 16, "bold")).pack(side="right")
        ttk.Button(top, text="تحديث الروابط", command=lambda: self.guarded(self.refresh)).pack(side="left")
        actions = ttk.Frame(self, padding=(12, 0, 12, 8))
        actions.pack(fill="x")
        ttk.Button(actions, text="طلب ربط برمز الهاتف", command=lambda: self.guarded(self.request_link)).pack(side="right", padx=4)
        ttk.Button(actions, text="إلغاء الربط المحدد", command=lambda: self.guarded(self.revoke)).pack(side="right", padx=4)
        self.table = ttk.Treeview(self, columns=("target", "status", "id"), show="headings", height=6)
        self.table.heading("target", text="الحساب الآخر")
        self.table.heading("status", text="حالة الموافقة")
        self.table.heading("id", text="رقم الربط")
        self.table.column("target", width=280)
        self.table.column("status", width=180)
        self.table.column("id", width=100)
        self.table.pack(fill="x", padx=12, pady=8)
        receiver = ttk.LabelFrame(self, text="استقبال أوامر الخادم", padding=12)
        receiver.pack(fill="both", expand=True, padx=12, pady=8)
        ttk.Label(receiver, text="الأوامر المسموحة: /status و/device و/help. يتم الرد بنتيجة عميل Windows فقط، دون تنفيذ أوامر نظام.").pack(anchor="e", pady=(0, 8))
        buttons = ttk.Frame(receiver)
        buttons.pack(fill="x")
        self.receiver_button = ttk.Button(buttons, text="بدء استقبال الأوامر", command=self.toggle_receiver)
        self.receiver_button.pack(side="right")
        self.receiver_status = ttk.Label(buttons, text="متوقف")
        self.receiver_status.pack(side="right", padx=12)
        self.log = tk.Text(receiver, height=9, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, pady=(8, 0))
        self._log("تم تسجيل الدخول. شغّل الاستقبال في الحساب المستهدف فقط.")
        self.refresh()

    def _log(self, text):
        if not hasattr(self, "log") or not self.log.winfo_exists():
            return
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def refresh(self):
        self.links = self.call("GET", "/api/pairings")
        for item in self.table.get_children():
            self.table.delete(item)
        labels = {"pending": "بانتظار الموافقة", "approved": "موافق عليه", "revoked": "ملغى", "rejected": "مرفوض"}
        for link in self.links:
            self.table.insert("", "end", iid=str(link["id"]), values=(
                link.get("target_username", "—"),
                labels.get(link.get("status"), link.get("status", "—")),
                link["id"]
            ))

    def request_link(self):
        code = simpledialog.askstring("ربط هاتف", "أدخل رمز الربط المؤقت الظاهر داخل تطبيق الهاتف:")
        if not code:
            return
        self.call("POST", "/api/pairings", {"code": code.strip()})
        messagebox.showinfo("تم إرسال الطلب", "تم إرسال طلب الربط. يجب أن يوافق صاحب الهاتف من جهازه.")
        self.refresh()

    def revoke(self):
        selection = self.table.selection()
        if not selection:
            messagebox.showinfo("إلغاء الربط", "حدد ربطًا من القائمة أولًا.")
            return
        link_id = int(selection[0])
        if not messagebox.askyesno("تأكيد", "هل تريد إلغاء الربط المحدد؟"):
            return
        self.call("POST", f"/api/pairings/{link_id}/revoke", {})
        messagebox.showinfo("مركز أجهزتي", "تم إلغاء الربط.")
        self.refresh()

    def toggle_receiver(self):
        if self.receiver_running:
            self.stop_receiver()
            return
        self.receiver_running = True
        self.receiver_button.configure(text="إيقاف استقبال الأوامر")
        self.receiver_status.configure(text="يعمل — فحص كل 3 ثوانٍ")
        self._log("بدأ استقبال الأوامر.")
        self._poll_commands()

    def stop_receiver(self):
        self.receiver_running = False
        if self.receiver_after_id is not None:
            try:
                self.after_cancel(self.receiver_after_id)
            except tk.TclError:
                pass
            self.receiver_after_id = None
        if hasattr(self, "receiver_button") and self.receiver_button.winfo_exists():
            self.receiver_button.configure(text="بدء استقبال الأوامر")
            self.receiver_status.configure(text="متوقف")
        self._log("تم إيقاف استقبال الأوامر.")

    def _poll_commands(self):
        if not self.receiver_running:
            return
        try:
            commands = self.call("GET", "/api/commands/pending")
            for item in commands or []:
                self._handle_command(item)
        except Exception as e:
            self._log("تعذر جلب الأوامر: " + str(e))
        if self.receiver_running:
            self.receiver_after_id = self.after(POLL_MS, self._poll_commands)

    def _handle_command(self, item):
        command_id = item.get("id")
        command = str(item.get("command", "")).strip().lower()
        if command not in ALLOWED_COMMANDS:
            result = {"client": "windows", "execution_mode": "windows_client", "message": "الأمر غير مدعوم في عميل Windows.", "command": command}
            status = "failed"
        else:
            status = "completed"
            if command == "/status":
                result = {"client": "windows", "execution_mode": "windows_client", "message": "عميل Windows متصل بالخادم ويستقبل الأوامر.", "account": self.username_value, "physical_device_status": "not_checked"}
            elif command == "/device":
                result = {"client": "windows", "execution_mode": "windows_client", "message": "تم التحقق من جلسة عميل Windows فقط؛ لم يتم فحص هاتف فعلي.", "account": self.username_value, "physical_device_status": "not_checked"}
            else:
                result = {"client": "windows", "execution_mode": "windows_client", "message": "الأوامر المتاحة: /status و/device و/help. لا يدعم هذا العميل التحكم أو تنفيذ أوامر نظام."}
        try:
            self.call("POST", f"/api/commands/{command_id}/result", {"status": status, "result": result})
            self._log(f"الأمر #{command_id} {command}: {status} — أُرسلت النتيجة للخادم.")
        except Exception as e:
            self._log(f"تعذر إرسال نتيجة الأمر #{command_id}: {e}")

    def close_app(self):
        self.stop_receiver()
        self.destroy()


if __name__ == "__main__":
    Client().mainloop()
