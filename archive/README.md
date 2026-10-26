# Archived display-mode research

The compressed snapshots described here are **local-only** and excluded from
Git. They contain original filesystem ownership metadata and, for the standalone
prototype, device captures. The public repository includes these notes and the
JSON manifests, with project paths expressed relative to the repository root.
Do not upload the original archives as source or release assets.

The display-mode feature was removed from active builds on 2026-10-25. Glasses
now use their original device-specific SDK defaults for resolution, refresh rate
and mode. Old saved selections are ignored because neither startup reader is
included.

`display-mode-2026-10-25.tar.gz` preserves the complete source context immediately
before removal: settings UI and activity, preference store and controller, DEX
and native hooks, 2D/3D refresh selection, experimental mono-renderer variants,
build integration, tests, diagnostic tools, and original README notes. The JSON
file lists SHA-256 checksums for the archive and every source file, the required
baseline APK, and historical evidence directories. Historical feature APKs and
reports under `artifacts/` are retained only as research/rollback evidence; the
current release points to `artifacts/unbound-launcher-default-display/`.

The archive is not a source directory, imported module, resource, or release
payload. There is no feature-enabling flag in the active builder. To inspect or
resume the research, extract it into a separate research checkout, then review
the archived README and builder. Do not extract over the active source tree.
The archived tools expect the original APK fixtures and Android/JADX toolchain
at the paths recorded in the snapshot; these large binary dependencies are not
copied into the source archive.

The high-refresh stereo requests were rejected on the tested OnePlus 15/XREAL
One, and mono-renderer wearer tests produced duplicated or blank pictures. Those
results remain historical evidence, not support claims for other glasses.

## Shelved standalone prototype — 2026-10-26

`standalone-prototype-2026-10-26.tar.gz` preserves the standalone source, builder,
evidence verifier, prototype APK and device results. The adjacent JSON records
checksums and the decision to continue with the ADB-assisted launcher. The
prototype is not integrated into the production build. Its development signing
key is retained locally in `artifacts/standalone/` and is not included in this
archive.

Two real apps passed live split-screen capture and Accessibility input on the
OnePlus 15; independent untrusted virtual displays rejected both apps. The final
repeat test was interrupted at the owner's request, so the latest APK is not
claimed to have a completed runtime validation. See
[`standalone/README.md`](../standalone/README.md) for details. Restore into a
separate research directory, preserving the current ADB-assisted launcher.
