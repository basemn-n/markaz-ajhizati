"""
Markaz Ajhizati Windows pairing client (starter).
This client manages authenticated account links only; it does NOT stream or control a phone screen.
Requires Python 3.10+ on Windows. Run: py main.py
"""
import json
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

API = "https://markaz-ajhizati-api.onrender.com"


class Client(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("مركز أجهزتي — Windows")
        self.geometry("760x520")
        self.token = None
        self.links = []
        self._build_login()

    def call(self, method, path, data=None):
        raw = None if data is None else json.dumps(data).encode("utf-8")
        headers = {"Accept": "application/json"}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = Request(API + path, data=raw, headers=headers, method=method)
        try:
            with urlopen(req, timeout=25) as res:
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
        ttk.Label(box, text="تسجيل الدخول لإدارة طلبات ربط الأجهزة", font=("Segoe UI", 11)).pack(pady=8)
        form = ttk.Frame(box)
        form.pack(pady=18)
        ttk.Label(form, text="اسم المستخدم").grid(row=0, column=0, sticky="e", padx=8, pady=8)
        self.username = ttk.Entry(form, width=32)
        self.username.grid(row=0, column=1, pady=8)
        ttk.Label(form, text="كلمة المرور").grid(row=1, column=0, sticky="e", padx=8, pady=8)
        self.password = ttk.Entry(form, width=32, show="•")
        self.password.grid(row=1, column=1, pady=8)
        ttk.Button(box, text="دخول", command=lambda: self.guarded(self.login)).pack(pady=10)
        ttk.Label(box, text="هذا الإصدار يدير الربط فقط؛ مشاركة الشاشة والتحكم غير متاحين بعد.",
                  foreground="#a33").pack(pady=14)

    def login(self):
        result = self.call("POST", "/api/auth/login", {
            "username": self.username.get().strip(),
            "password": self.password.get()
        })
        self.token = result["access_token"]
        self._build_dashboard()

    def _build_dashboard(self):
        for child in self.winfo_children():
            child.destroy()
        top = ttk.Frame(self, padding=12)
        top.pack(fill="x")
        ttk.Label(top, text="الأجهزة المرتبطة", font=("Segoe UI", 18, "bold")).pack(side="right")
        ttk.Button(top, text="تحديث", command=lambda: self.guarded(self.refresh)).pack(side="left")
        actions = ttk.Frame(self, padding=(12, 0, 12, 8))
        actions.pack(fill="x")
        ttk.Button(actions, text="طلب ربط برمز الهاتف", command=lambda: self.guarded(self.request_link)).pack(side="right", padx=4)
        ttk.Button(actions, text="إلغاء الربط المحدد", command=lambda: self.guarded(self.revoke)).pack(side="right", padx=4)
        self.table = ttk.Treeview(self, columns=("target", "status", "id"), show="headings", height=12)
        self.table.heading("target", text="اسم حساب الهاتف")
        self.table.heading("status", text="حالة الموافقة")
        self.table.heading("id", text="رقم الربط")
        self.table.column("target", width=280)
        self.table.column("status", width=180)
        self.table.column("id", width=100)
        self.table.pack(fill="both", expand=True, padx=12, pady=8)
        ttk.Label(self, text="تنبيه: الموافقة على الربط لا تعني أن عرض الشاشة أو التحكم متاح.",
                  foreground="#a33").pack(pady=(0, 12))
        self.refresh()

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
        result = self.call("POST", "/api/pairings", {"code": code.strip()})
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
        result = self.call("POST", f"/api/pairings/{link_id}/revoke", {})
        messagebox.showinfo("مركز أجهزتي", "تم إلغاء الربط.")
        self.refresh()


if __name__ == "__main__":
    Client().mainloop()
