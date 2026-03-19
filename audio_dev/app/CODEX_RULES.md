Project: DeployTeach Android audio tracking module

Hard constraints:
- Java only, not Kotlin
- Do not rewrite unrelated files
- Keep code modular and small
- Never store or export raw audio
- Process audio on-device only
- Use a foreground microphone service
- Build in phases; do not jump ahead
- After each phase, list:
    1) files created/edited
    2) how to run
    3) what still remains
- If any dependency is uncertain, stop and explain the exact issue instead of guessing

Target module behavior:
- detect speech vs silence
- detect non-speech disturbances
- estimate a lightweight reverb/echo proxy
- privacy-preserving event logging only