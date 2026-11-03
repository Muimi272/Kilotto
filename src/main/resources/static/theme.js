// Runs before styles load, so a saved dark theme never flashes a light page.
(() => {
  const key = "kilotto-theme";
  const system = matchMedia("(prefers-color-scheme: dark)");
  const valid = (value) =>
    ["light", "dark", "system"].includes(value) ? value : "system";
  let preference = "system";
  try {
    preference = valid(localStorage.getItem(key));
  } catch {
    /* Storage may be unavailable in private contexts. */
  }
  function apply() {
    const resolved =
      preference === "system"
        ? system.matches
          ? "dark"
          : "light"
        : preference;
    document.documentElement.dataset.theme = resolved;
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", resolved === "dark" ? "#171a16" : "#f6f3e9");
    const selector = document.getElementById("theme-select");
    if (selector) selector.value = preference;
    window.dispatchEvent(
      new CustomEvent("kilotto-theme-change", { detail: preference }),
    );
  }
  window.kilottoTheme = {
    get preference() {
      return preference;
    },
    set(value) {
      preference = valid(value);
      try {
        localStorage.setItem(key, preference);
      } catch {
        /* Keep the choice for this page. */
      }
      apply();
    },
  };
  system.addEventListener("change", () => {
    if (preference === "system") apply();
  });
  addEventListener("storage", (event) => {
    if (event.key === key || event.key === null) {
      preference = valid(event.newValue);
      apply();
    }
  });
  apply();
})();
