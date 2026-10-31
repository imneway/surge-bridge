# Build artifact delivery

After each successful APK build, copy every generated APK for that build (debug or release, all generated architectures) into:

`/Users/liuwei/Dropbox/Personal/Dev/Builds/Surge Android APK`

Keep version, architecture and debug/release distinctions in filenames. Verify copied files match the build outputs and link to the copies in this delivery directory when handing off APKs. Gradle may retain its normal intermediate/output directories. Do not copy signing keys or signing passwords into the APK delivery directory.

# Git commits

After each round of changes, complete the relevant validation and commit that round's scoped changes before handing off. Report the commit hash and any outstanding validation. Do not include unrelated user changes, credentials, private logs, or chat attachments. A local commit does not authorize pushing to a remote.
