const toggle   = document.getElementById("toggle");
const dot      = document.getElementById("dot");
const badge    = document.getElementById("badge");
const pickBtn  = document.getElementById("pickBtn");
const clearBtn = document.getElementById("clearBtn");
const filePick = document.getElementById("filePicker");
const prevVid  = document.getElementById("prevVid");
const placeholder = document.getElementById("placeholder");
const filenameEl  = document.getElementById("filename");

// ── Load saved state ──────────────────────────────────────────────────────
chrome.storage.local.get(["fakecam_enabled", "fakecam_video_url", "fakecam_filename"], (d) => {
  setToggle(!!d.fakecam_enabled);
  if (d.fakecam_video_url) showPreview(d.fakecam_video_url, d.fakecam_filename || "video");
});

// ── Toggle ────────────────────────────────────────────────────────────────
toggle.addEventListener("change", () => {
  const on = toggle.checked;
  setToggle(on);
  chrome.storage.local.set({ fakecam_enabled: on }, () => {
    // Re-inject into current tab so user doesn't need to reload manually
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (!tabs[0]) return;
      if (on) {
        chrome.runtime.sendMessage({ type: "REINJECT", tabId: tabs[0].id });
      }
      chrome.tabs.reload(tabs[0].id);
    });
  });
});

function setToggle(on) {
  toggle.checked = on;
  dot.className = "dot" + (on ? " active" : "");
  badge.textContent = on ? "ON" : "OFF";
  badge.className = "badge" + (on ? " on" : "");
}

// ── File picker ───────────────────────────────────────────────────────────
pickBtn.addEventListener("click", () => filePick.click());

filePick.addEventListener("change", () => {
  const file = filePick.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = (e) => {
    const url = e.target.result;
    chrome.storage.local.set({ fakecam_video_url: url, fakecam_filename: file.name });
    showPreview(url, file.name);
  };
  reader.readAsDataURL(file);
});

// ── Clear ─────────────────────────────────────────────────────────────────
clearBtn.addEventListener("click", () => {
  chrome.storage.local.remove(["fakecam_video_url", "fakecam_filename"]);
  prevVid.style.display = "none";
  prevVid.src = "";
  placeholder.style.display = "";
  filenameEl.textContent = "—";
});

// ── Preview ───────────────────────────────────────────────────────────────
function showPreview(url, name) {
  prevVid.src = url;
  prevVid.style.display = "block";
  placeholder.style.display = "none";
  filenameEl.textContent = name || "video";
}
