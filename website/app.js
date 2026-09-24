/**
 * PenBridge 3D Showcase Script
 * OS Detection, 3D Parallax, Live Drawing Surface, and Protocol Stream
 */

document.addEventListener('DOMContentLoaded', () => {
  initOSDetection();
  init3DParallax();
  initDrawingCanvas();
  initProtocolStream();
  initStarfield();
  initMobileNav();
  initScrollReveal();
});

/* ─────────────────────────────────────────────────────────────
   1. OS Detection & Dynamic Download CTA
   ───────────────────────────────────────────────────────────── */
function initOSDetection() {
  const ua = navigator.userAgent.toLowerCase();
  const heroBtn = document.getElementById('hero-os-btn');
  const heroTitle = document.getElementById('hero-os-title');
  const heroSubtext = document.getElementById('hero-os-subtext');
  const heroIcon = document.getElementById('hero-os-icon');

  const releaseBase = 'https://github.com/GTXPOFFIC-developer/PenBridge/releases/download/v1.3.0/';

  let detectedOS = 'windows';
  let downloadUrl = releaseBase + 'PenBridge-Windows-x64-v1.3.0.exe';
  let title = 'PenBridge Installer (.exe)';
  let subtext = 'Download for Windows';
  let iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451L0 20.699M10.949 12.6H24V24l-12.9-1.801"/></svg>`;

  if (/iphone|ipad|ipod/.test(ua)) {
    detectedOS = 'ios';
    downloadUrl = releaseBase + 'Dashboard-iOS-v1.3.0.zip';
    title = 'PenBridge for iPad (IPA)';
    subtext = 'Download for iOS';
    iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>`;
  } else if (/android/.test(ua)) {
    detectedOS = 'android';
    downloadUrl = releaseBase + 'PenBridge-Android-v1.3.0.apk';
    title = 'PenBridge Android APK';
    subtext = 'Download for Android';
    iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.551 0 .9993.4482.9993.9993.0001.5511-.4483.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4482.9993.9993 0 .5511-.4482.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.411 13.856 8.1 12 8.1s-3.5902.311-5.1368.8507L4.8409 5.4477a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.152 5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3432-4.1021-2.6889-7.5743-6.1185-9.4396"/></svg>`;
  } else if (/macintosh|mac os x/.test(ua)) {
    detectedOS = 'macos';
    downloadUrl = releaseBase + 'DashboardHost-macOS-v1.3.0.zip';
    title = 'PenBridge for Mac (.zip)';
    subtext = 'Download for macOS';
    iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M15.97 6.37c.62-.75 1.04-1.8 0.92-2.85-.9.04-1.99.6-2.64 1.35-.58.67-.09 1.74.05 2.78.99.08 2.05-.53 2.67-1.28"/></svg>`;
  } else if (/linux/.test(ua)) {
    detectedOS = 'linux';
    downloadUrl = releaseBase + 'DashboardHost-Linux-v1.3.0.tar.gz';
    title = 'PenBridge Daemon (.tar.gz)';
    subtext = 'Download for Linux';
    iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><circle cx="12" cy="12" r="10"/></svg>`;
  }

  if (heroBtn) {
    heroBtn.href = downloadUrl;
    if (heroTitle) heroTitle.textContent = title;
    if (heroSubtext) heroSubtext.textContent = subtext;
    if (heroIcon) heroIcon.innerHTML = iconSvg;
  }

  // Highlight corresponding card in download section
  const targetCard = document.getElementById(`card-${detectedOS}`);
  if (targetCard) {
    targetCard.classList.add('highlight');
  }
}

/* ─────────────────────────────────────────────────────────────
   2. 3D Perspective Tilt on Hero Showcase
   ───────────────────────────────────────────────────────────── */
function init3DParallax() {
  const card = document.getElementById('shot-3d-card');
  if (!card) return;

  const frame = card.closest('.shot-frame');
  if (!frame) return;

  let isHovered = false;

  frame.addEventListener('mouseenter', () => {
    isHovered = true;
  });

  frame.addEventListener('mousemove', (e) => {
    if (!isHovered) return;
    const rect = frame.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    const centerX = rect.width / 2;
    const centerY = rect.height / 2;

    const rotateX = ((y - centerY) / centerY) * -12; // -12deg to +12deg
    const rotateY = ((x - centerX) / centerX) * 14;

    card.style.transform = `rotateX(${rotateX}deg) rotateY(${rotateY}deg)`;
  });

  frame.addEventListener('mouseleave', () => {
    isHovered = false;
    card.style.transform = 'rotateX(0deg) rotateY(0deg)';
  });
}

/* ─────────────────────────────────────────────────────────────
   3. Interactive Live Digitizer Canvas
   ───────────────────────────────────────────────────────────── */
function initDrawingCanvas() {
  const canvas = document.getElementById('drawing-canvas');
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  const container = canvas.parentElement;
  const hint = document.getElementById('canvas-hint');
  const hzEl = document.getElementById('demo-hz');
  const pressureEl = document.getElementById('demo-pressure');
  const tiltEl = document.getElementById('demo-tilt');
  const clearBtn = document.getElementById('demo-clear');
  const colorPicker = document.getElementById('brush-color');
  const sizeSlider = document.getElementById('brush-size');
  const sizeVal = document.getElementById('brush-size-val');

  // Handle Retina / HiDPI
  function resizeCanvas() {
    const dpr = window.devicePixelRatio || 1;
    const rect = container.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
  }

  resizeCanvas();
  window.addEventListener('resize', resizeCanvas);

  let isDrawing = false;
  let lastX = 0;
  let lastY = 0;
  let sampleCount = 0;
  let lastSampleTime = performance.now();

  // Hz sample counter interval
  setInterval(() => {
    const now = performance.now();
    const elapsed = (now - lastSampleTime) / 1000;
    if (elapsed > 0) {
      const hz = Math.round(sampleCount / elapsed);
      if (hzEl) hzEl.textContent = `${hz} Hz`;
    }
    sampleCount = 0;
    lastSampleTime = now;
  }, 500);

  function getPos(e) {
    const rect = canvas.getBoundingClientRect();
    return {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
      pressure: (e.pressure !== undefined && e.pressure > 0) ? e.pressure : 0.5,
      tiltX: e.tiltX || 0,
      tiltY: e.tiltY || 0
    };
  }

  function startDraw(e) {
    e.preventDefault();
    isDrawing = true;
    if (hint) hint.style.opacity = '0';

    const pos = getPos(e);
    lastX = pos.x;
    lastY = pos.y;
    sampleCount++;

    updateMetrics(pos);
  }

  function draw(e) {
    if (!isDrawing) return;
    e.preventDefault();
    sampleCount++;

    const pos = getPos(e);
    const baseSize = parseFloat(sizeSlider.value) || 4;
    const strokeWidth = Math.max(1, baseSize * (pos.pressure * 1.8));

    ctx.strokeStyle = colorPicker.value;
    ctx.lineWidth = strokeWidth;
    ctx.shadowBlur = 10;
    ctx.shadowColor = colorPicker.value;

    ctx.beginPath();
    ctx.moveTo(lastX, lastY);
    ctx.lineTo(pos.x, pos.y);
    ctx.stroke();

    lastX = pos.x;
    lastY = pos.y;

    updateMetrics(pos);
  }

  function stopDraw() {
    isDrawing = false;
    ctx.shadowBlur = 0;
  }

  function updateMetrics(pos) {
    if (pressureEl) pressureEl.textContent = pos.pressure.toFixed(2);
    if (tiltEl) tiltEl.textContent = `${pos.tiltX}° / ${pos.tiltY}°`;
  }

  canvas.addEventListener('pointerdown', startDraw);
  canvas.addEventListener('pointermove', draw);
  canvas.addEventListener('pointerup', stopDraw);
  canvas.addEventListener('pointercancel', stopDraw);
  canvas.addEventListener('pointerleave', stopDraw);

  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (hint) hint.style.opacity = '1';
    });
  }

  if (sizeSlider && sizeVal) {
    sizeSlider.addEventListener('input', () => {
      sizeVal.textContent = `${sizeSlider.value}px`;
    });
  }
}

/* ─────────────────────────────────────────────────────────────
   4. Protocol Wire Stream Simulator
   ───────────────────────────────────────────────────────────── */
function initProtocolStream() {
  const streamEl = document.getElementById('hex-stream');
  if (!streamEl) return;

  function randomByte() {
    return Math.floor(Math.random() * 256).toString(16).padStart(2, '0').toUpperCase();
  }

  function updatePacket() {
    // 24-byte packet:
    // Magic: 50 42 (PB)
    // Event: 02 (Move)
    // Tool: 01 (Pen)
    // X (float32): 4 bytes
    // Y (float32): 4 bytes
    // Pressure (float32): 4 bytes
    // TiltX (int16): 2 bytes
    // TiltY (int16): 2 bytes
    // Timestamp/Flags: 4 bytes
    const packet = [
      '50', '42',
      '02', '01',
      randomByte(), randomByte(), randomByte(), randomByte(),
      randomByte(), randomByte(), randomByte(), randomByte(),
      '3F', randomByte(), randomByte(), randomByte(),
      '00', randomByte(),
      'FF', randomByte(),
      '00', '01', randomByte(), randomByte()
    ].join(' ');

    streamEl.textContent = packet;
  }

  setInterval(updatePacket, 120); // ~8 packets/sec visual update
}

/* ─────────────────────────────────────────────────────────────
   5. Background Starfield Canvas
   ───────────────────────────────────────────────────────────── */
function initStarfield() {
  const canvas = document.getElementById('starfield');
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  let stars = [];
  const count = 75;

  function resize() {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    stars = [];
    for (let i = 0; i < count; i++) {
      stars.push({
        x: Math.random() * canvas.width,
        y: Math.random() * canvas.height,
        size: Math.random() * 1.5 + 0.5,
        alpha: Math.random() * 0.7 + 0.2,
        speed: Math.random() * 0.2 + 0.05
      });
    }
  }

  resize();
  window.addEventListener('resize', resize);

  function render() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const star of stars) {
      star.y -= star.speed;
      if (star.y < 0) {
        star.y = canvas.height;
        star.x = Math.random() * canvas.width;
      }
      ctx.fillStyle = `rgba(180, 210, 255, ${star.alpha})`;
      ctx.beginPath();
      ctx.arc(star.x, star.y, star.size, 0, Math.PI * 2);
      ctx.fill();
    }
    requestAnimationFrame(render);
  }

  requestAnimationFrame(render);
}

/* ─────────────────────────────────────────────────────────────
   6. Mobile Navigation & Scroll Reveal
   ───────────────────────────────────────────────────────────── */
function initMobileNav() {
  const toggle = document.getElementById('nav-toggle');
  const links = document.getElementById('nav-links');

  if (toggle && links) {
    toggle.addEventListener('click', () => {
      const expanded = toggle.getAttribute('aria-expanded') === 'true';
      toggle.setAttribute('aria-expanded', !expanded);
      links.classList.toggle('active');
    });

    links.querySelectorAll('a').forEach(link => {
      link.addEventListener('click', () => {
        links.classList.remove('active');
        toggle.setAttribute('aria-expanded', 'false');
      });
    });
  }
}

function initScrollReveal() {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
      }
    });
  }, { threshold: 0.1 });

  document.querySelectorAll('[data-reveal]').forEach(el => {
    observer.observe(el);
  });
}
