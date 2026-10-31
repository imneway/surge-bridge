# Build artifact delivery

After each successful APK build, copy every generated APK for that build (debug or release, all generated architectures) into:

`/Users/liuwei/Dropbox/Personal/Dev/Builds/Surge Android APK`

Keep version, architecture and debug/release distinctions in filenames. Verify copied files match the build outputs and link to the copies in this delivery directory when handing off APKs. Gradle may retain its normal intermediate/output directories. Do not copy signing keys or signing passwords into the APK delivery directory.
