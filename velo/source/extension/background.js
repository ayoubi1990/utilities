// velo integration: notices video files that pages load, and hands links to the
// velo desktop app through its local bridge on 127.0.0.1.

const VELO = "http://127.0.0.1:47321";
const VIDEO_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|wmv|flv|3gp|ogv)(?:$|[?#])/i;
// Pieces of adaptive streams (DASH/HLS segments) are not whole videos.
const SEGMENT = /\.(m4s|ts|m4f|cmfv)(?:$|[?#])|[?&](range|bytestart)=/i;
const MIN_BYTES = 256 * 1024;
const MAX_PER_TAB = 25;

const NOT_RUNNING = "velo is not running. Start velo and try again.";

// ---- media seen per tab (kept in session storage so it survives worker restarts) ----

const key = (tabId) => `media:${tabId}`;

async function getMedia(tabId) {
  const stored = await chrome.storage.session.get(key(tabId));
  return stored[key(tabId)] || [];
}

let writes = Promise.resolve();

function addMedia(tabId, item) {
  // Serialize updates so concurrent responses do not overwrite each other.
  writes = writes.then(async () => {
    const list = await getMedia(tabId);
    if (list.some((m) => m.url === item.url)) return;
    list.push(item);
    while (list.length > MAX_PER_TAB) list.shift();
    await chrome.storage.session.set({ [key(tabId)]: list });
    chrome.tabs.sendMessage(tabId, { type: "velo-media", media: list }).catch(() => {});
  }).catch(() => {});
}

function clearMedia(tabId) {
  writes = writes.then(() => chrome.storage.session.remove(key(tabId))).catch(() => {});
}

function header(headers, name) {
  const h = headers.find((x) => x.name.toLowerCase() === name);
  return h ? h.value : "";
}

/** Total size from Content-Range ("bytes 0-99/12345") or Content-Length. */
function totalSize(headers) {
  const range = header(headers, "content-range");
  const m = /\/(\d+)\s*$/.exec(range);
  if (m) return Number(m[1]);
  const len = Number(header(headers, "content-length"));
  return Number.isFinite(len) ? len : 0;
}

chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0 || (details.statusCode !== 200 && details.statusCode !== 206)) return;
    if (!/^https?:/i.test(details.url) || SEGMENT.test(details.url)) return;
    const headers = details.responseHeaders || [];
    const type = header(headers, "content-type").toLowerCase();
    const looksLikeFile = VIDEO_EXT.test(details.url) &&
      (type === "" || type.startsWith("application/octet-stream") || type.startsWith("binary/"));
    if (!(type.startsWith("video/") || looksLikeFile) || type.includes("segment")) return;
    const size = totalSize(headers);
    if (size && size < MIN_BYTES) return;
    addMedia(details.tabId, { url: details.url, type, size, seen: Date.now() });
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "object", "other"] },
  ["responseHeaders"]
);

chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) clearMedia(tabId);
});
chrome.tabs.onRemoved.addListener(clearMedia);

// ---- hand-off to the desktop app ----

/** mode "page": url is a video page for velo's yt-dlp support rather than a file. */
async function sendToVelo(url, pageUrl, title, fileName, mode, post) {
  let cookie = "";
  try {
    const cookies = await chrome.cookies.getAll({ url });
    cookie = cookies.map((c) => `${c.name}=${c.value}`).join("; ");
  } catch (e) {
    // Some URLs have no cookie store; send without.
  }
  const body = {
    url,
    referrer: pageUrl || "",
    cookie,
    userAgent: navigator.userAgent,
    title: title || "",
    fileName: fileName || null,
    mode: mode || "file",
    method: post ? post.method : null,
    bodyBase64: post ? post.bodyBase64 : null,
    contentType: post ? post.contentType : null,
  };
  try {
    const res = await fetch(`${VELO}/download`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) return { ok: false, error: `velo refused the link (HTTP ${res.status}).` };
    return { ok: true };
  } catch (e) {
    return { ok: false, error: NOT_RUNNING };
  }
}

// ---- remember POSTed requests, so a download a form started can be repeated by velo ----
// e.g. SharePoint/OneDrive "Download" on a folder POSTs the file list and a token to a zip
// service; fetching that address with a plain GET fails with HTTP 405.

const POST_TTL_MS = 2 * 60 * 1000;
const posts = new Map(); // url -> { method, bodyBase64, contentType, at }

function toBase64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function prunePosts() {
  const now = Date.now();
  for (const [url, p] of posts) if (now - p.at > POST_TTL_MS) posts.delete(url);
  while (posts.size > 50) posts.delete(posts.keys().next().value);
}

chrome.webRequest.onBeforeRequest.addListener(
  (d) => {
    if (d.method === "GET" || d.method === "HEAD" || d.tabId < 0) return;
    const entry = { method: d.method, bodyBase64: null, contentType: null, at: Date.now() };
    const body = d.requestBody;
    if (body && body.formData) {
      // A submitted form: send its fields again, URL-encoded.
      const form = new URLSearchParams();
      for (const [key, values] of Object.entries(body.formData)) for (const v of values) form.append(key, v);
      entry.bodyBase64 = btoa(form.toString());
      entry.contentType = "application/x-www-form-urlencoded";
    } else if (body && body.raw) {
      const parts = body.raw.filter((r) => r.bytes).map((r) => new Uint8Array(r.bytes));
      const all = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
      let at = 0;
      for (const p of parts) (all.set(p, at), (at += p.length));
      entry.bodyBase64 = toBase64(all);
    }
    posts.set(d.url, entry);
    prunePosts();
  },
  { urls: ["<all_urls>"], types: ["main_frame", "sub_frame", "xmlhttprequest", "other"] },
  ["requestBody"]
);

// A raw body's Content-Type is only known from the request headers.
chrome.webRequest.onBeforeSendHeaders.addListener(
  (d) => {
    const entry = posts.get(d.url);
    if (!entry || entry.contentType) return;
    const h = (d.requestHeaders || []).find((x) => x.name.toLowerCase() === "content-type");
    if (h) entry.contentType = h.value;
  },
  { urls: ["<all_urls>"], types: ["main_frame", "sub_frame", "xmlhttprequest", "other"] },
  ["requestHeaders"]
);

/** The method and body to repeat for a download, and the address to send them to. */
function postFor(item) {
  for (const url of [item.finalUrl, item.url]) {
    const p = url && posts.get(url);
    if (p && Date.now() - p.at <= POST_TTL_MS) {
      return { url, method: p.method, bodyBase64: p.bodyBase64, contentType: p.contentType };
    }
  }
  return null;
}

// ---- take over the browser's own downloads (like IDM) ----

const DEFAULTS = { capture: true, minSizeMB: 1 };

async function settings() {
  return { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
}

async function veloRunning() {
  try {
    const res = await fetch(`${VELO}/ping`, { signal: AbortSignal.timeout(1500) });
    return res.ok;
  } catch (e) {
    return false;
  }
}

/**
 * Decides whether velo takes a download, and if so hands it over and cancels the
 * browser's copy. Anything uncertain stays with the browser, so no download is lost.
 */
async function takeOver(item) {
  const s = await settings();
  if (!s.capture || item.byExtensionId || item.incognito) return false;
  const url = item.finalUrl || item.url;
  if (!/^https?:/i.test(url)) return false; // blob:, data:, file: only the browser can fetch
  const size = item.totalBytes > 0 ? item.totalBytes : item.fileSize;
  if (size > 0 && size < s.minSizeMB * 1024 * 1024) return false;
  if (!(await veloRunning())) return false;
  const name = item.filename ? item.filename.split(/[\\/]/).pop() : null;
  // A download a POSTed form started is repeated as that POST (sent to the address it went to).
  const post = postFor(item);
  const result = await sendToVelo(post ? post.url : url, item.referrer || "", "", name, "file", post);
  if (!result.ok) return false;
  try {
    await chrome.downloads.cancel(item.id);
    await chrome.downloads.erase({ id: item.id });
  } catch (e) {
    // Already gone; velo has it either way.
  }
  return true;
}

// Fires once the browser knows the file's name and size, before it saves anything.
// Answering asynchronously holds the download while velo is asked.
chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  takeOver(item)
    .then((taken) => {
      if (!taken) suggest();
    })
    .catch(() => suggest());
  return true;
});

async function updateBadge() {
  const s = await settings();
  await chrome.action.setBadgeText({ text: s.capture ? "" : "off" });
  await chrome.action.setBadgeBackgroundColor({ color: "#6b7280" });
}
chrome.storage.onChanged.addListener(updateBadge);
chrome.runtime.onStartup.addListener(updateBadge);

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const tabId = sender.tab ? sender.tab.id : -1;
  if (msg.type === "velo-status") {
    veloRunning().then((running) => sendResponse({ running }));
    return true;
  }
  if (msg.type === "velo-get-media") {
    getMedia(tabId).then(sendResponse, () => sendResponse([]));
    return true;
  }
  if (msg.type === "velo-download") {
    sendToVelo(msg.url, msg.pageUrl, msg.title, msg.fileName, msg.mode).then(sendResponse);
    return true;
  }
  return false;
});

// ---- right-click menu ----

chrome.runtime.onInstalled.addListener(() => {
  updateBadge();
  chrome.contextMenus.create({
    id: "velo-download",
    title: "Download with velo",
    contexts: ["link", "video", "audio", "image"],
  });
  chrome.contextMenus.create({
    id: "velo-page",
    title: "Download video on this page with velo",
    contexts: ["page", "video"],
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const notify = (text) => {
    if (tab && tab.id >= 0) {
      chrome.tabs.sendMessage(tab.id, { type: "velo-toast", text }, { frameId: info.frameId || 0 }).catch(() => {});
    }
  };
  if (info.menuItemId === "velo-page") {
    const page = info.frameUrl || info.pageUrl;
    const result = await sendToVelo(page, page, tab ? tab.title : "", null, "page");
    notify(result.ok ? "Sent to velo" : result.error);
    return;
  }
  const url = info.linkUrl || info.srcUrl;
  if (!url || !/^https?:/i.test(url)) {
    notify("This item has no downloadable address (it may be streamed).");
    return;
  }
  const result = await sendToVelo(url, info.frameUrl || info.pageUrl, tab ? tab.title : "", null);
  notify(result.ok ? "Sent to velo" : result.error);
});
