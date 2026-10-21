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
async function sendToVelo(url, pageUrl, title, fileName, mode) {
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

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const tabId = sender.tab ? sender.tab.id : -1;
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
