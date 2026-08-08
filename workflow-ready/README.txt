دستور انتقال دستی
-----------------
این پوشه `workflow-ready` فقط برای دور زدن محدودیت انتقال خودکار ورکفلو ساخته شده.

فایل اصلی:
  workflow-ready/android-release.yml

آن را دستی به مسیر زیر منتقل کن:
  .github/workflows/android-release.yml
  (در این ریپو فعلی نامش main.yml است - می‌تونی جایگزینش کنی یا هر دو را نگه داری)

تغییر کلیدی:
- اگر Secrets امضای خصوصی (ANDROID_KEYSTORE_BASE64 ...) تنظیم شده باشد -> از همان استفاده می‌کند (امضای خصوصی)
- اگر Secrets تنظیم نشده باشد -> به صورت خودکار با امضای عمومی debug (android/debug) بیلد می‌کند
  => خروجی `app/build/outputs/apk/release/*.apk` همیشه Signed و قابل نصب است
  => دیگر APK بدون امضا (unsigned) تولید نمی‌شود

تغییر مکمل در app/build.gradle.kts:
  signingConfig برای buildType release همیشه ست می‌شود:
    - با secrets -> signingConfigs.release
    - بدون secrets -> signingConfigs.debug  (امضای عمومی قابل نصب)

نیازی به کامیت کردن keystore نیست و .gitignore هم دست نخورده باقی مانده.
