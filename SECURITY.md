# Security

Alpha Assistant can read your screen, type, tap and send on your behalf. That is powerful, so please report problems privately and give us a chance to fix them.

## Report a vulnerability

Use GitHub's **private vulnerability reporting**: the *Security* tab of this repository, then *Report a vulnerability*. Please do not open a public issue for something that could be abused. Include what you did, what you expected and what happened. This is a small volunteer project, so please be patient.

Things we especially want to hear about:

- A way for text from a web page, email, message, file or screen to make a bot do something the owner did not ask for (a prompt-injection path), above all sending, paying, deleting, publishing or changing settings.
- Any way a secret (an API key, token or password) reaches a log, a chat, a screenshot or the network.
- A way around the rules in `PhoneSafety.kt` (never type secrets, never open password managers, ask before pay / erase / security / unwanted send).
- Anything that makes the app send data somewhere the user did not choose.

## What is protected, and what is not

Protected, and covered by tests: the limits listed under *Safety* in the README.

Not protected, so you should know:

- **The AI can be wrong or fooled.** The limits above reduce the damage; they cannot make a language model trustworthy. Do not give a bot accounts you cannot afford to have mistakes in.
- **Accessibility access is broad.** With the service on, Android lets the app read what is on the screen. Turn it off when you do not need it.
- **The phone itself.** Anyone who unlocks your phone can open the app. State is encrypted at rest with a key held by the Android Keystore, which does not defend against a rooted, compromised or unlocked device.
- **Your AI provider** sees what the bots send it. Choose one you trust, or one that runs on the phone.

## Supported versions

Only the latest release gets fixes.
