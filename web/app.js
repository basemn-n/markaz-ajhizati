const API_BASE = "https://markaz-ajhizati-api.onrender.com";
const authPanel = document.querySelector("#authPanel");
const appPanel = document.querySelector("#appPanel");
const authForm = document.querySelector("#authForm");
const authTitle = document.querySelector("#authTitle");
const authSubmit = document.querySelector("#authSubmit");
const authNotice = document.querySelector("#authNotice");
const usernameInput = document.querySelector("#username");
const passwordInput = document.querySelector("#password");
const logoutBtn = document.querySelector("#logoutBtn");
const toast = document.querySelector("#toast");
let authMode = "login";
let token = sessionStorage.getItem("markaz-token") || "";
let currentUser = null;

function notify(message) {
  toast.textContent = message;
  toast.classList.add("show");
  window.setTimeout(() => toast.classList.remove("show"), 3000);
}
function showAuthNotice(message) {
  authNotice.textContent = message;
  authNotice.classList.remove("hidden");
}
function clearAuthNotice() {
  authNotice.textContent = "";
  authNotice.classList.add("hidden");
}
function setAuthMode(mode) {
  authMode = mode;
  const registering = mode === "register";
  authTitle.textContent = registering ? "إنشاء حساب جديد" : "تسجيل الدخول";
  authSubmit.textContent = registering ? "إنشاء الحساب" : "تسجيل الدخول";
  passwordInput.autocomplete = registering ? "new-password" : "current-password";
  passwordInput.minLength = registering ? 10 : 1;
  document.querySelector("#loginModeBtn").classList.toggle("active", !registering);
  document.querySelector("#registerModeBtn").classList.toggle("active", registering);
  clearAuthNotice();
}
async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (token) headers.Authorization = "Bearer " + token;
  const response = await fetch(API_BASE + path, { ...options, headers });
  let data = {};
  try { data = await response.json(); } catch {}
  if (!response.ok) {
    const raw = data.detail;
    let detail = "تعذّر إكمال الطلب. حاول مرة أخرى.";
    if (typeof raw === "string") {
      const known = {
        "Username is already registered": "اسم المستخدم مسجّل من قبل. اختر اسمًا آخر.",
        "Incorrect username or password": "اسم المستخدم أو كلمة المرور غير صحيحة.",
        "Server authentication is not configured. Set MARKAZ_AUTH_SECRET to a random secret of at least 32 characters.": "إعدادات أمان الخادم غير مكتملة؛ يلزم ضبط MARKAZ_AUTH_SECRET في Render.",
        "Bearer access token required": "انتهت الجلسة أو لم يتم تسجيل الدخول. سجّل الدخول مجددًا."
      };
      detail = known[raw] || raw;
    } else if (Array.isArray(raw)) {
      const errors = raw.map((item) => {
        const field = Array.isArray(item.loc) ? item.loc[item.loc.length - 1] : "";
        if (field === "username" && item.type === "string_pattern_mismatch") return "اسم المستخدم يجب أن يكون بالإنجليزية، ويقبل الحروف والأرقام و . _ - فقط.";
        if (field === "username" && item.type === "string_too_short") return "اسم المستخدم يجب أن يتكوّن من 3 أحرف على الأقل.";
        if (field === "username" && item.type === "string_too_long") return "اسم المستخدم طويل جدًا؛ الحد الأقصى 32 حرفًا.";
        if (field === "password" && item.type === "string_too_short") return "كلمة المرور يجب أن تتكوّن من 10 أحرف على الأقل.";
        if (field === "password" && item.type === "string_too_long") return "كلمة المرور طويلة جدًا؛ الحد الأقصى 128 حرفًا.";
        return "";
      }).filter(Boolean);
      if (errors.length) detail = [...new Set(errors)].join(" ");
    }
    throw new Error(detail);
  }
  return data;
}
function showSignedIn(user) {
  currentUser = user;
  authPanel.classList.add("hidden");
  appPanel.classList.remove("hidden");
  logoutBtn.classList.remove("hidden");
  document.querySelector("#currentUsername").textContent = user.username;
  startPresenceHeartbeat();
  refreshAll();
}
function showSignedOut() {
  stopPresenceHeartbeat();
  token = "";
  currentUser = null;
  sessionStorage.removeItem("markaz-token");
  authPanel.classList.remove("hidden");
  appPanel.classList.add("hidden");
  logoutBtn.classList.add("hidden");
}
authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  clearAuthNotice();
  const username = usernameInput.value.trim();
  const password = passwordInput.value;
  authSubmit.disabled = true;
  try {
    const path = authMode === "register" ? "/api/auth/register" : "/api/auth/login";
    const data = await api(path, { method: "POST", body: JSON.stringify({ username, password }) });
    token = data.access_token;
    sessionStorage.setItem("markaz-token", token);
    passwordInput.value = "";
    showSignedIn(data.user);
    notify(authMode === "register" ? "تم إنشاء الحساب وتسجيل الدخول." : "تم تسجيل الدخول.");
  } catch (error) {
    const message = error instanceof TypeError
      ? "تعذّر الاتصال بالخادم. انتظر قليلًا ثم أعد المحاولة؛ إذا استمرت المشكلة فسنفحص خدمة API على Render."
      : error.message;
    showAuthNotice(message);
  } finally {
    authSubmit.disabled = false;
  }
});
document.querySelector("#loginModeBtn").addEventListener("click", () => setAuthMode("login"));
document.querySelector("#registerModeBtn").addEventListener("click", () => setAuthMode("register"));
logoutBtn.addEventListener("click", () => { showSignedOut(); notify("تم تسجيل الخروج."); });

let presenceTimer = null;
let presenceInFlight = false;
async function sendPresenceHeartbeat() {
  if (!token || document.visibilityState === "hidden" || presenceInFlight) return;
  presenceInFlight = true;
  try { await api("/api/presence/heartbeat", { method: "POST" }); }
  catch (error) {
    if (!window.__presenceErrorShown) {
      window.__presenceErrorShown = true;
      notify("تعذّر تسجيل نشاط التطبيق: " + (error instanceof TypeError ? "تعذّر الاتصال بالخادم أو منع المتصفح الاتصال به." : error.message));
    }
  }
  finally { presenceInFlight = false; }
}
function startPresenceHeartbeat() {
  stopPresenceHeartbeat();
  sendPresenceHeartbeat();
  presenceTimer = window.setInterval(sendPresenceHeartbeat, 30000);
}
function stopPresenceHeartbeat() {
  if (presenceTimer !== null) window.clearInterval(presenceTimer);
  presenceTimer = null;
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && token) sendPresenceHeartbeat();
});
async function refreshAll() {
  await Promise.allSettled([refreshLinks(), refreshIncoming()]);
}
function emptyMessage(container, message) {
  const p = document.createElement("p");
  p.className = "muted";
  p.textContent = message;
  container.append(p);
}
function statusArabic(status) {
  return ({pending:"بانتظار موافقة صاحب الهاتف", approved:"تمت الموافقة على الربط", rejected:"تم رفض الطلب", revoked:"تم إلغاء الربط"})[status] || status;
}
function renderLinkList(items) {
  const list = document.querySelector("#linkList");
  list.replaceChildren();
  if (!items.length) return emptyMessage(list, "لا توجد أجهزة مرتبطة حتى الآن.");
  items.forEach((item) => {
    const card = document.createElement("article");
    card.className = "device";
    const icon = document.createElement("div");
    icon.className = "device-icon";
    icon.textContent = "▣";
    const info = document.createElement("div");
    info.className = "device-info";
    const title = document.createElement("strong");
    title.textContent = item.target_username || "جهاز مرتبط";
    const status = document.createElement("span");
    status.textContent = statusArabic(item.status);
    info.append(title, status);
    if (item.status === "approved") {
      const presence = document.createElement("span");
      presence.className = "presence-status " + (item.session_status === "active" ? "is-active" : "is-inactive");
      presence.textContent = item.session_status === "active"
        ? "جلسة الموقع نشطة الآن — لا تؤكد اتصال تطبيق أندرويد"
        : (item.last_seen_at ? "آخر نشاط للموقع: " + new Date(item.last_seen_at).toLocaleString("ar") + " — اتصال أندرويد غير مؤكد" : "لم يسجل الموقع أو تطبيق أندرويد نشاطًا بعد");
      info.append(presence);
    }
    card.append(icon, info);
    if (item.status === "approved") {
      const revoke = document.createElement("button");
      revoke.className = "secondary";
      revoke.type = "button";
      revoke.textContent = "إلغاء";
      revoke.addEventListener("click", async () => {
        if (!window.confirm("هل تريد إلغاء الربط؟")) return;
        try {
          await api("/api/pairings/" + item.id + "/revoke", { method: "POST" });
          notify("تم إلغاء الربط.");
          refreshAll();
        } catch (error) { notify(error.message); }
      });
      card.append(revoke);
    }
    list.append(card);
  });
}
async function refreshLinks() {
  const list = document.querySelector("#linkList");
  try {
    const items = await api("/api/pairings");
    renderLinkList(items);
  } catch (error) {
    list.replaceChildren();
    emptyMessage(list, error.message);
  }
}
async function refreshIncoming() {
  const list = document.querySelector("#incomingList");
  try {
    const items = await api("/api/pairings/incoming");
    list.replaceChildren();
    if (!items.length) return emptyMessage(list, "لا توجد طلبات ربط تنتظر موافقتك.");
    items.forEach((item) => {
      const card = document.createElement("article");
      card.className = "device";
      const icon = document.createElement("div");
      icon.className = "device-icon";
      icon.textContent = "↔";
      const info = document.createElement("div");
      info.className = "device-info";
      const title = document.createElement("strong");
      title.textContent = item.controller_username;
      const status = document.createElement("span");
      status.textContent = "يريد ربط حسابه بجهازك";
      info.append(title, status);
      const actions = document.createElement("div");
      actions.style.display = "flex";
      actions.style.gap = "6px";
      const approve = document.createElement("button");
      approve.className = "primary";
      approve.type = "button";
      approve.textContent = "موافقة";
      const reject = document.createElement("button");
      reject.className = "secondary";
      reject.type = "button";
      reject.textContent = "رفض";
      async function decide(approved) {
        approve.disabled = true;
        reject.disabled = true;
        try {
          await api("/api/pairings/" + item.id + "/decision", {method:"POST", body:JSON.stringify({approved})});
          notify(approved ? "وافقت على طلب الربط." : "تم رفض طلب الربط.");
          await refreshAll();
        } catch (error) {
          notify(error.message);
          approve.disabled = false;
          reject.disabled = false;
        }
      }
      approve.addEventListener("click", () => decide(true));
      reject.addEventListener("click", () => decide(false));
      actions.append(approve, reject);
      card.append(icon, info, actions);
      list.append(card);
    });
  } catch (error) {
    list.replaceChildren();
    emptyMessage(list, error.message);
  }
}
document.querySelector("#createCodeBtn").addEventListener("click", async () => {
  const result = document.querySelector("#pairCodeResult");
  result.classList.remove("hidden");
  result.textContent = "جارٍ إنشاء الرمز…";
  try {
    const data = await api("/api/pairing-codes", {method:"POST"});
    result.textContent = "رمز الربط: " + data.pairing_code + " — صالح حتى " + new Date(data.expires_at).toLocaleTimeString("ar", {hour:"2-digit", minute:"2-digit"}) + ". شاركه مع الحساب الذي تريد السماح له بطلب الربط.";
  } catch (error) {
    result.textContent = error.message;
  }
});
document.querySelector("#pairForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const input = document.querySelector("#pairCodeInput");
  try {
    const data = await api("/api/pairings", {method:"POST", body:JSON.stringify({code:input.value.trim()})});
    input.value = "";
    notify(data.message || "تم إرسال طلب الربط.");
    await refreshLinks();
  } catch (error) { notify(error.message); }
});
document.querySelector("#refreshLinksBtn").addEventListener("click", refreshLinks);
document.querySelector("#refreshRequestsBtn").addEventListener("click", refreshIncoming);

function screenStatusArabic(status) {
  return ({requested:"بانتظار الموافقة", accepted:"تمت الموافقة — البث غير متصل بعد", active:"جلسة نشطة", rejected:"تم الرفض", stopped:"تم الإيقاف", expired:"انتهت الجلسة"})[status] || status;
}
function showScreenNotice(message) {
  const box = document.querySelector("#screenNotice");
  box.textContent = message;
  box.classList.remove("hidden");
}
async function refreshScreenTargets() {
  const select = document.querySelector("#screenTargetSelect");
  const previous = select.value;
  const items = await api("/api/pairings");
  const approved = items.filter(item => item.status === "approved");
  select.replaceChildren();
  if (!approved.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "لا توجد حسابات مرتبطة وموافق عليها";
    select.append(option);
    select.disabled = true;
    document.querySelector("#screenRequestBtn").disabled = true;
    return;
  }
  select.disabled = false;
  document.querySelector("#screenRequestBtn").disabled = false;
  approved.forEach(item => {
    const option = document.createElement("option");
    option.value = String(item.target_user_id);
    option.textContent = item.target_username || "حساب مرتبط";
    select.append(option);
  });
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
}
async function refreshScreenSessions() {
  const list = document.querySelector("#screenSessionList");
  list.replaceChildren();
  try {
    const items = await api("/api/screen-sessions");
    if (!items.length) return emptyMessage(list, "لا توجد طلبات مشاركة شاشة حاليًا.");
    items.forEach(item => {
      const card = document.createElement("article");
      card.className = "device";
      const icon = document.createElement("div");
      icon.className = "device-icon";
      icon.textContent = "▣";
      const info = document.createElement("div");
      info.className = "device-info";
      const title = document.createElement("strong");
      title.textContent = "مشاركة شاشة مع: " + (Number(item.controller_id) === Number(currentUser?.id) ? (item.target_username || "حساب مرتبط") : (item.controller_username || "حساب مرتبط"));
      const status = document.createElement("span");
      status.textContent = screenStatusArabic(item.status);
      info.append(title, status);
      card.append(icon, info);
      if (item.status === "requested" && Number(item.target_id) === Number(currentUser?.id)) {
        const actions = document.createElement("div");
        actions.style.display = "flex";
        actions.style.gap = "6px";
        [true, false].forEach(accepted => {
          const button = document.createElement("button");
          button.className = accepted ? "primary" : "secondary";
          button.type = "button";
          button.textContent = accepted ? "موافقة" : "رفض";
          button.addEventListener("click", async () => {
            button.disabled = true;
            try {
              await api("/api/screen-sessions/" + item.id + "/decision", {method:"POST", body:JSON.stringify({accepted})});
              notify(accepted ? "سُجلت الموافقة؛ البث الفعلي غير متصل بعد." : "تم رفض طلب مشاركة الشاشة.");
              await refreshScreenAll();
            } catch (error) { notify(error.message); button.disabled = false; }
          });
          actions.append(button);
        });
        card.append(actions);
      }
      if (["requested", "accepted", "active"].includes(item.status)) {
        const stop = document.createElement("button");
        stop.className = "secondary";
        stop.type = "button";
        stop.textContent = "إيقاف الطلب";
        stop.addEventListener("click", async () => {
          try {
            await api("/api/screen-sessions/" + item.id + "/stop", {method:"POST"});
            notify("تم إيقاف الطلب.");
            await refreshScreenAll();
          } catch (error) { notify(error.message); }
        });
        card.append(stop);
      }
      list.append(card);
    });
  } catch (error) { emptyMessage(list, error.message); }
}
async function refreshScreenAll() {
  await Promise.allSettled([refreshScreenTargets(), refreshScreenSessions()]);
}
document.querySelector("#screenRequestForm").addEventListener("submit", async event => {
  event.preventDefault();
  const target = Number(document.querySelector("#screenTargetSelect").value);
  if (!Number.isInteger(target) || target <= 0) return showScreenNotice("اختر حسابًا مرتبطًا أولًا.");
  const button = document.querySelector("#screenRequestBtn");
  button.disabled = true;
  try {
    await api("/api/screen-sessions", {method:"POST", body:JSON.stringify({target_user_id:target})});
    showScreenNotice("تم إرسال الطلب. يلزم قبول الطرف الآخر؛ البث الفعلي غير متصل بعد.");
    await refreshScreenAll();
  } catch (error) { showScreenNotice(error.message); }
  finally { button.disabled = false; }
});
document.querySelector("#refreshScreenBtn").addEventListener("click", refreshScreenAll);

// Keep incoming screen-share requests visible on the target account without requiring a manual refresh.
let screenAutoRefreshTimer = null;
function startScreenAutoRefresh() {
  if (screenAutoRefreshTimer !== null) window.clearInterval(screenAutoRefreshTimer);
  screenAutoRefreshTimer = window.setInterval(() => {
    if (currentUser && !document.hidden) {
      const panel = document.querySelector("#screen");
      if (panel && !panel.classList.contains("hidden")) refreshScreenSessions();
    }
  }, 5000);
}
startScreenAutoRefresh();
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && currentUser) {
    const panel = document.querySelector("#screen");
    if (panel && !panel.classList.contains("hidden")) refreshScreenAll();
  }
});

let browserReceiverTimer = null;
let browserReceiverBusy = false;
function browserReceiverNotice(message) {
  const box = document.querySelector("#browserReceiverNotice");
  if (!box) return;
  box.textContent = message;
  box.classList.remove("hidden");
}
function stopBrowserReceiver() {
  if (browserReceiverTimer !== null) window.clearInterval(browserReceiverTimer);
  browserReceiverTimer = null;
  const button = document.querySelector("#browserReceiverToggle");
  if (button) button.textContent = "بدء استقبال الاختباري";
  browserReceiverNotice("الاستقبال الاختباري متوقف.");
}
async function pollBrowserReceiver() {
  if (!currentUser || !token || browserReceiverBusy || document.hidden) return;
  browserReceiverBusy = true;
  try {
    const commands = await api("/api/commands/pending");
    if (!commands.length) {
      browserReceiverNotice("الاستقبال الاختباري يعمل — لا توجد أوامر جديدة للحساب " + currentUser.username + ".");
      return;
    }
    for (const command of commands) {
      let result;
      if (command.command === "/status") {
        result = {simulation:true, execution_mode:"browser_simulation", message:"تم استلام الأمر في جلسة المتصفح فقط.", account:currentUser.username, browser_session:"active", physical_device_status:"not_checked"};
      } else if (command.command === "/device") {
        result = {simulation:true, execution_mode:"browser_simulation", message:"هذه بيانات جلسة الويب وليست بيانات هاتف فعلي.", account:currentUser.username, client:"web-browser", physical_device_info:"not_available"};
      } else if (command.command === "/help") {
        result = {simulation:true, execution_mode:"browser_simulation", supported_commands:["/status","/device","/help"], message:"محاكاة استقبال الأوامر في المتصفح فقط."};
      } else {
        await api("/api/commands/" + command.id + "/result", {method:"POST", body:JSON.stringify({status:"failed", result:{simulation:true, execution_mode:"browser_simulation", error:"Unsupported command"}})});
        continue;
      }
      await api("/api/commands/" + command.id + "/result", {method:"POST", body:JSON.stringify({status:"completed", result})});
      browserReceiverNotice("استُلم الأمر رقم " + command.id + " (" + command.command + ") وأُرسلت نتيجة محاكاة إلى الخادم. لا يعني ذلك تنفيذًا على هاتف فعلي.");
    }
    await refreshCommandList();
  } catch (error) {
    browserReceiverNotice("تعذّر استقبال الأمر: " + (error.message || "خطأ غير معروف"));
  } finally {
    browserReceiverBusy = false;
  }
}
document.querySelector("#browserReceiverToggle").addEventListener("click", () => {
  if (browserReceiverTimer !== null) {
    stopBrowserReceiver();
    return;
  }
  browserReceiverNotice("جارٍ تشغيل الاستقبال الاختباري لهذه الجلسة...");
  document.querySelector("#browserReceiverToggle").textContent = "إيقاف الاستقبال الاختباري";
  pollBrowserReceiver();
  browserReceiverTimer = window.setInterval(pollBrowserReceiver, 3000);
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && browserReceiverTimer !== null) pollBrowserReceiver();
});
window.addEventListener("pagehide", stopBrowserReceiver);

function commandStatusArabic(item) {
  const status = item?.status || item;
  const labels = {
    pending: "لم يستلمه أي عميل بعد — في قائمة الانتظار",
    claimed: "استلمه عميل — ننتظر النتيجة",
    completed: "أرسل العميل نتيجة مكتملة",
    failed: "أرسل العميل نتيجة فشل",
    expired: "انتهت صلاحية الأمر دون نتيجة"
  };
  const label = labels[status] || status || "حالة غير معروفة";
  if (item && typeof item === "object" && item.result?.simulation === true) {
    return label + " — نتيجة محاكاة من المتصفح وليست من الهاتف";
  }
  return label;
}
function showCommandNotice(message) {
  const box = document.querySelector("#commandNotice");
  box.textContent = message;
  box.classList.remove("hidden");
}
async function refreshCommandTargets() {
  const select = document.querySelector("#commandTargetSelect");
  const previous = select.value;
  const items = await api("/api/pairings");
  const approved = items.filter(item => item.status === "approved");
  select.replaceChildren();
  approved.forEach(item => {
    const option = document.createElement("option");
    option.value = String(item.target_user_id);
    option.textContent = item.target_username || "حساب مرتبط";
    select.append(option);
  });
  if (!approved.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "لا توجد أجهزة مرتبطة وموافق عليها";
    select.append(option);
    select.disabled = true;
    document.querySelector("#sendCommandBtn").disabled = true;
    return;
  }
  select.disabled = false;
  document.querySelector("#sendCommandBtn").disabled = false;
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
}
let commandListRefreshPromise = null;
async function refreshCommandList() {
  // Keep the currently displayed history visible while the request is in flight.
  // Reuse an active request to prevent overlapping refreshes from racing.
  if (commandListRefreshPromise) return commandListRefreshPromise;
  const list = document.querySelector("#commandList");
  commandListRefreshPromise = (async () => {
    try {
      const items = await api("/api/commands?limit=50");
      const fragment = document.createDocumentFragment();
      if (!items.length) {
        const message = document.createElement("p");
        message.className = "muted";
        message.textContent = "لا توجد أوامر مسجلة حتى الآن.";
        fragment.append(message);
      } else {
        items.forEach(item => {
          const card = document.createElement("article");
          card.className = "device";
          const icon = document.createElement("div");
          icon.className = "device-icon";
          icon.textContent = "⌘";
          const info = document.createElement("div");
          info.className = "device-info";
          const title = document.createElement("strong");
          title.textContent = (item.command || "أمر") + " — " + (item.target_username || "الجهاز");
          const status = document.createElement("span");
          status.textContent = commandStatusArabic(item);
          const created = document.createElement("span");
          created.className = "muted";
          created.textContent = "وقت الإرسال: " + (item.created_at ? new Date(item.created_at).toLocaleString("ar") : "غير متاح");
          info.append(title, status, created);
          if (item.expires_at) {
            const expiry = document.createElement("span");
            expiry.className = "muted";
            expiry.textContent = "تنتهي الصلاحية: " + new Date(item.expires_at).toLocaleString("ar");
            info.append(expiry);
          }
          if (item.result && typeof item.result === "object") {
            const result = document.createElement("pre");
            result.className = "muted";
            result.style.whiteSpace = "pre-wrap";
            result.style.overflowWrap = "anywhere";
            result.textContent = "نتيجة العميل: " + JSON.stringify(item.result, null, 2);
            info.append(result);
          }
          card.append(icon, info);
          fragment.append(card);
        });
      }
      // Replace the visible list only after a successful response is fully rendered.
      list.replaceChildren(fragment);
    } catch (error) {
      // Preserve the last known history instead of blanking it on a transient failure.
      if (!list.children.length) {
        emptyMessage(list, error.message || "تعذّر تحميل سجل الأوامر.");
      } else {
        showCommandNotice("تعذّر تحديث سجل الأوامر؛ ما زال السجل السابق ظاهرًا. " + (error.message || ""));
      }
    } finally {
      commandListRefreshPromise = null;
    }
  })();
  return commandListRefreshPromise;
}
async function refreshCommandsAll() {
  try { await refreshCommandTargets(); }
  catch (error) { showCommandNotice(error.message || "تعذّر تحميل الأجهزة المرتبطة."); }
  await refreshCommandList();
}
document.querySelector("#commandForm").addEventListener("submit", async event => {
  event.preventDefault();
  const target = Number(document.querySelector("#commandTargetSelect").value);
  const command = document.querySelector("#commandSelect").value;
  const button = document.querySelector("#sendCommandBtn");
  if (!Number.isInteger(target) || target <= 0) return showCommandNotice("اختر جهازًا مرتبطًا وموافقًا عليه أولًا.");
  button.disabled = true;
  try {
    const data = await api("/api/commands", {method:"POST", body:JSON.stringify({target_user_id:target, command})});
    showCommandNotice("تم تسجيل الأمر رقم " + data.id + " وإرساله إلى قائمة الانتظار. هذا لا يؤكد التنفيذ؛ ينتظر استلام العميل.");
    await refreshCommandList();
  } catch (error) {
    showCommandNotice(error.message || "تعذّر إرسال الأمر.");
  } finally {
    button.disabled = false;
    refreshCommandTargets().catch(() => {});
  }
});
document.querySelector("#refreshCommandsBtn").addEventListener("click", refreshCommandsAll);
let commandAutoRefreshTimer = null;
function startCommandAutoRefresh() {
  if (commandAutoRefreshTimer !== null) window.clearInterval(commandAutoRefreshTimer);
  commandAutoRefreshTimer = window.setInterval(() => {
    const panel = document.querySelector("#commands");
    if (currentUser && !document.hidden && panel && !panel.classList.contains("hidden")) refreshCommandList();
  }, 5000);
}
startCommandAutoRefresh();

document.querySelectorAll(".tab[data-tab]").forEach((button) => button.addEventListener("click", () => {
  document.querySelectorAll(".tab[data-tab]").forEach((b) => b.classList.toggle("active", b === button));
  document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("hidden", panel.id !== button.dataset.tab));
  if (button.dataset.tab === "screen") refreshScreenAll();
  if (button.dataset.tab === "commands") refreshCommandsAll();
}));
let installPrompt = null;
const installBtn = document.querySelector("#installBtn");
window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  installPrompt = event;
  installBtn.classList.remove("hidden");
});
installBtn.addEventListener("click", async () => {
  if (!installPrompt) {
    notify("من قائمة المتصفح اختر «تثبيت التطبيق» أو «إضافة إلى الشاشة الرئيسية».");
    return;
  }
  installPrompt.prompt();
  await installPrompt.userChoice;
  installPrompt = null;
  installBtn.classList.add("hidden");
});
if ("serviceWorker" in navigator && location.protocol.startsWith("http")) {
  window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(() => {}));
}
async function start() {
  if (!token) return;
  try {
    const user = await api("/api/auth/me");
    showSignedIn(user);
  } catch {
    showSignedOut();
  }
}
start();
