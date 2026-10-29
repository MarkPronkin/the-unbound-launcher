# Unbound Launcher website

A static, monochrome project site following the app's Signature OLED design.
Plain HTML, CSS and JavaScript; no runtime dependencies, external fonts, trackers,
CDN libraries, build framework or server. All interactions run in the browser.
The site contains feature explanations, real app screenshots, setup and requirements.
Release notes and individual phone names are intentionally excluded. Feature,
setup and upgrade copy reflects the published 0.2.0 alpha, while compatibility
language distinguishes working-session tests from pending hardware checks.

## Preview

From the project root:

```sh
python3 tools/build_website.py
python3 -m http.server 4173 --bind 127.0.0.1 --directory .site-build
```

Open http://127.0.0.1:4173. Relative asset paths work under a GitHub project
subpath such as `https://OWNER.github.io/REPOSITORY/` and at a custom-domain root.
You can also serve `website/` directly during editing; the build supplies the
repository and download links for deployment.

## Publish alongside the project on GitHub

1. Push the project, including `website/`, `tools/build_website.py`, and
   `.github/workflows/pages.yml`, to its GitHub repository. Keep the existing
   `.gitignore`; APKs, local device reports, credentials, signing keys and
   compressed research snapshots are intentionally ignored.
2. In the repository's **Settings → Pages**, select **GitHub Actions** as Source.
3. Run **Deploy project website** from Actions, or push a website change to
   `main` or `master`. For another default branch, update the workflow's branch list.
4. The deployment's environment link is the published website URL.

The workflow automatically obtains `owner/repository` from GitHub Actions. There
is no account name hard-coded into the site. For a custom setup, `repositoryUrl`
in `website/site.json` can supply the URL; `--repository owner/repo` overrides it.

## Downloads are separate

Upload the roughly 1 GB APK as a **GitHub Release asset**, never into the Pages
directory or Git history. After the asset exists, set the repository Actions
variable `UNBOUND_DOWNLOAD_URL` to its full HTTPS download URL and run the
workflow. The site's main action then becomes **Download 0.2.0 alpha**. Update
the version in `index.html` and `app.js` alongside each public release. You can
alternatively set `downloadUrl` in `site.json`.

Until a real download URL is supplied, **Join the alpha** goes to the project's
existing community. No guessed download, empty button or placeholder repository
is exposed. Linking directly to a binary keeps release notes off the site.

## Size and GitHub limits

The initial website is well under **1 MiB**, including every image. Its builder
enforces a **5 MiB total budget** and **1 MiB per file**. It copies only three
explicit web entry points and the small image assets, then generates configuration.
It rejects unexpected asset types and symlinks. App binaries, development logs,
device data, archives, APK tooling and the repository root are never uploaded.

GitHub's documented limits, checked on 2026-10-26:

- Pages: **1 GB published site**, **100 GB/month soft bandwidth limit**, and
  **10-minute deployment timeout**. The recommended source repository size is
  **1 GB**. The soft ten-builds-per-hour limit does not apply to custom Actions
  workflows. See [GitHub Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits).
- Release assets: **each file must be under 2 GiB**. GitHub currently documents
  no total release-size or release-bandwidth cap. See
  [About releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).
- Ordinary Git files: GitHub blocks files over **100 MiB**; do not commit APKs.
  See [About large files](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).

Only `.site-build/` is deployed. The workflow has read-only source access; the
deployment job alone has Pages and identity-token permissions. Changes outside
the website paths do not trigger unnecessary website builds.

## Design and behavior

- The original Dot Portal mark and the exact Signature black, graphite, white,
  gray and green palette from `settings-ui/src/dev/beampro/settings/Signature.java`.
- CSS spatial hero, a two/ten-window comparison, phone-screen-off illustration,
  real screenshot switching, responsive navigation, and native FAQ disclosure.
- All copy is visible without JavaScript. Optional interactions enhance it.
- Keyboard focus, a skip link, accessible pressed states, reduced-motion support,
  and a manual motion pause. Images below the hero load lazily.
- No video, WebGL dependency, analytics, network fetches, or automatic APK download.

The spatial scenes are explicitly labeled concepts, not headset captures. Phone
and wide-screen images are emulator captures from this project. Pictures of
glasses are original XREAL artwork already shown in the app; original software
and artwork rights remain with their owners. The site identifies the project as
independent and fan-made.

## Asset sources

The optimized WebP files are checked in so CI does not need an image toolchain.
To regenerate them, use `cwebp` with quality 86 for screenshots and lossless mode
for the mark. The original files stay untouched.

| Site image | Original project file |
| --- | --- |
| `mark.webp` | `branding/symbol-white.png`, resized to 480 × 480 |
| `icon.webp` | `branding/app-icon.png`, resized to 128 × 128 |
| `settings.webp` | `artifacts/glasses-artwork-fidelity/after.png` |
| `app-menu.webp` | `artifacts/design-refresh/after/screens/06-app-menu.png`, width 720 |
| `setup.webp` | `artifacts/design-refresh/after/screens/13-adb-setup.png`, width 720 |
| `menu-wide.webp` | `artifacts/landscape-layout/menu-16-10.png`, width 1440 |
| `settings-wide.webp` | `artifacts/landscape-layout/settings-16-10.png`, width 1440 |

The design and feature copy come from the project README and development reports.
No phone brand is used to imply universal compatibility. Revisit the generic
alpha and hardware-testing language as validation progresses.
