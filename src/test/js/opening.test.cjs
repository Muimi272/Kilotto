const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const vm = require("node:vm");

const staticRoot = resolve(__dirname, "../../main/resources/static");
const source = readFileSync(resolve(staticRoot, "opening.js"), "utf8");
const css = readFileSync(resolve(staticRoot, "style.css"), "utf8");

function browser({
  path = "/join",
  search = "",
  hidden = false,
  reduced = false,
  storageWorks = true,
} = {}) {
  let now = 0,
    id = 0,
    mounted = false;
  const jobs = new Map(),
    storage = new Map(),
    documentEvents = new Map(),
    windowEvents = new Map(),
    motionEvents = new Map();
  const classes = new Set();
  const root = {
    dataset: {},
    classList: {
      add: (value) => classes.add(value),
      remove: (value) => classes.delete(value),
      contains: (value) => classes.has(value),
      toggle: (value, on) => (on ? classes.add(value) : classes.delete(value)),
    },
  };
  const openingEvents = new Map();
  const opening = {
    hidden: false,
    addEventListener: (name, fn) => openingEvents.set(name, fn),
  };
  const document = {
    documentElement: root,
    hidden,
    getElementById: () => (mounted ? opening : null),
    addEventListener: (name, fn) => documentEvents.set(name, fn),
  };
  const motion = {
    matches: reduced,
    addEventListener: (name, fn) => motionEvents.set(name, fn),
  };
  function schedule(fn, delay) {
    jobs.set(++id, { fn, at: now + delay });
    return id;
  }
  const context = {
    document,
    location: { pathname: path, search },
    URLSearchParams,
    matchMedia: () => motion,
    performance: { now: () => now },
    setTimeout: schedule,
    clearTimeout: (key) => jobs.delete(key),
    requestAnimationFrame: (fn) => schedule(fn, 16),
    cancelAnimationFrame: (key) => jobs.delete(key),
    getComputedStyle: () => ({
      getPropertyValue: (name) =>
        css.match(new RegExp(`${name}:\\s*([^;]+)`))?.[1] || "",
    }),
    addEventListener: (name, fn) => windowEvents.set(name, fn),
    sessionStorage: {
      getItem(key) {
        if (!storageWorks) throw new Error("Storage unavailable");
        return storage.get(key) || null;
      },
      setItem(key, value) {
        if (!storageWorks) throw new Error("Storage unavailable");
        storage.set(key, value);
      },
    },
  };
  context.window = context;
  vm.runInNewContext(source, context);
  return {
    root,
    opening,
    storage,
    api: context.kilottoOpening,
    get phase() {
      return root.dataset.openingPhase;
    },
    get active() {
      return classes.has("opening-active");
    },
    ready() {
      mounted = true;
      documentEvents.get("DOMContentLoaded")();
    },
    advance(ms) {
      const end = now + ms;
      for (;;) {
        const next = [...jobs.entries()]
          .filter(([, job]) => job.at <= end)
          .sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at;
        jobs.delete(next[0]);
        next[1].fn();
      }
      now = end;
    },
    visibility(value) {
      document.hidden = value;
      documentEvents.get("visibilitychange")();
    },
    reduce(value) {
      motion.matches = value;
      motionEvents.get("change")();
    },
    end(name = "opening-exit", target = opening) {
      openingEvents.get("animationend")({ target, animationName: name });
    },
  };
}

test("slow loading does not consume the visible entry and hold", () => {
  const b = browser();
  b.advance(5000);
  assert.equal(b.phase, "pending");
  assert.equal(b.storage.size, 0);
  b.ready();
  b.advance(32);
  assert.equal(b.phase, "playing");
  b.advance(1349);
  assert.equal(b.phase, "playing");
  b.advance(1);
  assert.equal(b.phase, "exiting");
});

test("child animations and unrelated container events cannot end playback", () => {
  const b = browser();
  b.ready();
  b.advance(32);
  b.end("opening-spark-in", {});
  b.end("opening-exit");
  assert.equal(b.phase, "playing");
  b.advance(1350);
  b.end("opening-word-in");
  assert.equal(b.phase, "exiting");
  b.end();
  assert.equal(b.opening.hidden, true);
  assert.equal(b.active, false);
});

test("missing exit events use a timeout measured from the exit stage", () => {
  const b = browser();
  b.ready();
  b.advance(32);
  b.advance(1200);
  assert.equal(b.phase, "playing");
  b.advance(150);
  assert.equal(b.phase, "exiting");
  b.advance(599);
  assert.equal(b.active, true);
  b.advance(1);
  assert.equal(b.active, false);
  assert.equal(b.opening.hidden, true);
});

test("background time does not shorten entry, hold, or exit", () => {
  const b = browser();
  b.ready();
  b.advance(32 + 500);
  b.visibility(true);
  b.advance(10000);
  assert.equal(b.phase, "playing");
  b.visibility(false);
  b.advance(849);
  assert.equal(b.phase, "playing");
  b.advance(1);
  assert.equal(b.phase, "exiting");
  b.visibility(true);
  b.advance(10000);
  assert.equal(b.phase, "exiting");
  b.visibility(false);
  b.advance(600);
  assert.equal(b.active, false);
});

test("a background tab waits to become visible before marking playback seen", () => {
  const b = browser({ hidden: true });
  b.ready();
  b.advance(5000);
  assert.equal(b.phase, "pending");
  assert.equal(b.storage.size, 0);
  b.visibility(false);
  b.advance(32);
  assert.equal(b.phase, "playing");
  assert.equal(b.storage.size, 1);
});

test("navigation cancels queued frames without resurrecting the cover", () => {
  const b = browser();
  b.ready();
  b.api.finish();
  b.advance(5000);
  assert.equal(b.active, false);
  assert.equal(b.opening.hidden, true);
  assert.equal(b.storage.size, 0);
  b.api.start();
  b.advance(32);
  assert.equal(b.phase, "playing");
  b.api.start();
  b.advance(1350);
  assert.equal(b.phase, "exiting");
  b.api.finish();
  b.api.start();
  b.advance(5000);
  assert.equal(b.active, false);
});

test("reduced motion and unavailable storage skip the opening", () => {
  for (const options of [{ reduced: true }, { storageWorks: false }]) {
    const b = browser(options);
    b.ready();
    b.advance(5000);
    assert.equal(b.active, false);
    assert.equal(b.opening.hidden, true);
  }
  const b = browser();
  b.ready();
  b.advance(32);
  b.reduce(true);
  assert.equal(b.active, false);
});

test("room links, legacy invites, and login do not start an opening", () => {
  for (const options of [
    { path: "/room/ABCDEFGH" },
    { path: "/", search: "?room=ABCDEFGH" },
    { path: "/login" },
  ]) {
    const b = browser(options);
    b.ready();
    b.advance(5000);
    assert.equal(b.active, false);
    assert.equal(b.storage.size, 0);
  }
});
