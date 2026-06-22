const state = {
  limit: 120,
  offset: 0,
  loading: false,
  total: 0,
};

const els = {
  login: document.getElementById("login"),
  app: document.getElementById("app"),
  password: document.getElementById("password"),
  loginButton: document.getElementById("loginButton"),
  refreshButton: document.getElementById("refreshButton"),
  requestAllButton: document.getElementById("requestAllButton"),
  searchInput: document.getElementById("searchInput"),
  albumSelect: document.getElementById("albumSelect"),
  typeSelect: document.getElementById("typeSelect"),
  gallery: document.getElementById("gallery"),
  loadMoreButton: document.getElementById("loadMoreButton"),
  toast: document.getElementById("toast"),
  status: document.getElementById("status"),
  deviceName: document.getElementById("deviceName"),
  photoCount: document.getElementById("photoCount"),
  videoCount: document.getElementById("videoCount"),
  storageUsed: document.getElementById("storageUsed"),
  lastSync: document.getElementById("lastSync"),
};

function showToast(message) {
  els.toast.textContent = message;
  els.toast.classList.remove("hidden");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => els.toast.classList.add("hidden"), 3200);
}

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function formatDate(seconds) {
  if (!seconds) return "Unknown";
  const millis = seconds < 1000000000000 ? seconds * 1000 : seconds;
  return new Date(millis).toLocaleString();
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename || "download";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function refreshStats() {
  const stats = await fetch("/stats").then((res) => res.json());
  const device = stats.devices?.[stats.devices.length - 1];
  els.deviceName.textContent = device?.device_name || "No device";
  els.photoCount.textContent = stats.photos || 0;
  els.videoCount.textContent = stats.videos || 0;
  els.storageUsed.textContent = formatBytes(stats.storage || 0);
  els.lastSync.textContent = stats.lastSync ? formatDate(stats.lastSync) : "Never";
  els.status.textContent = stats.total ? `${stats.total} files indexed` : "Waiting for phone sync";
}

async function refreshAlbums() {
  const current = els.albumSelect.value;
  const data = await fetch("/albums").then((res) => res.json());
  els.albumSelect.innerHTML = '<option value="">All albums</option>';
  for (const album of data.albums || []) {
    const option = document.createElement("option");
    option.value = album.name;
    option.textContent = `${album.name} (${album.count})`;
    els.albumSelect.appendChild(option);
  }
  els.albumSelect.value = current;
}

function mediaCard(item) {
  const card = document.createElement("article");
  card.className = "media-card";

  const thumb = document.createElement("div");
  thumb.className = "thumb";
  if (item.thumbnailUrl) {
    const image = document.createElement("img");
    image.src = item.thumbnailUrl;
    image.alt = item.name;
    thumb.appendChild(image);
  } else {
    thumb.textContent = item.type === "video" ? "Video" : "Image";
  }

  const body = document.createElement("div");
  body.className = "media-body";

  const title = document.createElement("div");
  title.className = "media-title";
  title.textContent = item.name || item.id;

  const meta = document.createElement("div");
  meta.className = "meta";
  meta.textContent = `${item.album || "Unknown"} | ${formatBytes(item.size)} | ${formatDate(item.dateModified)}`;

  const button = document.createElement("button");
  button.textContent = item.downloadReady ? "Download" : "Request Download";
  button.addEventListener("click", () => downloadMedia(item));

  body.append(title, meta, button);
  card.append(thumb, body);
  return card;
}

async function loadMedia(reset = false) {
  if (state.loading) return;
  state.loading = true;

  if (reset) {
    state.offset = 0;
    els.gallery.innerHTML = "";
  }

  const params = new URLSearchParams({
    limit: String(state.limit),
    offset: String(state.offset),
  });
  if (els.searchInput.value.trim()) params.set("q", els.searchInput.value.trim());
  if (els.albumSelect.value) params.set("album", els.albumSelect.value);
  if (els.typeSelect.value) params.set("type", els.typeSelect.value);

  const data = await fetch(`/media?${params}`).then((res) => res.json());
  state.total = data.total || 0;
  for (const item of data.items || []) {
    els.gallery.appendChild(mediaCard(item));
  }
  state.offset += (data.items || []).length;
  els.loadMoreButton.style.display = state.offset < state.total ? "block" : "none";
  state.loading = false;
}

async function refreshAll() {
  await refreshStats();
  await refreshAlbums();
  await loadMedia(true);
}

async function downloadMedia(item) {
  const response = await fetch(`/download/${encodeURIComponent(item.id)}`);
  if (response.status === 202) {
    showToast("Phone upload requested. Keep the Android app open.");
    pollDownload(item);
    return;
  }
  if (!response.ok) {
    showToast("Download failed.");
    return;
  }
  downloadBlob(await response.blob(), item.name || item.id);
}

async function pollDownload(item, tries = 30) {
  if (tries <= 0) {
    showToast("Still waiting. Try again after the phone uploads it.");
    return;
  }
  await new Promise((resolve) => setTimeout(resolve, 3000));
  const response = await fetch(`/download/${encodeURIComponent(item.id)}`);
  if (response.status === 202) {
    pollDownload(item, tries - 1);
    return;
  }
  if (response.ok) {
    showToast("Download ready.");
    downloadBlob(await response.blob(), item.name || item.id);
    refreshAll();
  }
}

function connectSocket() {
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${location.host}/ws/gallery`);
  socket.onmessage = (event) => {
    try {
      const message = JSON.parse(event.data);
      if (["SYNC_COMPLETED", "MEDIA_UPDATED", "DEVICE_CONNECTED"].includes(message.event)) {
        refreshAll();
      }
    } catch {
      refreshAll();
    }
  };
  socket.onclose = () => window.setTimeout(connectSocket, 3000);
}

els.loginButton.addEventListener("click", () => {
  if (els.password.value !== "local") {
    showToast('Password is "local" for this simple build.');
    return;
  }
  sessionStorage.setItem("gallery-sync-login", "yes");
  els.login.classList.add("hidden");
  els.app.classList.remove("hidden");
  refreshAll();
  connectSocket();
});

els.refreshButton.addEventListener("click", refreshAll);
els.loadMoreButton.addEventListener("click", () => loadMedia(false));
els.searchInput.addEventListener("input", () => loadMedia(true));
els.albumSelect.addEventListener("change", () => loadMedia(true));
els.typeSelect.addEventListener("change", () => loadMedia(true));
els.requestAllButton.addEventListener("click", async () => {
  const data = await fetch("/download-all/request", { method: "POST" }).then((res) => res.json());
  showToast(`${data.queued || 0} files queued. Keep the Android app open.`);
});

if (sessionStorage.getItem("gallery-sync-login") === "yes") {
  els.login.classList.add("hidden");
  els.app.classList.remove("hidden");
  refreshAll();
  connectSocket();
}
