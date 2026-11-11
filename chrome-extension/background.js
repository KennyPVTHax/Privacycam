/*
 * background.js — Service Worker
 *
 * Uses scripting.executeScript with world:'MAIN' to inject the camera
 * override DIRECTLY into the page world, bypassing any CSP restrictions.
 * This is the only reliable method for CSP-strict sites like Google Meet.
 */

// ── The override function (runs in page world, has full navigator access) ──
function fakeCamOverride(videoDataUrl) {
  "use strict";

  if (window.__FAKECAM_ACTIVE__) return; // already injected this page
  window.__FAKECAM_ACTIVE__ = true;

  // ── Build fake MediaStream ────────────────────────────────────────────
  let _fakeStream = null;

  function waitForBody() {
    return new Promise(resolve => {
      if (document.body) return resolve();
      const obs = new MutationObserver(() => {
        if (document.body) { obs.disconnect(); resolve(); }
      });
      obs.observe(document.documentElement, { childList: true });
    });
  }

  function canvasStream() {
    const canvas = document.createElement("canvas");
    canvas.width = 1280; canvas.height = 720;
    const ctx = canvas.getContext("2d");
    let frame = 0;
    (function draw() {
      const hue = (frame++ * 1.5) % 360;
      ctx.fillStyle = `hsl(${hue},55%,15%)`;
      ctx.fillRect(0, 0, 1280, 720);
      ctx.fillStyle = "rgba(255,255,255,0.9)";
      ctx.font = "bold 52px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("FakeCam Active", 640, 340);
      ctx.font = "24px sans-serif";
      ctx.fillStyle = "rgba(255,255,255,0.5)";
      ctx.fillText("Pick a video in the popup for realistic feed", 640, 395);
      requestAnimationFrame(draw);
    })();
    return canvas.captureStream(30);
  }

  async function buildFakeStream() {
    if (videoDataUrl && videoDataUrl.length > 0) {
      await waitForBody();
      return new Promise((resolve) => {
        const vid = document.createElement("video");
        vid.style.cssText = "position:fixed;top:-9999px;left:-9999px;width:1px;height:1px;opacity:0;pointer-events:none;";
        vid.loop = vid.muted = vid.autoplay = vid.playsInline = true;
        vid.src = videoDataUrl;
        document.body.appendChild(vid);
        const fallback = setTimeout(() => resolve(canvasStream()), 4000);
        vid.oncanplay = () => {
          clearTimeout(fallback);
          vid.play().then(() => resolve(vid.captureStream(30))).catch(() => resolve(canvasStream()));
        };
        vid.onerror = () => { clearTimeout(fallback); resolve(canvasStream()); };
      });
    }
    return canvasStream();
  }

  // ── Override getUserMedia ─────────────────────────────────────────────
  const _origGUM = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);

  navigator.mediaDevices.getUserMedia = async function(constraints) {
    if (constraints && constraints.audio && !constraints.video) {
      return _origGUM(constraints);
    }
    try {
      if (!_fakeStream) _fakeStream = await buildFakeStream();
      const tracks = _fakeStream.getVideoTracks().map(t => t.clone());
      if (constraints && constraints.audio) {
        try {
          const a = await _origGUM({ audio: true });
          a.getAudioTracks().forEach(t => tracks.push(t));
        } catch (_) {}
      }
      console.log("[BranchCam] ✓ getUserMedia intercepted — fake stream active");
      return new MediaStream(tracks);
    } catch (e) {
      console.error("[BranchCam] stream build failed:", e);
      throw e;
    }
  };

  // ── Override enumerateDevices ─────────────────────────────────────────
  const _origEnum = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);

  navigator.mediaDevices.enumerateDevices = async function() {
    let devices = [];
    try { devices = await _origEnum(); } catch (_) {}

    // Always ensure at least one videoinput is in the list
    const videoDevices = devices.filter(d => d.kind === "videoinput");

    const fakeVideoDevice = new Proxy(
      videoDevices.length > 0 ? videoDevices[0] : { kind: "videoinput", deviceId: "fakecam001", groupId: "fakecamgrp", toJSON() {} },
      {
        get(target, prop) {
          if (prop === "label") return "FHD WebCam (Generic)";
          if (prop === "kind")  return "videoinput";
          if (prop === "deviceId") return target.deviceId || "fakecam001";
          if (prop === "groupId")  return target.groupId  || "fakecamgrp";
          if (prop === "toJSON")   return () => ({ kind:"videoinput", label:"FHD WebCam (Generic)", deviceId: target.deviceId||"fakecam001", groupId: target.groupId||"fakecamgrp" });
          return Reflect.get(target, prop);
        }
      }
    );

    // Filter out real video devices, keep audio/other, inject our fake video
    const nonVideo = devices.filter(d => d.kind !== "videoinput");
    return [...nonVideo, fakeVideoDevice];
  };

  // ── Spoof getSupportedConstraints ─────────────────────────────────────
  navigator.mediaDevices.getSupportedConstraints = () => ({
    width:true, height:true, aspectRatio:true, frameRate:true,
    facingMode:true, resizeMode:true, sampleRate:true, sampleSize:true,
    echoCancellation:true, autoGainControl:true, noiseSuppression:true,
    latency:true, channelCount:true, deviceId:true, groupId:true,
  });

  console.log("[BranchCam] ✓ CSP-bypass injection complete — camera hidden.");
}

// ── Inject on every navigation ────────────────────────────────────────────
async function injectIntoTab(tabId) {
  const data = await chrome.storage.local.get(["fakecam_enabled", "fakecam_video_url"]);
  if (!data.fakecam_enabled) return;

  const videoUrl = data.fakecam_video_url || "";

  try {
    await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      world: "MAIN",           // ← page world, bypasses CSP
      func: fakeCamOverride,
      args: [videoUrl],
    });
  } catch (e) {
    // Some system pages can't be injected (chrome:// etc) — ignore
  }
}

// Inject at the earliest point: when the main frame commits
chrome.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId !== 0) return;          // top frame only for now
  injectIntoTab(details.tabId);
}, { url: [{ schemes: ["http", "https"] }] });

// Also inject when user enables the toggle (tab already open)
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === "REINJECT") injectIntoTab(msg.tabId);
});
