/* ═══════════════════════════════════════════════════════
   DualCam Recorder — JS bridge + UI controller
   Requires: Capacitor native app (com.gkk.app)
═══════════════════════════════════════════════════════ */

const DualCam = (() => {

  // ── Plugin handle ──────────────────────────────────────────────────────
  // Cap 6 plugin bridge
  let _plugin = null;

  function Plugin() {
    if (_plugin) return _plugin;
    const cap = window.Capacitor;
    if (!cap) return null;
    // Try direct Plugins object first (populated by native bridge on load)
    if (cap.Plugins && cap.Plugins.DualCamRecorder) {
      _plugin = cap.Plugins.DualCamRecorder;
      return _plugin;
    }
    // Fallback: registerPlugin proxy
    try {
      if (typeof cap.registerPlugin === 'function') {
        // Only use if native bridge confirms plugin is available
        if (typeof cap.isPluginAvailable === 'function' && cap.isPluginAvailable('DualCamRecorder')) {
          _plugin = cap.registerPlugin('DualCamRecorder');
          return _plugin;
        }
        // Last resort: register and hope
        _plugin = cap.registerPlugin('DualCamRecorder');
        return _plugin;
      }
    } catch (e) {
      console.warn('[DualCam] registerPlugin failed:', e);
    }
    return null;
  }

  const isNative = () => !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());

  // ── State ──────────────────────────────────────────────────────────────
  let recording      = false;
  let elapsedSecs    = 0;
  let timerInterval  = null;
  let selectedMode   = 'background';

  // ── Plugin calls ────────────────────────────────────────────────────────
  async function startRec() {
    if (!isNative()) {
      dcToast('⚠️ Native app required — install the GKK APK');
      return;
    }
    const p = Plugin();
    if (!p) {
      dcToast('⚠️ DualCamRecorder plugin not found');
      return;
    }
    try {
      const res = await p.startRecording({ mode: selectedMode });
      if (res && res.started) {
        recording   = true;
        elapsedSecs = 0;
        renderRecorderUI();
        startTimer();
        dcToast('🔴 Recording started — both cameras active');
      } else {
        dcToast('⚠️ Could not start recording');
      }
    } catch (e) {
      const msg = e && (e.message || e.code || JSON.stringify(e));
      dcToast('❌ ' + msg);
      console.error('[DualCam] startRecording error', JSON.stringify(e));
    }
  }

  async function stopRec() {
    if (!isNative()) return;
    stopTimer();
    dcToast('⏹ Stopping…');
    const p = Plugin();
    if (!p) return;
    try {
      const res = await p.stopRecording();
      recording = false;
      renderRecorderUI();
      if (res && (res.backPath || res.frontPath)) {
        dcToast('✅ Saved to Downloads/DualCam');
        await loadRecordings();
      }
    } catch (e) {
      dcToast('Stop error: ' + (e.message || e));
      recording = false;
      renderRecorderUI();
    }
  }

  async function loadRecordings() {
    if (!isNative()) { renderBrowserFallback(); return; }
    const p = Plugin();
    if (!p) return;
    try {
      const res = await p.getRecordings();
      renderRecordingsList(res.recordings || []);
    } catch (e) {
      console.warn('[DualCam] getRecordings error', e);
    }
  }

  async function deleteRec(timestamp) {
    if (!isNative() || !confirm('Delete this recording pair?')) return;
    const p = Plugin();
    if (!p) return;
    try {
      await p.deleteRecording({ timestamp });
      dcToast('Deleted');
      await loadRecordings();
    } catch (e) {
      dcToast('Delete error: ' + (e.message || e));
    }
  }

  async function checkStatus() {
    if (!isNative()) return;
    const p = Plugin();
    if (!p) return;
    try {
      const res = await p.isRecording();
      recording   = res.recording;
      elapsedSecs = res.elapsed || 0;
      if (recording) startTimer();
      renderRecorderUI();
    } catch (_) {}
  }

  // ── Timer ────────────────────────────────────────────────────────────
  function startTimer() {
    stopTimer();
    timerInterval = setInterval(() => {
      elapsedSecs++;
      const el = document.getElementById('dc-timer');
      if (el) el.textContent = fmtTime(elapsedSecs);
    }, 1000);
  }

  function stopTimer() {
    clearInterval(timerInterval);
    timerInterval = null;
  }

  function fmtTime(s) {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    const pad = n => String(n).padStart(2, '0');
    return h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
  }

  // ── Render helpers ────────────────────────────────────────────────────
  function renderRecorderUI() {
    const btn     = document.getElementById('dc-rec-btn');
    const status  = document.getElementById('dc-status');
    const timerEl = document.getElementById('dc-timer');
    const modeBox = document.getElementById('dc-mode-box');
    const pulse   = document.getElementById('dc-pulse');
    if (!btn) return;

    if (recording) {
      btn.textContent    = '⏹ Stop Recording';
      btn.className      = 'dc-btn dc-btn-stop';
      btn.onclick        = stopRec;
      status.textContent = '🔴 Recording in progress…';
      status.className   = 'dc-status dc-status-rec';
      if (timerEl) timerEl.textContent = fmtTime(elapsedSecs);
      if (modeBox) modeBox.style.opacity = '0.4';
      if (pulse)   pulse.style.display  = 'block';
    } else {
      btn.textContent    = '⏺ Start Recording';
      btn.className      = 'dc-btn dc-btn-start';
      btn.onclick        = startRec;
      status.textContent = '⬤ Ready';
      status.className   = 'dc-status dc-status-idle';
      if (timerEl) timerEl.textContent = '00:00';
      if (modeBox) modeBox.style.opacity = '1';
      if (pulse)   pulse.style.display  = 'none';
    }
  }

  function selectMode(mode) {
    selectedMode = mode;
    document.querySelectorAll('.dc-mode-card').forEach(c => c.classList.remove('dc-mode-active'));
    const card = document.getElementById('dc-mode-' + mode);
    if (card) card.classList.add('dc-mode-active');
  }

  function renderRecordingsList(recs) {
    const el = document.getElementById('dc-recordings');
    if (!el) return;
    if (!recs.length) {
      el.innerHTML = `<div class="empty-state" style="padding:32px 0">
        <div class="empty-state-icon">🎥</div>
        <div class="empty-state-text">No recordings yet</div>
        <div class="empty-state-sub">Recordings save to Downloads/DualCam</div>
      </div>`;
      return;
    }
    el.innerHTML = recs.map(r => `
      <div class="dc-rec-row card" style="padding:14px 16px;margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
          <div>
            <div style="font-weight:700;font-size:13px">${r.date}</div>
            <div style="font-size:11px;color:var(--muted);margin-top:2px">Total: ${r.totalMB} MB</div>
          </div>
          <button onclick="DualCam.deleteRec('${r.timestamp}')" style="background:none;border:none;color:#ef4444;font-size:13px;cursor:pointer;padding:4px 8px">🗑 Delete</button>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
          <div style="background:var(--bg2);border-radius:10px;padding:10px;text-align:center">
            <div style="font-size:18px;margin-bottom:4px">📷</div>
            <div style="font-size:12px;font-weight:600">Back Camera</div>
            <div style="font-size:11px;color:var(--muted)">${r.backPath ? fmtSize(r.backSize) : 'Not saved'}</div>
            <div style="font-size:10px;color:var(--muted);margin-top:2px">Video + Audio</div>
          </div>
          <div style="background:var(--bg2);border-radius:10px;padding:10px;text-align:center">
            <div style="font-size:18px;margin-bottom:4px">🤳</div>
            <div style="font-size:12px;font-weight:600">Front Camera</div>
            <div style="font-size:11px;color:var(--muted)">${r.frontPath ? fmtSize(r.frontSize) : 'Not saved'}</div>
            <div style="font-size:10px;color:var(--muted);margin-top:2px">Video only</div>
          </div>
        </div>
        <div style="margin-top:8px;font-size:10px;color:var(--muted);text-align:center">
          📁 Also in Downloads/DualCam
        </div>
      </div>
    `).join('');
  }

  function renderBrowserFallback() {
    const el = document.getElementById('dc-recordings');
    if (el) el.innerHTML = `<div class="empty-state" style="padding:24px 0">
      <div class="empty-state-icon">📱</div>
      <div class="empty-state-text">Native App Required</div>
      <div class="empty-state-sub">Build and install the GKK APK to use dual-camera recording</div>
    </div>`;
  }

  function fmtSize(bytes) {
    if (!bytes) return '—';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return (bytes / 1_048_576).toFixed(1) + ' MB';
  }

  function dcToast(msg) {
    if (typeof toast === 'function') toast(msg);
    else {
      // fallback visible alert if toast() not available
      const t = document.createElement('div');
      t.textContent = msg;
      t.style.cssText = 'position:fixed;bottom:80px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 18px;border-radius:20px;font-size:13px;z-index:9999;max-width:80vw;text-align:center';
      document.body.appendChild(t);
      setTimeout(() => t.remove(), 3000);
    }
  }

  // ── Public API ────────────────────────────────────────────────────────
  return {
    init:       () => { checkStatus().then(loadRecordings); },
    startRec,
    stopRec,
    loadRecordings,
    deleteRec,
    selectMode,
  };
})();
