// Queue the cover before the first paint, but time playback only after it can be shown.
(() => {
  const root = document.documentElement;
  const motion = matchMedia("(prefers-reduced-motion: reduce)");
  const path = location.pathname.replace(/\/$/, "") || "/";
  const eligible =
    ["/", "/index.html", "/join"].includes(path) &&
    !new URLSearchParams(location.search).has("room");
  const storageKey = "kilotto-opening-seen";
  let ready = false,
    state = "idle",
    seen = false,
    generation = 0;
  let frame = 0,
    timer = 0,
    deadline = 0,
    remaining = 0;

  function duration(name, fallback) {
    const value = getComputedStyle(root).getPropertyValue(name).trim();
    const match = value.match(/^(\d+(?:\.\d+)?)(ms|s)$/);
    return match ? Number(match[1]) * (match[2] === "s" ? 1000 : 1) : fallback;
  }
  function finish() {
    generation++;
    cancelAnimationFrame(frame);
    clearTimeout(timer);
    frame = timer = 0;
    state = "idle";
    root.classList.remove("opening-active");
    delete root.dataset.openingPhase;
    const opening = document.getElementById("opening");
    if (opening) opening.hidden = true;
  }
  function armTimer(delay) {
    clearTimeout(timer);
    remaining = delay;
    if (document.hidden) return;
    deadline = performance.now() + delay;
    timer = setTimeout(() => {
      timer = 0;
      if (state === "playing") exit();
      else if (state === "exiting") finish();
    }, delay);
  }
  function exit() {
    if (state !== "playing") return;
    state = "exiting";
    root.dataset.openingPhase = state;
    // This fallback starts with the exit, so loading or entry cannot consume it.
    armTimer(duration("--opening-exit", 450) + 150);
  }
  function play() {
    if (state !== "pending" || document.hidden || motion.matches) return;
    try {
      sessionStorage.setItem(storageKey, "1");
    } catch {
      finish();
      return;
    }
    seen = true;
    state = "playing";
    root.dataset.openingPhase = state;
    armTimer(
      duration("--opening-enter", 700) + duration("--opening-hold", 650),
    );
  }
  function preparePaint() {
    if (!ready || document.hidden || state !== "pending" || frame) return;
    const run = generation;
    frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (run === generation) play();
      });
    });
  }
  function start() {
    if (state !== "idle" || seen || motion.matches) return;
    try {
      if (sessionStorage.getItem(storageKey)) return;
    } catch {
      return;
    }
    state = "pending";
    root.dataset.openingPhase = state;
    root.classList.add("opening-active");
    const opening = document.getElementById("opening");
    if (opening) opening.hidden = false;
    preparePaint();
  }
  function visibilityChanged() {
    root.classList.toggle("motion-paused", document.hidden);
    if (document.hidden) {
      cancelAnimationFrame(frame);
      frame = 0;
      if (timer) {
        remaining = Math.max(0, deadline - performance.now());
        clearTimeout(timer);
        timer = 0;
      }
    } else if (state === "pending") preparePaint();
    else if (state === "playing" || state === "exiting") armTimer(remaining);
  }
  window.kilottoOpening = { start, finish };
  motion.addEventListener("change", () => {
    if (motion.matches) finish();
  });
  document.addEventListener("visibilitychange", visibilityChanged);
  visibilityChanged();
  document.addEventListener(
    "DOMContentLoaded",
    () => {
      ready = true;
      const opening = document.getElementById("opening");
      if (!opening) {
        finish();
        return;
      }
      opening.hidden = state === "idle";
      opening.addEventListener("animationend", (event) => {
        if (
          event.target === opening &&
          event.animationName === "opening-exit" &&
          state === "exiting"
        )
          finish();
      });
      preparePaint();
    },
    { once: true },
  );
  addEventListener("pagehide", finish);
  if (eligible) start();
})();
