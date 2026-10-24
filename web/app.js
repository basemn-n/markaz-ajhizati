const starterDevices = [
  {name:"سامسونج", detail:"Galaxy · غير مربوط"},
  {name:"ريدمي", detail:"Redmi · غير مربوط"},
  {name:"جلاكسي آخر", detail:"Galaxy · غير مربوط"},
  {name:"إلنترا", detail:"الموديل غير محدد · غير مربوط"}
];
const list = document.querySelector("#deviceList");
const dialog = document.querySelector("#addDialog");
const form = document.querySelector("#addForm");
const nameInput = document.querySelector("#deviceName");
const toast = document.querySelector("#toast");
let devices = [];
try { devices = JSON.parse(localStorage.getItem("markaz-devices") || "null") || starterDevices; }
catch { devices = starterDevices; }
function notify(message) { toast.textContent = message; toast.classList.add("show"); window.setTimeout(() => toast.classList.remove("show"), 2600); }
function save() { localStorage.setItem("markaz-devices", JSON.stringify(devices)); renderDevices(); }
function renderDevices() {
  list.replaceChildren();
  devices.forEach((device) => {
    const card = document.createElement("article"); card.className = "device";
    const icon = document.createElement("div"); icon.className = "device-icon"; icon.textContent = "▣";
    const info = document.createElement("div"); info.className = "device-info";
    const title = document.createElement("strong"); title.textContent = device.name;
    const detail = document.createElement("span"); detail.textContent = device.detail;
    const status = document.createElement("span"); status.className = "status"; status.textContent = "● غير متصل";
    info.append(title, detail, status); card.append(icon, info);
    card.addEventListener("click", () => notify("هذا الجهاز محفوظ محليًا فقط؛ الربط الحقيقي سيُضاف في مرحلة الخادم.")); list.append(card);
  });
}
renderDevices();
document.querySelector("#addDeviceBtn").addEventListener("click", () => { nameInput.value = ""; dialog.showModal(); nameInput.focus(); });
form.addEventListener("submit", (event) => {
  if (event.submitter && event.submitter.value === "cancel") return;
  event.preventDefault(); const name = nameInput.value.trim(); if (!name) return;
  devices.push({name, detail:"أُضيف على هذا المتصفح · غير مربوط"}); save(); dialog.close(); notify("تم حفظ اسم الجهاز على هذا المتصفح.");
});
document.querySelectorAll(".tab").forEach((button) => button.addEventListener("click", () => {
  document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b === button));
  document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("hidden", panel.id !== button.dataset.tab));
}));
document.querySelector("#chatForm").addEventListener("submit", (event) => {
  event.preventDefault(); const input = document.querySelector("#chatInput"); const text = input.value.trim(); if (!text) return;
  const item = document.createElement("div"); item.className = "local-message"; item.textContent = text;
  document.querySelector("#localMessages").append(item); input.value = ""; notify("رسالة تجريبية محلية فقط؛ لم تُرسل إلى مستخدم آخر.");
});
let installPrompt = null; const installBtn = document.querySelector("#installBtn");
window.addEventListener("beforeinstallprompt", (event) => { event.preventDefault(); installPrompt = event; installBtn.classList.remove("hidden"); });
installBtn.addEventListener("click", async () => {
  if (!installPrompt) { notify("من قائمة المتصفح اختر «تثبيت التطبيق» أو «إضافة إلى الشاشة الرئيسية»."); return; }
  installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; installBtn.classList.add("hidden");
});
if ("serviceWorker" in navigator && location.protocol.startsWith("http")) window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(() => {}));