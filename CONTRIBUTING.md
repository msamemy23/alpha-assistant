# Contributing

Thank you for helping. Alpha Assistant is a Kotlin / Jetpack Compose Android app; most of the logic lives in plain Kotlin files that can be tested on a normal JVM without a phone.

## Before you start

- Open an issue for anything big, so we can agree on the shape of it first.
- For a **safety problem**, do not open a public issue; follow [SECURITY.md](SECURITY.md).

## Build and test

```
./gradlew :app:testDebugUnitTest      # about 840 JVM tests, no phone needed
./gradlew :app:assembleDebug
```

CI runs the same two commands. A pull request must keep them green.

## How the code is laid out

- `Controller.kt` is the engine (jobs, tools, history); the `*Ui.kt` files are screens.
- Pure rules that decide what is allowed live in small objects with their own tests: `PhoneSafety.kt` (what the phone will do), `SupabaseRules.kt`, `ProactiveRules.kt`, `UsageMeter.kt`, `Rules.kt`. Prefer putting new decisions there, not inside the controller.
- AI providers: `Ai.kt` (the OpenAI Responses services), `ChatCompletionsAi.kt` (everything that speaks Chat Completions), `Provider.kt` (settings, and the gateway that picks one).
- Tools the model can call are defined next to where they run, in `Controller.kt` and `PhoneControl.kt`.

## Style

- Read the file you are changing and write code that looks like it: same naming, same density of comments, same idiom. Comments say *why*, in plain words.
- Add or change a test with every behaviour change. If you fix a bug, first write the test that fails.
- Keep user-facing text short and plain (this app is texted like a person).
- Do not add analytics, tracking, or any network call the user did not ask for.
- Do not copy code from GPL/AGPL projects. Add any new dependency's licence to `NOTICE.md`.

## Pull requests

- One idea per pull request, with a short description of what changed and how you checked it.
- If you touched anything that sends, deletes, pays, publishes or changes settings, say how the safety rules still hold.
- Do not commit keys, personal details, APKs or build output.

By contributing you agree your work is released under the project's MIT licence.
