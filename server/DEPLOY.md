# نشر خادم مركز أجهزتي على Render

## مهم
الموقع الحالي `markaz-ajhizati.onrender.com` هو Static Site للواجهة فقط. رفع ملفات `server/` إلى GitHub لا ينشئ خدمة API تلقائيًا. أنشئ خدمة Web Service منفصلة قبل توصيل الواجهة بها.

## إعداد الخدمة
1. في Render اختر **New + → Web Service** ثم المستودع `basemn-n/markaz-ajhizati`.
2. الفرع: `main`.
3. **Root Directory:** `server`.
4. **Runtime:** Python 3.
5. **Build Command:** `pip install -r requirements.txt`.
6. **Start Command:** `uvicorn main:app --host 0.0.0.0 --port $PORT`.
7. أضف متغيرات البيئة التالية:
   - `MARKAZ_AUTH_SECRET`: قيمة عشوائية سرية لا تقل عن 32 حرفًا. لا تضعها في GitHub ولا ترسلها في المحادثات.
   - `MARKAZ_WEB_ORIGIN`: `https://markaz-ajhizati.onrender.com`.
   - `MARKAZ_DB_PATH`: `/var/data/markaz.db` إذا أضفت قرصًا دائمًا بمسار تركيب `/var/data`.

## تخزين البيانات
قاعدة SQLite الافتراضية تُنشأ داخل مجلد الخدمة. نظام ملفات Render الافتراضي مؤقت وقد تُفقد البيانات بعد إعادة النشر أو إعادة التشغيل. لا تستخدم الحسابات والرسائل الحقيقية قبل إعداد تخزين دائم؛ للنسخة الإنتاجية يُفضّل استخدام PostgreSQL مُدار. إذا استخدمت SQLite، أضف قرصًا دائمًا إلى خدمة الويب واضبط `MARKAZ_DB_PATH=/var/data/markaz.db`.

## التحقق بعد النشر
- افتح `https://YOUR-API.onrender.com/health` ويُفترض أن يعيد `{"ok":true}`.
- افتح `https://YOUR-API.onrender.com/docs` لمشاهدة مسارات API.
- التسجيل والدخول يعيدان رمز Bearer مدته 12 ساعة. استخدم هذا الرمز في ترويسة `Authorization: Bearer <token>` للمسارات المحمية.
- لا ترسل كلمات مرور أو رسائل خاصة إلى الخدمة قبل ضبط السر والتخزين الدائم والتحقق من إعدادات الأمان.

## ما الذي تغطيه هذه النسخة؟
تحتوي على تسجيل مستخدم باسم مستخدم وكلمة مرور، ورمز وصول موقّع، وعزل الأجهزة والرسائل حسب الحساب. لا تحتوي بعد على ربط جهاز Android حقيقي أو توصيل الواجهة الأمامية بالـ API أو تسليم رسائل فوري عبر WebSocket أو تحديد معدل الطلبات. لا تعتبرها جاهزة للإنتاج قبل اختبارها ومراجعة أمان إضافية.
