# الكامل-نت (Alkamel-Net)

تطبيق Android عربي لخدمات شبكة الكامل، مبني باستخدام Kotlin وWebView مع الاحتفاظ بملفات الموقع الأصلية وواجهة بث HD.

## توافق Android
- `minSdk`: 24 (Android 7.0).
- `targetSdk`: 35.
- `compileSdk`: 35.
- Java 17 / Android Gradle Plugin 8.7.3 / Kotlin 2.0.21.

## إصلاح بدء التشغيل والشاشة البيضاء
- الصفحة الرئيسية `assets/home.html` تُحمّل مباشرة من أصول التطبيق باستخدام `loadDataWithBaseURL` بدلاً من الاعتماد على نجاح تحميل أول مستند عبر WebViewAssetLoader.
- المسارات النسبية للخطوط والصور وملفات HD تبقى على نطاق `appassets.androidplatform.net` وتُخدم من أصول التطبيق.
- توجد شاشة تحميل، وشاشة خطأ عربية مع زر إعادة المحاولة، وتسجيل لأخطاء WebView وJavaScript في Logcat تحت الوسم `AlkamelNet`.
- لم يتم التحقق من التشغيل على جهاز فعلي بعد؛ يجب اختبار النسخة المبنية على Android 7 وجهاز أحدث.

## الخدمات
- الاستعلامات: `http://k.com`
- استراحة 1 و2.
- بث مباشر 1 و2.
- بث HD المدمج: `assets/hd/index.html`.
- تحديث حالة الجلسة عبر `http://k.com/status?var=callBack`.
- تسجيل الخروج عبر المسار الموجود في ملفات الموقع.
- زر إنهاء الاتصال ينفذ مسار `userLogout(false)` الموجود حرفياً في بوابة الموقع: `/logout?var=callBack`؛ وزر تسجيل الخروج الكامل يستخدم `erase-cookie=yes`.
- بطاقة فئة/سرعة الكرت تعرض قيمة `spes` الواردة من حالة الحساب، ولا تغيّر السرعة.
- تحديثات جوجل ظاهرة كخدمة غير مفعّلة لأن ملفات المصدر لا تحتوي على endpoint موثق لها.

## البناء
يتطلب JDK 17 وAndroid SDK Platform 35.
يمكن فتح المجلد في Android Studio ثم اختيار Build APK.
أو استخدام Gradle 8.9:
`gradle --no-daemon :app:assembleDebug`

الناتج:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
ملف `.github/workflows/android-build.yml` يشغّل بناء Debug عند الدفع إلى `main` أو يدوياً من Actions، ثم يرفع Artifact منفصلاً للتطبيق وArtifact للمصدر الكامل.

## ملاحظات
- خدمات `k.com` وعناوين 30.x و10.x لا تعمل إلا عند الاتصال بشبكة الكامل.
- بث HD يستخدم `http://10.10.10.10:8888/live/index.m3u8`.
- صفحة HD تستخدم HLS.js من CDN، وقد تحتاج إنترنت للوصول إلى المكتبة؛ يمكن استخدام VLC أو MX Player عند تعذر ذلك.


### فحوص ما قبل البناء

يتضمن المشروع سكربت `scripts/validate_project.py` لفحص إعدادات Android الأساسية، ووجود ملفات الواجهات، وروابط خدمات الشبكة الموثقة، وصحة JavaScript داخل صفحات التطبيق. يشغله GitHub Actions قبل محاولة بناء APK. هذه الفحوص لا تغني عن اختبار التطبيق على شبكة الكامل الفعلية.


## Launcher icon

Current app release: 1.5.5 (version code 39).

The Android launcher icon uses the approved multicolor `شبكة الكامل` artwork from `app/src/main/res/drawable-nodpi/ic_launcher.webp`.
