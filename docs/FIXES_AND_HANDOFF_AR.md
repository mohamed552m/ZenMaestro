# Zen Maestro / CoachAI — تقرير الإصلاحات وتسليم المشروع

> آخر تحديث: 2026-11-24. هذا المستند يصف ما هو موجود في نسخة العمل الحالية فعلًا، وما تم التحقق منه، وما لا يزال يحتاج اختبارًا أو تجهيز Production. لا يحتوي على مفاتيح أو كلمات مرور أو بيانات حسابات.

## 1. وصف المشروع والحالة الحالية

Zen Maestro هو تطبيق Android لإدارة اليوم والمهام والتخطيط بمساعدة الذكاء الاصطناعي. تطبيق Android مكتوب بـ **Kotlin + Jetpack Compose**، والباك إند مكتوب بـ **FastAPI/Python**، والبيانات محفوظة في **PostgreSQL**، وتسجيل الدخول يعتمد على **Firebase Authentication**، وخصائص الذكاء الاصطناعي تعتمد على **Gemini**.

- المستودع: `https://github.com/YousefElmasry1/CoachAI`
- الفرع وقت التسليم: `main`
- نقطة الأساس التي بدأ منها العمل: `3f4e26a`
- Application ID: `com.zenmaestro.app`
- Android وFastAPI يعملان محليًا حاليًا.
- Android Debug يستخدم `http://127.0.0.1:8000` مع `adb reverse tcp:8000 tcp:8000`.
- لا يوجد نشر حالي على Render أو Neon، ولا يوجد Backend Production URL.
- Home وAnalytics يعرضان HTML داخل WebView في Compose، بينما بقية الشاشات Native Compose.

هذه النسخة **نسخة تطوير محلية ناجحة في Verification Gate، وليست Production release**.

## 2. المشاكل الأصلية والإصلاحات المنفذة

### 2.1 واجهات تسجيل الدخول

**المشكلة الأصلية**

- Login وCreate Account وForgot Password لم تكن مكتملة أو مطابقة للتصميم البرتقالي/الكحلي المطلوب.
- تسلسل إنشاء الحساب كان يمكن أن يعتبر الجلسة جاهزة قبل اكتمال حفظ اسم المستخدم.

**الإصلاح المنفذ**

- إعادة بناء تدفقات المصادقة بواجهات Compose والهوية البصرية المطلوبة.
- إضافة التحقق من الحقول وحالات loading/error والتنقل بين Login وCreate Account وForgot Password.
- جعل إنشاء الحساب ينتظر حفظ الاسم قبل اعتبار التدفق مكتملًا.

**أهم الملفات**

- `android/app/src/main/java/com/coachai/android/ui/auth/AuthScreen.kt`
- `android/app/src/main/java/com/coachai/android/ui/auth/AuthViewModel.kt`

### 2.2 الرجوع إلى Login بعد فتح Home

**السبب**

- بعض طلبات Android كانت تستخدم Firebase ID Token مرفوضًا أو منتهيًا.
- لم يكن هناك مسار مشترك وآمن لتحديث التوكن وإعادة الطلب.
- عدة شاشات قد تفشل في الوقت نفسه وتحاول تحديث التوكن بالتوازي.

**الإصلاح المنفذ**

- إضافة `AuthenticatedRequest.kt` كمسار مشترك للطلبات الموثقة.
- عند `401 Unauthorized` يتم force-refresh لـFirebase ID Token ثم إعادة نفس الطلب **مرة واحدة فقط**.
- محاولات refresh المتزامنة لنفس التوكن تشترك في نتيجة refresh واحدة.
- إذا فشل refresh أو رجع الطلب الثاني `401`، تُبطل الجلسة بدل الدخول في loop.
- طلبات التعديل تحافظ على body وidempotency key نفسيهما عند إعادة المحاولة.

**الفرق بين `401` و`403` حاليًا**

- `401`: التوثيق غير صالح أو انتهى؛ Android يحاول refresh وإعادة الطلب مرة واحدة.
- `403`: المستخدم موثّق لكن غير مسموح له بالعملية؛ **لا يحدث refresh ولا retry تلقائي**.

**أهم الملفات**

- `android/app/src/main/java/com/coachai/android/data/remote/AuthenticatedRequest.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/AuthTokenProvider.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/FirebaseAuthTokenProvider.kt`
- Repositories وremote APIs داخل `android/app/src/main/java/com/coachai/android/data/`
- `android/app/src/test/java/com/coachai/android/data/remote/AuthenticatedRequestTest.kt`

### 2.3 Firebase Authentication وGoogle Sign-In

**الإصلاحات المنفذة**

- ربط Email/Password Auth بتطبيق Android.
- إرسال Firebase ID Token في `Authorization: Bearer ...` لكل endpoint خاص.
- تحقق الباك إند من التوكن واستخراج الهوية الموثوقة منه بدل قبول user ID من الواجهة.
- إضافة clock-skew محدود لعملية تحقق Firebase.
- إعداد Google Credential Manager وGoogle ID Token flow في Android.
- استخدام ملف `android/app/google-services.json` محلي ومتجاهل من Git.

**ما تم التحقق منه**

- Email/Password end-to-end محليًا: Firebase أصدر ID Token و`GET /v1/profile` أعاد `200`.
- Google Sign-In وصل إلى Android Account Chooser بدون configuration/developer error.

**غير المؤكد**

- اختيار حساب Google وإكمال تسجيل الدخول end-to-end لم يُختبر.
- reset-link domain وRelease SHA fingerprints يحتاجان تحققًا قبل الإنتاج.

**أهم الملفات**

- `identity.py`
- `android/app/src/main/java/com/coachai/android/ui/auth/AuthScreen.kt`
- `android/app/src/main/java/com/coachai/android/ui/auth/AuthViewModel.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/FirebaseAuthTokenProvider.kt`
- `tests/test_backend_auth.py`

### 2.4 Firebase UID وعزل بيانات المستخدمين

**المشكلة الأصلية**

- كان يلزم منع الواجهة أو أي request من اختيار هوية مستخدم يدويًا.
- التحميل المتوازي لأول تسجيل دخول كان يمكن أن يحاول إنشاء المستخدم أكثر من مرة.
- بعض حالات Android كان يجب ربطها بالمستخدم الحالي حتى لا تبقى حالة حساب سابق.

**الإصلاح المنفذ**

- Firebase UID المأخوذ من التوكن الموثّق هو مصدر هوية المستخدم.
- معالجة race condition في first-login provisioning.
- تطبيق ownership checks على الخطط والمهام والإشعارات وCalendar operations.
- ربط ViewModels/navigation state بالمستخدم الحالي.
- إضافة اختبارات tenant isolation ومنع القراءة أو التعديل عبر حساب آخر.

**الحالة**

- العزل مؤكد باختبارات Backend الآلية.
- اختبار يدوي end-to-end بحسابين حقيقيين على Android ما زال مطلوبًا.

**أهم الملفات**

- `identity.py`
- `database.py`
- `unit_of_work.py`
- `tests/test_tenant_integrity.py`
- `tests/test_mobile_sync.py`

### 2.5 PostgreSQL والـMigrations

**الإصلاح المنفذ**

- تشغيل PostgreSQL محلي وربط FastAPI عبر `DATABASE_URL`.
- استخدام SQLAlchemy وAlembic للـschema والمigrations.
- قاعدة التطوير المحلية الحالية اسمها `coachai`.
- إضافة وفحص قيود ownership وuniqueness والتعامل الآمن مع المعاملات.

**الحالة المؤكدة**

- PostgreSQL المحلي يعمل.
- Alembic current: `20261009_0002`.
- Alembic head: `20261009_0002`.
- لا توجد قاعدة Neon أو Production database حاليًا.

**أهم الملفات**

- `database.py`
- `migrations/`
- `alembic.ini`
- `tests/test_migrations.py`
- `tests/test_tenant_integrity.py`

### 2.6 Gemini وAI Provider

**المشاكل الأصلية**

- إعداد الموديل والـfallback لم يكن موحدًا بين Ask Coach وPlanner وVoice.
- أخطاء الشبكة أو quota/provider كانت قد تظهر بصورة غير مفهومة.
- كان يجب منع تسريب تفاصيل المزود أو المفتاح للمستخدم والـlogs.

**الإصلاح المنفذ**

- إضافة `ai_provider.py` لتوحيد أسماء الموديلات والتحقق منها وتصنيف الأخطاء المؤقتة.
- قراءة المفتاح من environment variable فقط.
- إضافة primary model وfallback واحد عند الأخطاء المؤقتة فقط.
- إضافة request timeout وtotal timeout وحدود للإدخال والإخراج وعدد tool rounds.
- عزل طلب Ask Coach في `chatbot_worker.py` بمهلة نهائية محدودة.
- إظهار رسائل آمنة عند عدم توفر Gemini، مع بقاء الوظائف الحتمية متاحة.

**أسماء الإعدادات دون قيم**

- `GOOGLE_API_KEY`
- `COACHAI_CHAT_MODEL`
- `COACHAI_CHAT_FALLBACK_MODEL`
- `COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS`
- `COACHAI_PLANNER_MODEL`
- `COACHAI_PLANNER_FALLBACK_MODEL`
- `COACHAI_FUTURE_PLANNER_MODEL`
- `COACHAI_FUTURE_PLANNER_FALLBACK_MODEL`
- `COACHAI_VOICE_MODEL`
- `COACHAI_VOICE_FALLBACK_MODEL`

**أهم الملفات**

- `ai_provider.py`
- `chatbot_service.py`
- `chatbot_worker.py`
- `planner.py`
- `planning_request.py`
- `voice_service.py`
- `tests/test_ai_boundary.py`

### 2.7 Ask Coach

**الإصلاح المنفذ**

- توصيل شاشة Ask Coach بالـFastAPI endpoint الموثّق.
- استخدام أدوات مقيدة لجلب بيانات المستخدم الموثّق فقط.
- إضافة hard deadline وfallback محلي آمن عند timeout أو فشل المزود.
- عدم إظهار استثناءات Gemini أو أي بيانات حساسة للمستخدم.

**الحالة**

- ظهرت إجابة فعلية مبنية على بيانات المستخدم في الاختبار المحلي.
- ظهرت أيضًا حالة provider مؤقتة وتم عرض رسالة آمنة بدل انهيار التطبيق.
- يحتاج اختبارًا إضافيًا على جهاز حقيقي وبعد النشر.

**أهم الملفات**

- `chatbot_service.py`
- `chatbot_worker.py`
- `api_routes.py`
- `application_services.py`
- `android/app/src/main/java/com/coachai/android/ui/ask/AskCoachScreen.kt`

### 2.8 AI Planner

**المشكلة الأصلية**

- كان يجب ألا تتحول نتيجة AI مباشرة إلى بيانات محفوظة دون مراجعة المستخدم.

**الإصلاح المنفذ**

- فصل إنشاء المسودة عن apply/save.
- التحقق من بنية مخرجات AI وحدودها قبل عرضها.
- عرض المهام والمدد والمواعيد للمراجعة.
- لا يتم الحفظ إلا بعد ضغط المستخدم على **Save plan**.
- الحفاظ على idempotency ومنع apply المكرر.

**التدفق الحالي**

`Input → AI Draft → Review → Save`

**أهم الملفات**

- `planner.py`
- `application_services.py`
- `android/app/src/main/java/com/coachai/android/ui/schedule/ScheduleScreen.kt`
- `tests/test_ai_boundary.py`

### 2.9 Voice Planner

**المشاكل الأصلية**

- الـEmulator كان قد يمرر صمتًا أو صوتًا غير صالح.
- لم يكن هناك playback قبل رفع التسجيل.
- النص الناتج كان يحتاج مراجعة قبل بناء الخطة.

**الإصلاح المنفذ**

1. **Record:** تسجيل ملف `.m4a` مؤقت داخل Android cache باستخدام `VOICE_RECOGNITION`.
2. **Playback:** تشغيل التسجيل محليًا قبل إرساله.
3. **Re-record:** حذف التسجيل المؤقت الحالي وبدء تسجيل جديد.
4. **Transcription:** رفع bytes إلى FastAPI مع MIME type، والتحقق من النوع والحجم حتى 15 MB.
5. **Review:** عرض transcript للمراجعة والتعديل.
6. **Draft → Review → Save:** إنشاء مسودة من النص، مراجعة المهام، ثم الحفظ الصريح فقط.

- الصوت يُرسل إلى Gemini كـinline bytes.
- كود المشروع لا يحفظ التسجيل في PostgreSQL أو Firebase.
- الملف المؤقت يُحذف عند الاستبدال أو إغلاق التدفق.

**الحالة**

- UI والتدفق والحدود موجودة ومغطاة باختبارات الوحدة ذات الصلة.
- جودة التسجيل والتحويل لم تُثبت على هاتف Android حقيقي.
- مشكلة host microphone في الـEmulator لا تُعتبر دليلًا على عمل transcription على جهاز حقيقي.

**أهم الملفات**

- `android/app/src/main/java/com/coachai/android/ui/schedule/ScheduleScreen.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/TaskApi.kt`
- `api_routes.py`
- `voice_service.py`

### 2.10 Future Planning

**الإصلاح المنفذ**

- دعم candidates وpreview وapply وundo.
- رفض تاريخ أقدم من اليوم المحلي للمستخدم.
- إلغاء نتيجة candidate قديمة إذا تغير النطاق بسرعة.
- منع Apply أو Undo آخر أثناء وجود mutation قيد التنفيذ.
- منع Apply المكرر من إنشاء drafts مكررة.
- تحديث Schedule وHome بعد Apply/Undo الناجح.

**الحالة**

- السلوك مغطى باختبارات Backend واختبار Android ViewModel.
- preview → apply → inspect → undo يحتاج اختبارًا كاملًا على جهاز حقيقي.

**أهم الملفات**

- `android/app/src/main/java/com/coachai/android/ui/future/FuturePlanningScreen.kt`
- `android/app/src/main/java/com/coachai/android/ui/future/FuturePlanningViewModel.kt`
- `android/app/src/main/java/com/coachai/android/data/FuturePlanningRepository.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/FuturePlanningApi.kt`
- `android/app/src/test/java/com/coachai/android/ui/future/FuturePlanningViewModelTest.kt`

### 2.11 FCM وتسجيل الجهاز والإشعارات

**الإصلاح المنفذ**

- Android يجلب Firebase Messaging token الحقيقي بدل استخدام installation ID كـpush token.
- تسجيل device/token يمر عبر endpoint موثّق.
- PostgreSQL يستخدم transaction advisory locks بترتيب ثابت لمنع race بين تسجيل device أو token بالتوازي.
- التحقق من tenant ownership وعدم إرجاع push token في responses.
- وجود `CoachFirebaseMessagingService` وقنوات الإشعارات وتفضيلات الصوت والاهتزاز.

**غير المنفذ أو غير المختبر**

- إرسال FCM من الباك إند غير مفعّل محليًا ولا توجد server credentials في نسخة Git.
- notification worker لم يُختبر مع Firebase الحقيقي.
- foreground/background delivery وtap routing/deep-link لم تُختبر end-to-end.

**أهم الملفات**

- `android/app/src/main/java/com/coachai/android/data/NotificationDeviceRegistrar.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/NotificationDeviceApi.kt`
- `android/app/src/main/java/com/coachai/android/notifications/CoachFirebaseMessagingService.kt`
- `notifications/repository.py`
- `notifications/worker_runtime.py`
- `tests/test_notification_devices.py`

### 2.12 Google Calendar

- توجد شاشات Android وFastAPI routes لتدفقات connect/callback/list/select/sync/disconnect.
- توجد طبقة تشفير وتخزين principal-scoped لتوكنات OAuth، وCalendar worker وعمليات reconciliation.
- توجد اختبارات آلية للأمان والـqueue والـsegment synchronization.
- البيئة المحلية الحالية لا تحتوي إعداد OAuth مكتملًا للتشغيل الفعلي.
- لم يتم اختبار connect → callback → select → sync → reconcile → disconnect مع Google Calendar الحقيقي.

**أهم الملفات**

- `google_calendar.py`
- `calendar_security.py`
- `calendar_worker.py`
- `application_services.py`
- `api_routes.py`
- `android/app/src/main/java/com/coachai/android/ui/calendar/CalendarConnectionsScreen.kt`
- `android/app/src/main/java/com/coachai/android/data/CalendarRepository.kt`
- `android/app/src/main/java/com/coachai/android/data/remote/CalendarApi.kt`

## 3. Feature Status Matrix

| Feature | Status | Evidence / remaining work |
|---|---|---|
| Email/Password Auth | ✅ Implemented & Verified | Firebase ID Token صدر محليًا و`GET /v1/profile` أعاد `200`. |
| Google Sign-In | 🟡 Implemented but needs real-device/end-to-end testing | وصل إلى Account Chooser؛ اختيار الحساب والدخول النهائي غير مختبر. |
| Firebase token refresh | ✅ Implemented & Verified | `401` refresh/retry مرة واحدة ومغطى باختبارات Android. |
| User isolation | ✅ Implemented & Verified | Firebase UID وtenant ownership مغطّيان باختبارات Backend؛ اختبار حسابين حقيقيين ما زال مطلوبًا. |
| PostgreSQL | ✅ Implemented & Verified | Local PostgreSQL يعمل وAlembic عند `20261009_0002 (head)`. |
| Gemini Ask Coach | 🟡 Implemented but needs real-device/end-to-end testing | عمل محليًا مع معالجة آمنة لفشل المزود؛ لا يوجد Production verification. |
| AI Planner | 🟡 Implemented but needs real-device/end-to-end testing | Draft → Review → Save موجود ومغطى آليًا؛ التدفق الكامل على هاتف حقيقي مطلوب. |
| Voice Planner | 🟡 Implemented but needs real-device/end-to-end testing | Record/Playback/Re-record/Transcribe/Review موجود؛ transcription على هاتف حقيقي غير مؤكد. |
| Future Planning | 🟡 Implemented but needs real-device/end-to-end testing | Preview/Apply/Undo وحماية التكرار موجودة؛ real-device flow مطلوب. |
| FCM registration | 🟡 Implemented but needs real-device/end-to-end testing | Android يجلب FCM token والكود يمنع التسجيل المتعارض؛ تسجيل جهاز إنتاج حقيقي غير مؤكد. |
| FCM backend delivery | ❌ Not implemented / Production work | Worker/provider code موجود لكن الإرسال الحقيقي غير مفعّل أو مختبر. |
| Google Calendar | 🟡 Implemented but needs real-device/end-to-end testing | الكود والعقود موجودة؛ OAuth والإرسال الحقيقي غير مهيئين محليًا. |
| Firebase App Check | ❌ Not implemented / Production work | لم تتم إضافة SDK/validation للتطبيق والباك إند. |
| Crashlytics | ❌ Not implemented / Production work | Firebase Crashlytics غير مضاف. |
| Firebase Analytics | ❌ Not implemented / Production work | Analytics الحالية داخل المنتج وليست Firebase Analytics. |
| Remote Config | ❌ Not implemented / Production work | Firebase Remote Config غير مضاف. |
| Render deployment | ❌ Not implemented / Production work | مجرد معمارية مقترحة؛ لا توجد خدمة منشورة. |
| Neon PostgreSQL | ❌ Not implemented / Production work | لا توجد قاعدة Neon؛ القاعدة الحالية محلية. |
| Release APK/AAB | ❌ Not implemented / Production work | Debug APK فقط؛ لا يوجد signed Release build أو AAB. |

## 4. Verification Results الحالية

### Backend

- إجمالي الاختبارات: **486**.
- **485 passed, 0 failed, 1 skipped**.
- المدة: **185.690 ثانية**.
- الاختبار المتجاوز: `test_real_database_copy_preserves_invalid_legacy_row` بسبب عدم توفر قاعدة الاختبار المحلية التي يتوقعها.
- `pytest` غير مثبت في البيئة الحالية، لذلك تم تشغيل نفس ملفات مجموعة الاختبارات الكاملة عبر:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v
```

لا يعني نجاح الاختبارات الآلية أن Firebase/FCM/Google Calendar/Gemini الخارجيين تم اختبارهم في Production.

### Android

- **51 passed, 0 failed, 0 skipped**.
- عدد test suites: 15.
- الأمر المستخدم:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

### Debug Build

- **BUILD SUCCESSFUL**.
- Debug APK موجود تحت `android/app/build/outputs/apk/debug/app-debug.apk`.
- لم يتم تشغيل Release build.

## 5. ما لم يُختبر بعد

- Fresh install وتدفق كامل على هاتف Android حقيقي.
- Voice recording/transcription على جهاز حقيقي.
- Google Sign-In end-to-end بحساب اختبار.
- حسابان حقيقيان للتأكد يدويًا من tenant isolation وعدم بقاء UI state من الحساب السابق.
- FCM foreground/background والتعامل مع notification tap/deep-link.
- Google Calendar connect/sync/reconcile/disconnect مع Google الحقيقي.
- دورة المهام الكاملة على جهاز حقيقي بكل حالات create/edit/delete/start/pause/resume/complete/fail/defer/undo.
- فشل الشبكة والمزود والسيرفر على جهاز حقيقي.
- Production deployment وHTTPS وmonitoring وbackup/restore.
- Release signed APK/AAB.

## 6. معمارية Production المقترحة — غير منفذة حاليًا

المقترح للمرحلة القادمة هو:

```text
Android App → Firebase Auth / App Check → Render FastAPI → Neon PostgreSQL
```

مع استخدام Firebase مستقبلًا في:

- FCM للإشعارات.
- Crashlytics لتسجيل الأعطال.
- Firebase Analytics لقياس الاستخدام.
- Remote Config للـfeature flags ورسائل الصيانة.

هذه المعمارية **اقتراح فقط**. Render وNeon وApp Check وCrashlytics وFirebase Analytics وRemote Config غير مفعّلة حاليًا.

## 7. كيف تطبق الإصلاحات على نسخة أخرى من المشروع

إذا كانت نسخة يوسف تحتوي تعديلات مختلفة أو أحدث، فلا يُنصح باستبدال المشروع كاملًا أو نسخ المجلد فوق نسخته. الأفضل نقل الإصلاحات حسب commits أو مجموعات منطقية:

1. Backend identity وFirebase verification وtenant isolation.
2. Android Auth UI وتدفق التسجيل.
3. `AuthenticatedRequest` وFirebase token refresh، ثم تحديث Repositories/APIs.
4. Gemini provider وAsk Coach والـworker المعزول.
5. AI Planner وDraft → Review → Save.
6. Voice recording/playback/transcription/review.
7. Future Planning وApply/Undo guards.
8. FCM device registration والـPostgreSQL concurrency locks.
9. الاختبارات والتوثيق.

بعد كل مجموعة:

- حل أي تعارض يدويًا مع الحفاظ على تعديلات نسخة يوسف.
- تشغيل اختبارات المجموعة ثم Verification Gate كاملة.
- عدم نقل ملفات الأسرار أو الملفات المحلية.
- استخدام commits صغيرة قابلة للمراجعة والرجوع بدل commit واحد ضخم.

## 8. ملاحظات Architecture وديون تقنية

- البنية الحالية تستخدم UI + ViewModel + Repository + data/remote + domain، وهي مناسبة للاستمرار.
- `ScheduleScreen.kt` و`AuthScreen.kt` كبيران ويُفضّل تقسيمهما لاحقًا، لكن لا يُنصح بإعادة كتابتهما قبل تثبيت نسخة الإنتاج.
- Home وAnalytics يستخدمان WebView بينما بقية التطبيق Native Compose.
- networking يدوي باستخدام `HttpURLConnection` بدل Retrofit/Ktor.
- dependency injection يدوي؛ يمكن تقييم Hilt إذا كبر المشروع.

هذه نقاط تحسين مستقبلية وليست سببًا لإيقاف النسخة الحالية.

## 9. Security وتسليم الأسرار

لا يجب رفع أو إرسال أي من الآتي داخل Git:

- `.env` أو محتواه.
- `android/app/google-services.json` إلا وفق سياسة الفريق الخاصة؛ النسخة الحالية تتجاهله.
- Firebase service-account credentials.
- Gemini API key.
- Firebase ID tokens أو FCM device tokens.
- database passwords أو connection strings المحتوية على password.
- Google OAuth client secret أو Calendar access/refresh tokens.
- release keystore أو كلمات مروره.
- APK/AAB وlogs وUI dumps وEmulator screenshots الناتجة محليًا.

يمكن توثيق **أسماء** environment variables فقط، وتوضع القيم الحقيقية في secret manager أو بيئة التشغيل، وليس في المستودع.

## 10. حالة Git وقت إعداد هذا المستند

- Verification Gate نجحت قبل تجهيز المستند.
- Emulator screenshots وUI XML dumps المؤقتة تم حذفها.
- `.gitignore` تم تحديثه لمنع الأنماط المحلية المعروفة.
- التغييرات لم تُعمل لها commit أو push بعد.
- ملف التسليم هذا يجب مراجعته مع commit plan قبل إنشاء commits الفعلية.
