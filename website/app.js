(() => {
  "use strict";
  const $ = (selector, parent = document) => parent.querySelector(selector);
  const $$ = (selector, parent = document) => [...parent.querySelectorAll(selector)];

  // Always honor the OS preference. The explicit control also pauses decorative motion.
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const motionButton = $(".motion-toggle");
  let userPaused = false;
  try { userPaused = sessionStorage.getItem("unbound-motion-paused") === "true"; } catch { /* Storage is optional. */ }
  function updateMotion() {
    const paused = userPaused || reducedMotion.matches;
    document.documentElement.classList.toggle("motion-paused", paused);
    motionButton.setAttribute("aria-pressed", String(paused));
    motionButton.disabled = reducedMotion.matches;
    motionButton.textContent = reducedMotion.matches ? "Reduced motion on" : paused ? "Resume motion ▷" : "Pause motion Ⅱ";
  }
  motionButton.addEventListener("click", () => {
    userPaused = !userPaused;
    try { sessionStorage.setItem("unbound-motion-paused", String(userPaused)); } catch { /* No persistence required. */ }
    updateMotion();
  });
  reducedMotion.addEventListener("change", updateMotion);
  updateMotion();

  const menuButton = $(".menu-toggle");
  const mobileNav = $("#mobile-nav");
  function closeMenu(returnFocus = false) {
    mobileNav.hidden = true;
    menuButton.setAttribute("aria-expanded", "false");
    menuButton.setAttribute("aria-label", "Open navigation");
    if (returnFocus) menuButton.focus();
  }
  menuButton.addEventListener("click", () => {
    const open = menuButton.getAttribute("aria-expanded") !== "true";
    menuButton.setAttribute("aria-expanded", String(open));
    menuButton.setAttribute("aria-label", open ? "Close navigation" : "Open navigation");
    mobileNav.hidden = !open;
  });
  $$("a", mobileNav).forEach(link => link.addEventListener("click", () => closeMenu()));
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !mobileNav.hidden) closeMenu(true);
  });
  document.addEventListener("click", event => {
    if (!$(".site-header").contains(event.target)) closeMenu();
  });
  window.matchMedia("(min-width: 801px)").addEventListener("change", event => {
    if (event.matches) closeMenu();
  });

  const demo = $(".space-demo");
  $$("[data-window-count]").forEach(button => {
    button.addEventListener("click", () => {
      const count = button.dataset.windowCount;
      demo.dataset.count = count;
      $$("[data-window-count]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
      $(".demo-counter").textContent = `${count} WINDOWS / OPEN`;
      $("#window-caption").textContent = count === "10" ? "Five times the room. Make it yours." : "The original two-window limit. Ready for more?";
    });
  });
  const scene = $(".demo-scene");
  demo.addEventListener("pointermove", event => {
    if (event.pointerType !== "mouse" || reducedMotion.matches || userPaused) return;
    const bounds = demo.getBoundingClientRect();
    scene.style.setProperty("--scene-x", `${((event.clientX - bounds.left) / bounds.width - .5) * 12}px`);
    scene.style.setProperty("--scene-y", `${((event.clientY - bounds.top) / bounds.height - .5) * 8}px`);
  });
  demo.addEventListener("pointerleave", () => {
    scene.style.setProperty("--scene-x", "0px");
    scene.style.setProperty("--scene-y", "0px");
  });

  const screens = {
    settings: {
      image: "assets/settings.webp",
      title: "Your space starts here.",
      description: "Model-aware glasses artwork, connection status, and one clear action to enter AR Space. Everything important, right where you need it.",
      alt: "Unbound’s black Settings screen showing XREAL glasses and the Enter AR Space button"
    },
    menu: {
      image: "assets/app-menu.webp",
      title: "Make yourself at home.",
      description: "Search your apps by name or package. Drag them into order, hide the distractions, and preview your headset layout. No glasses needed to get organized.",
      alt: "Actual App menu interface with search, list and headset-preview controls, and reorderable apps"
    },
    setup: {
      image: "assets/setup.webp",
      title: "Start here. Stay here.",
      description: "Guided pairing, live connection status, and a clear Ready state. Your phone handles setup, with recovery controls waiting whenever you need them.",
      alt: "Actual on-device ADB setup interface with guided pairing steps and connection controls"
    }
  };
  $$("[data-screen]").forEach(button => {
    button.addEventListener("click", () => {
      const screen = screens[button.dataset.screen];
      const image = $("#phone-screen");
      image.src = screen.image;
      image.alt = screen.alt;
      $("#screen-title").textContent = screen.title;
      $("#screen-description").textContent = screen.description;
      $$("[data-screen]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    });
  });

  $$("[data-wide-screen]").forEach(button => {
    button.addEventListener("click", () => {
      const isMenu = button.dataset.wideScreen === "menu";
      const image = $("#wide-screen");
      image.src = isMenu ? "assets/menu-wide.webp" : "assets/settings-wide.webp";
      image.alt = isMenu ? "Actual landscape app-menu editor with an app list beside a live headset preview" : "Actual landscape Settings interface with glasses and launch controls beside scrolling preferences";
      $$("[data-wide-screen]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    });
  });

  const powerButton = $(".screen-power");
  powerButton.addEventListener("click", () => {
    const off = powerButton.getAttribute("aria-pressed") !== "true";
    powerButton.setAttribute("aria-pressed", String(off));
    $(".screen-off-tile").classList.toggle("is-off", off);
    $("span", powerButton).textContent = off ? "Restore phone screen" : "Try screen off";
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
    const link = $("#download-link");
    link.href = download;
    $("span", link).textContent = "Download 0.2.3 alpha";
    $("#download-note").textContent = "0.2.3 alpha · Android APK · About 1 GB · Check the requirements before installing.";
  }
})();
