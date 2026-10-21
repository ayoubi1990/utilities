// velo integration: shows a "Download this video" button over videos on the page.
(() => {
  if (window.__veloInjected) return;
  window.__veloInjected = true;

  const MIN_W = 200;
  const MIN_H = 110;
  const HIDE_AFTER_MS = 2500;

  let sniffed = []; // video files the background script saw this tab load
  let current = null; // the <video> the button is attached to
  let hideTimer = 0;
  const dismissed = new WeakSet();

  // ---- UI, isolated from the page's CSS in a shadow root ----

  const host = document.createElement("div");
  host.style.cssText = "all:initial;position:fixed;z-index:2147483647;top:0;left:0;display:none;";
  const root = host.attachShadow({ mode: "closed" });
  root.innerHTML = `
    <style>
      :host { all: initial; }
      .bar { display: flex; align-items: stretch; font: 600 12.5px/1 "Segoe UI", system-ui, sans-serif;
             box-shadow: 0 2px 10px rgba(0,0,0,.35); border-radius: 6px; overflow: hidden; }
      button { all: unset; cursor: pointer; color: #fff; display: flex; align-items: center; }
      .go { gap: 7px; padding: 7px 11px 7px 9px;
            background: linear-gradient(#3d8be6, #1f6fd1); border-right: 1px solid rgba(255,255,255,.25); }
      .go:hover { background: linear-gradient(#4a97f0, #2a7be0); }
      .x { padding: 0 8px; background: #1a5fb4; font-size: 14px; }
      .x:hover { background: #164f97; }
      svg { width: 15px; height: 15px; }
      .menu { margin-top: 4px; background: #fff; color: #1f2328; border-radius: 6px; min-width: 260px;
              max-width: 420px; box-shadow: 0 4px 16px rgba(0,0,0,.3); padding: 4px 0;
              font: 12.5px/1.3 "Segoe UI", system-ui, sans-serif; }
      .item { all: unset; display: block; box-sizing: border-box; width: 100%; padding: 7px 12px;
              cursor: pointer; color: #1f2328; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .item:hover { background: #e8f0fc; }
      .item small { color: #6b7280; margin-left: 6px; }
      .toast { margin-top: 4px; padding: 7px 11px; border-radius: 6px; background: #202124; color: #fff;
               font: 12.5px/1.3 "Segoe UI", system-ui, sans-serif; box-shadow: 0 2px 10px rgba(0,0,0,.35);
               max-width: 340px; }
      [hidden] { display: none !important; }
    </style>
    <div class="bar">
      <button class="go" title="Download this video with velo">
        <svg viewBox="0 0 16 16" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"
             stroke-linejoin="round"><path d="M8 2v8M4.5 6.8 8 10.3l3.5-3.5M3 13.5h10"/></svg>
        <span>Download this video</span>
      </button>
      <button class="x" title="Hide for this video">×</button>
    </div>
    <div class="menu" hidden></div>
    <div class="toast" hidden></div>`;
  const goButton = root.querySelector(".go");
  const label = goButton.querySelector("span");
  const closeButton = root.querySelector(".x");
  const menu = root.querySelector(".menu");
  const toastBox = root.querySelector(".toast");

  function attachHost() {
    // In fullscreen only the fullscreen element's subtree is drawn, so move there.
    const parent = document.fullscreenElement || document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
  }

  // ---- finding what to download ----

  function fileName(url) {
    try {
      const last = new URL(url).pathname.split("/").filter(Boolean).pop() || url;
      return decodeURIComponent(last);
    } catch (e) {
      return url;
    }
  }

  function sizeText(bytes) {
    if (!bytes) return "";
    const mb = bytes / (1024 * 1024);
    return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(1)} MB`;
  }

  function candidates(video) {
    const list = [];
    const add = (url, note) => {
      if (url && /^https?:/i.test(url) && !list.some((c) => c.url === url)) list.push({ url, note: note || "" });
    };
    add(video.currentSrc, "playing");
    add(video.getAttribute("src") && video.src);
    video.querySelectorAll("source[src]").forEach((s) => add(s.src, s.type));
    // Players that stream through JavaScript expose no file URL on the element;
    // fall back to the video files the page actually downloaded.
    sniffed
      .slice()
      .sort((a, b) => (b.size || 0) - (a.size || 0))
      .forEach((m) => add(m.url, sizeText(m.size)));
    return list;
  }

  function bigEnough(video) {
    const r = video.getBoundingClientRect();
    return r.width >= MIN_W && r.height >= MIN_H && r.bottom > 0 && r.top < window.innerHeight;
  }

  function videoAt(x, y) {
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom && bigEnough(v)) return v;
    }
    return null;
  }

  // ---- showing and placing the button ----

  function place() {
    if (!current || !current.isConnected) return hide();
    const r = current.getBoundingClientRect();
    const w = host.offsetWidth || 220;
    host.style.top = `${Math.max(4, r.top + 10)}px`;
    host.style.left = `${Math.max(4, Math.min(window.innerWidth - w - 4, r.right - w - 10))}px`;
  }

  function show(video) {
    if (dismissed.has(video)) return;
    // Streamed videos (assembled from pieces by the player, e.g. YouTube) have no single file;
    // for those velo is sent the page, and yt-dlp works out the qualities.
    const streamed = candidates(video).length === 0;
    label.textContent = "Download this video";
    goButton.title = streamed
      ? "Send this page to velo; it will offer the available qualities"
      : "Download this video with velo";
    current = video;
    attachHost();
    host.style.display = "block";
    place();
    scheduleHide();
  }

  function hide() {
    host.style.display = "none";
    menu.hidden = true;
    current = null;
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (!host.matches(":hover") && menu.hidden && toastBox.hidden) hide();
    }, HIDE_AFTER_MS);
  }

  function toast(text) {
    attachHost();
    if (host.style.display === "none") {
      host.style.display = "block";
      root.querySelector(".bar").hidden = true;
      host.style.top = "16px";
      host.style.left = `${Math.max(4, window.innerWidth - 360)}px`;
    }
    toastBox.textContent = text;
    toastBox.hidden = false;
    setTimeout(() => {
      toastBox.hidden = true;
      root.querySelector(".bar").hidden = false;
      if (!current) host.style.display = "none";
      else scheduleHide();
    }, 3200);
  }

  function send(url, mode) {
    menu.hidden = true;
    chrome.runtime.sendMessage(
      { type: "velo-download", url, pageUrl: location.href, title: document.title, fileName: null, mode },
      (res) => {
        if (chrome.runtime.lastError) return toast("velo extension was reloaded; refresh this page.");
        toast(res && res.ok ? "Sent to velo" : (res && res.error) || "Could not reach velo.");
      }
    );
  }

  goButton.addEventListener("click", (e) => {
    e.stopPropagation();
    if (!current) return;
    const list = candidates(current);
    if (list.length === 0) return send(location.href, "page");
    if (list.length === 1) return send(list[0].url);
    menu.replaceChildren(
      ...list.map((c) => {
        const item = document.createElement("button");
        item.className = "item";
        item.title = c.url;
        item.textContent = fileName(c.url);
        if (c.note) {
          const small = document.createElement("small");
          small.textContent = c.note;
          item.appendChild(small);
        }
        item.addEventListener("click", (ev) => {
          ev.stopPropagation();
          send(c.url);
        });
        return item;
      })
    );
    menu.hidden = false;
  });

  closeButton.addEventListener("click", (e) => {
    e.stopPropagation();
    if (current) dismissed.add(current);
    hide();
  });

  // ---- events ----

  let lastMove = 0;
  document.addEventListener(
    "mousemove",
    (e) => {
      const now = Date.now();
      if (now - lastMove < 150) return;
      lastMove = now;
      const v = videoAt(e.clientX, e.clientY);
      if (v) {
        if (v !== current) show(v);
        else scheduleHide();
      }
    },
    { passive: true, capture: true }
  );

  // A video that starts playing gets the button briefly, as in classic download managers.
  document.addEventListener(
    "play",
    (e) => {
      if (e.target instanceof HTMLVideoElement && bigEnough(e.target)) show(e.target);
    },
    true
  );

  window.addEventListener("scroll", place, { passive: true, capture: true });
  window.addEventListener("resize", place, { passive: true });
  document.addEventListener("fullscreenchange", () => {
    attachHost();
    place();
  });

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === "velo-media") sniffed = msg.media || [];
    if (msg.type === "velo-toast") toast(msg.text);
  });
  chrome.runtime.sendMessage({ type: "velo-get-media" }, (list) => {
    if (!chrome.runtime.lastError && Array.isArray(list)) sniffed = list;
  });
})();
