(() => {
  "use strict";
  const $ = (selector, parent = document) => parent.querySelector(selector);
  const $$ = (selector, parent = document) => [...parent.querySelectorAll(selector)];

  // Two-versus-ten comparison. The stylesheet dims the extra windows from data-count.
  const scene = $(".scene");
  const captions = {
    2: "The original two-window limit. Ready for more?",
    10: "Five times the room. Make it yours."
  };
  $$("[data-window-count]").forEach(button => {
    button.addEventListener("click", () => {
      const count = button.dataset.windowCount;
      scene.dataset.count = count;
      $$("[data-window-count]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
      $("#window-count").textContent = count;
      $("#window-caption").textContent = captions[count];
    });
  });

  // The wall is wider than a phone. Start on the middle column, which stays lit in both states.
  const wall = $(".scene-scroll");
  wall.scrollLeft = (wall.scrollWidth - wall.clientWidth) / 2;

  const wideScreens = {
    menu: {
      image: "assets/menu-wide.webp",
      alt: "Actual landscape app-menu editor with an app list beside a live headset preview"
    },
    settings: {
      image: "assets/settings-wide.webp",
      alt: "Actual landscape Settings interface with glasses and launch controls beside scrolling preferences"
    }
  };
  $$("[data-wide-screen]").forEach(button => {
    button.addEventListener("click", () => {
      const screen = wideScreens[button.dataset.wideScreen];
      const image = $("#wide-screen");
      image.src = screen.image;
      image.alt = screen.alt;
      $$("[data-wide-screen]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    });
  });

  // Never guess a repository or a download. The build supplies explicit HTTPS destinations.
  const config = window.UNBOUND_SITE || {};
  function httpsUrl(value) {
    try { const url = new URL(value); return url.protocol === "https:" && !url.username && !url.password ? url.href : null; }
    catch { return null; }
  }
  const repository = httpsUrl(config.repositoryUrl);
  if (repository) $$(".repository-link").forEach(link => { link.href = repository; link.hidden = false; });
  const download = httpsUrl(config.downloadUrl);
  if (download) {
    // The release version is written once, in the hero of index.html.
    const version = $("#release-version").textContent.trim();
    const link = $("#download-link");
    link.href = download;
    link.removeAttribute("target");
    $("span", link).textContent = `Download ${version}`;
    $("use", link).setAttribute("href", "#i-download");
    $("#community-link").hidden = false;
    $("#download-note").textContent = `${version} · Android APK · About 1 GB · Check the requirements before installing.`;
  } else {
    // Without a published download, the page-top actions lead to the community invitation, so they must not say "Download".
    $$("[data-get-label]").forEach(label => { label.textContent = label.dataset.getLabel; });
    $$("[data-get-icon]").forEach(icon => icon.setAttribute("href", icon.dataset.getIcon));
  }
})();
