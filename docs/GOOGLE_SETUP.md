# Google features (Gmail, Calendar, Drive, Docs, Sheets, Tasks, Contacts)

Google only lets an app read a person's Gmail or Drive if the app is registered in a Google Cloud project, and apps that ask for those permissions must pass Google's verification. Alpha Assistant is a free open-source project and has not been through that process, so **the prebuilt APK cannot sign in to Google for you**. Everything else in the app works without it.

If you want the Google features, build your own copy of the app and register it yourself. It takes about 15 minutes and costs nothing.

1. Find the **SHA-1 fingerprint** of the key you sign your copy with:
   `keytool -list -v -keystore <your.jks> -alias alpha` (for a debug build, your `~/.android/debug.keystore`).
2. In the [Google Cloud console](https://console.cloud.google.com/), create a project. Under *APIs & Services → Library* enable the Gmail, Calendar, Drive, Docs, Sheets, Tasks and People APIs you want.
3. Under *OAuth consent screen* choose **External**, fill in the basics, leave the app in **Testing**, and add your own Google account as a **test user**. (In Testing mode only listed test users can sign in, which is exactly what you want for personal use.)
4. Under *Credentials* create an **OAuth client ID** of type **Android**, with the package name `com.alphainternational.assistant` and the SHA-1 from step 1.
5. Install your build, open **Settings → Logins**, and sign in with that Google account.

Notes: Google may show an "unverified app" warning; that is expected for your own test project. Sign-ins from a project in Testing mode expire every 7 days, so you sign in again. If you change the signing key, register the new SHA-1.
