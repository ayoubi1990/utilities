// The toolbar popup: whether velo is running, and the download-takeover settings.
const DEFAULTS = { capture: true, minSizeMB: 1 };
const capture = document.getElementById("capture");
const minSize = document.getElementById("minSize");
const status = document.getElementById("status");

chrome.storage.local.get(Object.keys(DEFAULTS)).then((stored) => {
  const s = { ...DEFAULTS, ...stored };
  capture.checked = s.capture;
  minSize.value = s.minSizeMB;
});

capture.addEventListener("change", () => chrome.storage.local.set({ capture: capture.checked }));
minSize.addEventListener("change", () => {
  const mb = Math.max(0, Number(minSize.value) || 0);
  minSize.value = mb;
  chrome.storage.local.set({ minSizeMB: mb });
});

chrome.runtime.sendMessage({ type: "velo-status" }, (res) => {
  const running = !chrome.runtime.lastError && res && res.running;
  status.className = "status " + (running ? "on" : "off");
  status.textContent = running
    ? "velo is running. Downloads go to velo."
    : "velo is not running. Downloads stay in the browser until you start it.";
});
