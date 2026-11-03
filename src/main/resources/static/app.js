"use strict";

const $ = (id) => document.getElementById(id);
const validCode = (value) => /^[2-9A-HJ-NP-Z]{8}$/.test(value);
let user = null,
  csrf = null,
  csrfFlight = null,
  identityVersion = 0;
let socket = null,
  room = null,
  connectionRequest = null,
  connectionAttempt = 0;
let reconnectTimer,
  reconnectAttempts = 0,
  noticeTimer,
  confirmation;
let participantId = null,
  observer = false,
  renderedRound = "",
  rosterKey = "",
  historyKey = "";
let initialized = false,
  authenticationBusy = false,
  roomSummaries = [],
  dashboardVersion = 0;
let userPage = 0,
  userHasNext = false,
  usersVersion = 0,
  usersLoading = false;
let routeVersion = 0,
  currentRoute = null,
  returnTo = "/rooms";
const pendingDraws = new Map();
const busyButtons = new WeakSet();
const authChannel =
  typeof BroadcastChannel === "function"
    ? new BroadcastChannel("kilotto-auth")
    : null;
const dateFormat = new Intl.DateTimeFormat("zh-CN", {
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function icon(name, className = "") {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", `icon ${className}`);
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  const use = document.createElementNS(svg.namespaceURI, "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
}
function buttonLabel(button, text, name) {
  button.replaceChildren(element("span", text), icon(name));
}
function link(text, href, className = "", glyph = "arrow-up-right") {
  const node = element("a", undefined, className);
  node.href = href;
  node.dataset.route = "";
  node.append(element("span", text), icon(glyph));
  return node;
}
function action(text, callback, className = "quiet") {
  const button = element("button", text, className);
  button.type = "button";
  button.addEventListener("click", () => run(button, callback));
  return button;
}
function notice(text) {
  $("notice-text").textContent = text;
  $("notice").hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    $("notice").hidden = true;
  }, 8000);
}
function formError(id, text = "") {
  $(id).textContent = text;
  $(id).hidden = !text;
}
function emptyState(title, control) {
  const node = element("div", undefined, "empty-state");
  node.append(icon("spark", "empty-star"), element("h3", title));
  if (control) node.append(control);
  return node;
}
function resetPassword(id) {
  $(id).value = "";
  $(id).type = "password";
  const button = document.querySelector(`[data-password="${id}"]`);
  button.replaceChildren(icon("eye"));
  button.setAttribute("aria-pressed", "false");
  button.setAttribute(
    "aria-label",
    id === "password" ? "显示密码" : "显示初始密码",
  );
}
function setUser(value) {
  if (user?.id !== value?.id || user?.role !== value?.role) {
    userPage = 0;
    pendingDraws.clear();
    $("create-user-form").reset();
    resetPassword("new-password");
    resetPassword("password");
    $("room-title").value = "";
    $("room-search").value = "";
    $("room-filter").value = "ALL";
  }
  user = value;
  identityVersion++;
  dashboardVersion++;
  usersVersion++;
  usersLoading = false;
  csrf = null;
  csrfFlight = null;
  roomSummaries = [];
  $("users").replaceChildren();
  $("room-list").replaceChildren();
  $("rooms-total").textContent = "0";
  if ($("confirm-dialog").open) $("confirm-dialog").close();
  renderAccount();
}
function renderAccount() {
  $("identity").textContent = user
    ? `${user.username} / ${user.role === "ADMIN" ? "管理员" : "房主"}`
    : "";
  buttonLabel(
    $("account-button"),
    user ? "退出登录" : "房主登录",
    user ? "logout" : "arrow-up-right",
  );
  document.querySelectorAll("[data-auth]").forEach((node) => {
    node.hidden = !user;
  });
  document.querySelectorAll("[data-admin]").forEach((node) => {
    node.hidden = user?.role !== "ADMIN";
  });
}
function confirmAction(title, description, label) {
  if (confirmation) return Promise.resolve(false);
  $("confirm-title").textContent = title;
  $("confirm-description").textContent = description;
  $("confirm-accept").textContent = label;
  return new Promise((resolve) => {
    confirmation = resolve;
    $("confirm-dialog").showModal();
    $("confirm-cancel").focus();
  });
}
async function run(button, callback, errorId) {
  if (!button || button.disabled || busyButtons.has(button)) return;
  const original = button.dataset.loading
    ? Array.from(button.childNodes)
    : null;
  busyButtons.add(button);
  button.disabled = true;
  button.setAttribute("aria-busy", "true");
  if (original)
    button.replaceChildren(
      element("span", button.dataset.loading),
      icon("refresh"),
    );
  if (errorId) formError(errorId);
  const version = routeVersion;
  try {
    await callback();
  } catch (error) {
    if (version === routeVersion && errorId) formError(errorId, error.message);
    else notice(error.message);
  } finally {
    busyButtons.delete(button);
    button.removeAttribute("aria-busy");
    if (original) button.replaceChildren(...original);
    button.disabled = false;
    if (button.id === "draw-button") updateDrawControls();
    if (button.id === "share-room")
      button.disabled = !room || room.status !== "OPEN";
    if (button.id.startsWith("users-")) updatePager();
  }
}
function form(id, callback, errorId) {
  if (errorId)
    $(id)
      .querySelectorAll("input,select")
      .forEach((input) => {
        if (!input.hasAttribute("aria-describedby"))
          input.setAttribute("aria-describedby", errorId);
      });
  $(id).addEventListener("submit", (event) => {
    event.preventDefault();
    run(
      event.submitter || $(id).querySelector('[type="submit"]'),
      callback,
      errorId,
    );
  });
}
function requireValue(condition, text, id) {
  if (condition) return;
  if (id) {
    $(id).setAttribute("aria-invalid", "true");
    $(id).focus();
  }
  throw new Error(text);
}
async function fetchWithTimeout(path, options = {}) {
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(path, {
      ...options,
      signal: controller.signal,
    });
    const text = await response.text();
    return new Response(
      [204, 205, 304].includes(response.status) ? null : text,
      { status: response.status, headers: response.headers },
    );
  } catch (error) {
    throw new Error(
      error.name === "AbortError"
        ? "结果暂未确认，请核对状态后重试"
        : "连接失败，请重试",
    );
  } finally {
    clearTimeout(timer);
  }
}
function displayError(detail, status) {
  if (/CSRF/.test(detail || "")) return "登录状态已改变，请重新操作";
  if (/KiB|请求体/.test(detail || "")) return "提交内容过长";
  const messages = {
    需要管理员权限: "无权执行此操作",
    无权操作此房间: "无权执行此操作",
    "输入格式不正确，请检查填写内容": "输入格式不正确",
    "数据冲突，用户名可能已存在": "数据冲突，请刷新后重试",
    "请求过于频繁，请稍后重试": "操作过于频繁，请稍后重试",
    "每位用户最多同时创建 10 个房间": "最多同时创建 10 个房间",
    "每位用户最多保留 50 个房间，请等待已结束的房间过期":
      "已达到 50 个保留房间，请稍后再试",
    在线且尚未中奖的人数不足: "在线且未中奖的人数不足",
    同一抽奖请求不能更改人数: "请先确认上一次抽奖结果",
    "会话已改变，请重新连接": "登录状态已改变，请重新操作",
    房间连接数已达上限: "连接数已达上限，请稍后重试",
    "连接数已达上限，请关闭其他房间后重试": "连接数已达上限，请稍后重试",
    房间累计参与人数已达上限: "参与人数已达上限",
    "昵称需为 1–30 个字符且不能包含控制字符":
      "昵称不能超过 30 个字符或包含控制字符",
    无效的房间连接参数: "无法加入房间，请核对房间码",
    "每次抽取人数需在 1–100 之间": "输入格式不正确",
    "用户名需为 3–50 位字母、数字或 _.-，密码需为 12–72 位且不超过 72 字节":
      "输入格式不正确",
  };
  if (status >= 500) return "操作失败，请重试";
  return (
    messages[detail] ||
    detail ||
    { 401: "请先登录", 403: "无权执行此操作", 429: "操作过于频繁，请稍后重试" }[
      status
    ] ||
    "操作失败，请重试"
  );
}
function refreshCsrf() {
  const version = identityVersion;
  if (csrfFlight?.version === version) return csrfFlight.promise;
  const promise = (async () => {
    const response = await fetchWithTimeout("/api/auth/csrf");
    if (!response.ok) throw new Error("连接失败，请重试");
    const value = await response.json();
    if (version === identityVersion) csrf = value;
  })();
  const flight = { version, promise };
  csrfFlight = flight;
  promise
    .finally(() => {
      if (csrfFlight === flight) csrfFlight = null;
    })
    .catch(() => {});
  return promise;
}
async function api(path, method = "GET", body) {
  const version = identityVersion,
    headers = { Accept: "application/json" };
  if (method !== "GET") {
    if (!csrf) await refreshCsrf();
    if (version !== identityVersion || !csrf)
      throw new Error("登录状态已改变，请重新操作");
    headers[csrf.headerName] = csrf.token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
  }
  const response = await fetchWithTimeout(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 204) return null;
  const data = await response.json().catch(() => {
    if (response.ok) throw new Error("结果暂未确认，请核对状态后重试");
    return {};
  });
  if (!response.ok) {
    if (
      response.status === 401 &&
      user &&
      version === identityVersion &&
      path !== "/api/auth/login"
    ) {
      returnTo = currentRoute?.path || "/rooms";
      setUser(null);
      disconnect();
      connectionRequest = null;
      room = null;
      if (initialized) navigate("/login", true);
      refreshCsrf().catch(() => {});
    }
    if (response.status === 403 && version === identityVersion) {
      csrf = null;
      csrfFlight = null;
    }
    const error = new Error(displayError(data.detail, response.status));
    error.status = response.status;
    throw error;
  }
  return data;
}

function parseRoute() {
  let path = location.pathname.replace(/\/$/, "") || "/";
  if (path === "/" || path === "/index.html") {
    const legacy = (
      new URLSearchParams(location.search).get("room") || ""
    ).toUpperCase();
    path = validCode(legacy) ? `/room/${legacy}` : "/join";
    history.replaceState(null, "", path);
  }
  const names = {
    "/join": "join",
    "/login": "login",
    "/rooms": "rooms",
    "/rooms/new": "create-room",
    "/admin/users": "users",
    "/admin/users/new": "create-user",
  };
  if (names[path]) return { name: names[path], path };
  const match = path.match(/^\/room\/([2-9A-HJ-NP-Z]{8})(\/history)?$/i);
  if (match) {
    const code = match[1].toUpperCase(),
      historyView = !!match[2];
    const canonical = `/room/${code}${historyView ? "/history" : ""}`;
    if (path !== canonical) history.replaceState(null, "", canonical);
    return { name: "room", code, history: historyView, path: canonical };
  }
  return { name: "not-found", path };
}
function navigate(path, replace = false) {
  if (replace) history.replaceState(null, "", path);
  else if (location.pathname !== path || location.search)
    history.pushState(null, "", path);
  renderRoute();
}
function renderRoute() {
  if (!initialized) return;
  const route = parseRoute(),
    version = ++routeVersion;
  if (
    ["rooms", "create-room", "users", "create-user"].includes(route.name) &&
    !user
  ) {
    returnTo = route.path;
    navigate("/login", true);
    return;
  }
  if (["users", "create-user"].includes(route.name) && user?.role !== "ADMIN") {
    notice("无权执行此操作");
    navigate("/rooms", true);
    return;
  }
  if (route.name === "login" && user) {
    navigate("/rooms", true);
    return;
  }
  if (
    connectionRequest &&
    (route.name !== "room" || route.code !== connectionRequest.code)
  ) {
    disconnect();
    connectionRequest = null;
    room = null;
  }
  if (currentRoute?.name === "login" && route.name !== "login")
    resetPassword("password");
  if (currentRoute?.name === "create-user" && route.name !== "create-user")
    resetPassword("new-password");
  if ($("confirm-dialog").open) $("confirm-dialog").close();
  if ($("share-dialog").open) $("share-dialog").close();
  currentRoute = route;
  if (route.name === "join") window.kilottoOpening?.start();
  else window.kilottoOpening?.finish();
  document.querySelectorAll("[data-page]").forEach((node) => {
    node.hidden = node.dataset.page !== route.name;
  });
  const titles = {
    join: "加入现场",
    login: "房主登录",
    rooms: user?.role === "ADMIN" ? "全部房间" : "我的房间",
    "create-room": "创建房间",
    users: "用户管理",
    "create-user": "创建用户",
    room: route.history ? "幸运记录" : "抽奖现场",
    "not-found": "页面不存在",
  };
  document.title = `${titles[route.name]} · Kilotto`;
  document.querySelectorAll("[data-nav]").forEach((node) => {
    const active =
      node.dataset.nav ===
      (route.name.includes("user")
        ? "users"
        : route.name.includes("room") && route.name !== "room"
          ? "rooms"
          : route.name);
    if (active) node.setAttribute("aria-current", "page");
    else node.removeAttribute("aria-current");
  });
  if (route.name === "rooms") {
    $("dashboard-title").replaceChildren(
      document.createTextNode(titles.rooms),
      element("span", ".", "accent-dot"),
    );
    loadRooms();
  }
  if (route.name === "users") loadUsers();
  if (route.name === "room") {
    $("live-panel").hidden = route.history;
    $("history-panel").hidden = !route.history;
    $("live-tab").href = `/room/${route.code}`;
    $("history-tab").href = `/room/${route.code}/history`;
    for (const id of ["live-tab", "history-tab"])
      $(id).removeAttribute("aria-current");
    $(route.history ? "history-tab" : "live-tab").setAttribute(
      "aria-current",
      "page",
    );
    if (!connectionRequest)
      openRoom(
        route.code,
        history.state?.nickname || "",
        !!history.state?.watch,
      );
  }
  const section = document.querySelector(`[data-page="${route.name}"]`);
  const heading =
    route.name === "room" && route.history
      ? $("history-title")
      : section?.querySelector("h1");
  if (version === routeVersion) heading?.focus({ preventScroll: true });
  window.scrollTo({ top: 0, behavior: "instant" });
}
document.addEventListener("click", (event) => {
  const anchor = event.target.closest("a[data-route]");
  if (
    !anchor ||
    event.defaultPrevented ||
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    anchor.target ||
    anchor.hasAttribute("download")
  )
    return;
  const url = new URL(anchor.href);
  if (url.origin !== location.origin) return;
  event.preventDefault();
  navigate(url.pathname + url.search);
});
addEventListener("popstate", renderRoute);

async function loadRooms() {
  if (!user || currentRoute?.name !== "rooms") return;
  const version = ++dashboardVersion,
    identity = identityVersion;
  formError("dashboard-error");
  $("dashboard").setAttribute("aria-busy", "true");
  if (!roomSummaries.length)
    $("room-list").replaceChildren(emptyState("加载中"));
  try {
    const values = await api(
      user.role === "ADMIN" ? "/api/admin/rooms" : "/api/rooms",
    );
    if (
      version !== dashboardVersion ||
      identity !== identityVersion ||
      currentRoute?.name !== "rooms"
    )
      return;
    roomSummaries = values;
    $("rooms-total").textContent = values.length;
    renderRoomCards();
  } catch (error) {
    if (version === dashboardVersion && currentRoute?.name === "rooms") {
      formError("dashboard-error", error.message);
      if (!roomSummaries.length) $("room-list").replaceChildren();
    }
  } finally {
    if (version === dashboardVersion)
      $("dashboard").removeAttribute("aria-busy");
  }
}
function renderRoomCards() {
  const search = $("room-search").value.trim().toLocaleLowerCase(),
    filter = $("room-filter").value;
  const values = roomSummaries.filter(
    (value) =>
      (filter === "ALL" || value.status === filter) &&
      `${value.title} ${value.code} ${value.ownerName}`
        .toLocaleLowerCase()
        .includes(search),
  );
  const fragment = document.createDocumentFragment();
  if (!values.length) {
    const filtered = !!search || filter !== "ALL";
    const control = filtered
      ? action("清除筛选", async () => {
          $("room-search").value = "";
          $("room-filter").value = "ALL";
          renderRoomCards();
        })
      : link("创建房间", "/rooms/new", "quiet", "plus");
    fragment.append(
      emptyState(filtered ? "没有匹配的房间" : "暂无房间", control),
    );
  }
  for (const value of values) {
    const open = value.status === "OPEN",
      card = element(
        "article",
        undefined,
        `room-card${open ? "" : " is-closed"}`,
      );
    const meta = element("div", undefined, "card-meta");
    meta.append(
      element("span", value.code, "card-code"),
      element(
        "span",
        open ? "进行中" : "已结束",
        `badge ${open ? "open" : ""}`,
      ),
    );
    const stats = element("div", undefined, "card-stats");
    for (const [count, label] of [
      [value.onlineCount, "在线"],
      [value.winnerCount, "已中奖"],
    ]) {
      const stat = element("span");
      stat.append(element("strong", count), document.createTextNode(label));
      stats.append(stat);
    }
    card.append(
      meta,
      element("h2", value.title),
      element("p", `房主 ${value.ownerName}`, "muted"),
      stats,
    );
    const actions = element("div", undefined, "card-actions");
    actions.append(
      link(
        open ? "进入现场" : "查看记录",
        `/room/${value.code}${open ? "" : "/history"}`,
      ),
    );
    if (open)
      actions.append(
        action(
          "结束房间",
          async () => {
            const identity = identityVersion;
            if (
              !(await confirmAction(
                "结束房间？",
                "结束后无法继续抽奖，所有参与者将断开连接。",
                "确认结束",
              )) ||
              identity !== identityVersion
            )
              return;
            await api(`/api/rooms/${value.code}/close`, "POST");
            notice("房间已结束");
            await loadRooms();
          },
          "quiet danger",
        ),
      );
    card.append(actions);
    fragment.append(card);
  }
  $("room-list").replaceChildren(fragment);
}
function updatePager() {
  $("users-prev").disabled = usersLoading || userPage === 0;
  $("users-next").disabled = usersLoading || !userHasNext;
}
async function loadUsers(pageNumber = userPage) {
  if (user?.role !== "ADMIN" || currentRoute?.name !== "users") return;
  const version = ++usersVersion,
    identity = identityVersion;
  usersLoading = true;
  updatePager();
  formError("users-error");
  $("admin-panel").setAttribute("aria-busy", "true");
  try {
    const values = await api(`/api/admin/users?page=${pageNumber}`);
    if (
      version !== usersVersion ||
      identity !== identityVersion ||
      currentRoute?.name !== "users"
    )
      return;
    userPage = pageNumber;
    userHasNext = values.length === 100;
    const fragment = document.createDocumentFragment();
    for (const account of values) {
      const row = element("tr"),
        status = element("td"),
        controls = element("td");
      status.append(
        element(
          "span",
          account.enabled ? "正常" : "已禁用",
          `badge ${account.enabled ? "enabled" : "disabled"}`,
        ),
      );
      if (account.id === user.id)
        controls.append(element("span", "当前账户", "self-tag"));
      else
        controls.append(
          action(
            account.enabled ? "禁用" : "启用",
            async () => {
              const identity = identityVersion;
              if (
                account.enabled &&
                !(await confirmAction(
                  "禁用用户？",
                  `禁用「${account.username}」将撤销其登录，并结束其主持的所有房间。`,
                  "确认禁用",
                ))
              )
                return;
              if (identity !== identityVersion) return;
              await api(`/api/admin/users/${account.id}/enabled`, "PATCH", {
                enabled: !account.enabled,
              });
              notice(account.enabled ? "用户已禁用" : "用户已启用");
              await loadUsers();
            },
            account.enabled ? "quiet danger" : "quiet",
          ),
        );
      row.append(
        element("td", account.username),
        element("td", account.role === "ADMIN" ? "管理员" : "房主"),
        status,
        controls,
      );
      fragment.append(row);
    }
    if (!values.length) {
      const row = element("tr"),
        cell = element("td", "暂无用户", "muted");
      cell.colSpan = 4;
      row.append(cell);
      fragment.append(row);
    }
    $("users").replaceChildren(fragment);
    $("users-page").textContent = `第 ${userPage + 1} 页`;
  } catch (error) {
    if (version === usersVersion && currentRoute?.name === "users")
      formError("users-error", error.message);
  } finally {
    if (version === usersVersion) {
      usersLoading = false;
      updatePager();
      $("admin-panel").removeAttribute("aria-busy");
    }
  }
}

function disconnect() {
  connectionAttempt++;
  clearTimeout(reconnectTimer);
  const previous = socket;
  socket = null;
  if (previous) previous.close();
}
function setConnection(text, live = false) {
  $("connection").textContent = text;
  $("connection").classList.toggle("live", live);
}
function clearRoomView(code) {
  participantId = null;
  observer = false;
  renderedRound = "";
  rosterKey = "";
  historyKey = "";
  $("participant-search").value = "";
  $("share-label").textContent = "复制邀请链接";
  $("share-room").disabled = true;
  $("current-title").textContent = "连接中";
  $("current-code").textContent = code;
  $("host-name").textContent = "";
  $("room-kind").textContent = "";
  for (const id of [
    "eligible-count",
    "won-count",
    "history-count",
    "online-count",
  ])
    $(id).textContent = "0";
  for (const id of ["participants", "winner-history"]) $(id).replaceChildren();
  for (const id of [
    "host-controls",
    "my-status",
    "close-room",
    "room-ended",
    "reconnect-room",
    "participant-hint",
  ])
    $(id).hidden = true;
  $("round-label").textContent = "等待开场";
  formError("room-error");
  $("winner-stage").replaceChildren(
    icon("spark", "stage-star"),
    element("h2", "等待开场"),
  );
  $("draw-count").value = "1";
  setConnection("连接中");
}
async function openRoom(code, nickname = "", watch = false) {
  disconnect();
  room = null;
  clearRoomView(code);
  reconnectAttempts = 0;
  const request = { code, nickname, watch };
  connectionRequest = request;
  try {
    // Authorized users can revisit ended rooms without attempting a live connection.
    if (user) {
      const identity = identityVersion;
      try {
        const detail = await api(`/api/rooms/${code}`);
        if (connectionRequest !== request || identity !== identityVersion)
          return;
        if (detail.status !== "OPEN") {
          room = detail;
          observer = true;
          renderRoom();
          return;
        }
        request.watch = user.id === detail.ownerId || user.role === "ADMIN";
      } catch (error) {
        if (![403, 404].includes(error.status)) throw error;
      }
    }
    if (connectionRequest === request) await prepareConnection(request);
  } catch (error) {
    if (connectionRequest !== request) return;
    setConnection("连接失败");
    $("reconnect-room").hidden = false;
    formError("room-error", error.message);
  }
}
async function prepareConnection(request) {
  const attempt = ++connectionAttempt,
    identity = identityVersion;
  try {
    const account = await api("/api/auth/me");
    if (
      connectionRequest !== request ||
      identity !== identityVersion ||
      attempt !== connectionAttempt
    )
      return;
    if (user?.id !== account.id || user?.role !== account.role)
      setUser(account);
  } catch (error) {
    if (error.status !== 401) throw error;
    if (connectionRequest !== request || attempt !== connectionAttempt) return;
  }
  await refreshCsrf();
  if (connectionRequest !== request || attempt !== connectionAttempt) return;
  if (request.watch && !user) {
    returnTo = currentRoute.path;
    navigate("/login", true);
    return;
  }
  formError("room-error");
  connect(request);
}
function connect(request) {
  const url = new URL(`/ws/rooms/${request.code}`, location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  if (request.nickname) url.searchParams.set("nickname", request.nickname);
  if (request.watch) url.searchParams.set("mode", "watch");
  const current = new WebSocket(url);
  socket = current;
  let protocolError = false;
  setConnection("连接中");
  current.onopen = () => {
    if (socket === current) setConnection("正在加入");
  };
  current.onmessage = (event) => {
    if (socket !== current) return;
    try {
      const message = JSON.parse(event.data);
      if (message.type === "error") {
        protocolError = displayError(message.detail, 400);
        formError("room-error", protocolError);
      }
      if (message.type === "joined") {
        participantId = message.participantId || null;
        observer = message.observer;
      }
      if (
        message.type === "room" &&
        message.room.code === request.code &&
        (!room || message.room.version >= room.version)
      ) {
        reconnectAttempts = 0;
        $("reconnect-room").hidden = true;
        setConnection("已连接", true);
        room = message.room;
        renderRoom();
      }
    } catch {
      formError("room-error", "同步失败，请重新连接");
      disconnect();
      setConnection("连接已断开");
      $("reconnect-room").hidden = false;
      renderRoom();
    }
  };
  current.onclose = (event) => {
    if (socket !== current) return;
    socket = null;
    updateDrawControls();
    $("host-controls").hidden = true;
    if (room?.status === "CLOSED") {
      setConnection("房间已结束");
      return;
    }
    setConnection("连接已断开");
    $("reconnect-room").hidden = false;
    if (event.code === 4002) {
      if (!authenticationBusy)
        prepareConnection(request).catch((error) =>
          formError("room-error", error.message),
        );
      return;
    }
    if (protocolError || [1008, 4001].includes(event.code)) {
      formError(
        "room-error",
        protocolError ||
          (event.code === 4001
            ? "此房间已在另一个标签页打开"
            : "无法加入房间，请核对房间码"),
      );
      if (event.code === 1008 && user) api("/api/auth/me").catch(() => {});
      return;
    }
    if (reconnectAttempts >= 10) {
      formError("room-error", "重连失败，请手动重试");
      return;
    }
    reconnectTimer = setTimeout(
      async () => {
        if (connectionRequest !== request) return;
        try {
          await prepareConnection(request);
        } catch (error) {
          formError("room-error", error.message);
          setConnection("连接失败");
        }
      },
      Math.min(1000 * 2 ** reconnectAttempts++, 15000),
    );
    setConnection("正在重连");
  };
  current.onerror = () => {
    if (socket === current) setConnection("连接失败");
  };
}
function leaveRoom() {
  navigate(user ? "/rooms" : "/join");
}

function updateDrawControls() {
  if (!room) return;
  const eligible = room.participants.filter(
    (person) => person.online && !person.won,
  ).length;
  const pending = pendingDraws.get(room.code),
    input = $("draw-count"),
    button = $("draw-button");
  const limit = Math.min(100, eligible),
    busy = busyButtons.has(button);
  input.max = String(Math.max(1, pending ? pending.count : limit));
  if (pending) input.value = String(pending.count);
  input.disabled = !!pending || busy;
  const count = Number(input.value),
    valid = Number.isInteger(count) && count >= 1 && count <= limit;
  button.disabled =
    busy ||
    room.status !== "OPEN" ||
    room.ownerId !== user?.id ||
    socket?.readyState !== WebSocket.OPEN ||
    (!pending && !valid);
  if (!busy)
    buttonLabel(
      button,
      pending ? "确认抽奖结果" : "开始抽奖",
      pending ? "refresh" : "play",
    );
  button.dataset.loading = pending ? "正在确认" : "正在抽奖";
  $("draw-minus").disabled =
    input.disabled || count <= 1 || !Number.isFinite(count);
  $("draw-plus").disabled =
    input.disabled || count >= limit || !Number.isFinite(count);
  $("draw-hint").textContent = pending
    ? "结果待确认"
    : !eligible
      ? "暂无可抽取的参与者"
      : !valid
        ? `请输入 1–${limit} 的整数`
        : `可抽取 ${eligible} 人`;
}
function renderRoster() {
  if (!room) return;
  const query = $("participant-search").value.trim().toLocaleLowerCase();
  const key = `${room.code}:${room.version}:${participantId}:${query}`;
  if (rosterKey === key) return;
  rosterKey = key;
  const scroll = $("participants").scrollTop,
    fragment = document.createDocumentFragment();
  const people = room.participants.filter((person) =>
    person.nickname.toLocaleLowerCase().includes(query),
  );
  people.sort(
    (a, b) =>
      Number(b.id === participantId) - Number(a.id === participantId) ||
      Number(b.online) - Number(a.online),
  );
  for (const person of people) {
    const row = element(
      "li",
      undefined,
      person.online ? "" : "participant-offline",
    );
    const name = element("span", undefined, "participant-name"),
      avatar = element("span", undefined, "participant-avatar");
    // The avatar is an SVG rather than a user-controlled emoji glyph.
    avatar.append(icon("users"));
    avatar.setAttribute("aria-hidden", "true");
    const text = element("span", person.nickname, "participant-text");
    if (person.id === participantId)
      text.append(element("span", "你", "me-tag"));
    name.append(avatar, text);
    const state = element(
      "span",
      undefined,
      `participant-state${person.won ? " is-winner" : ""}`,
    );
    if (person.won) state.append(icon("spark"));
    state.append(
      document.createTextNode(
        person.won ? "已中奖" : person.online ? "在线" : "已离线",
      ),
    );
    row.append(name, state);
    fragment.append(row);
  }
  if (!people.length)
    fragment.append(
      element("li", query ? "没有匹配的昵称" : "暂无参与者", "muted"),
    );
  $("participants").replaceChildren(fragment);
  $("participants").scrollTop = scroll;
}
function renderHistory() {
  if (!room) return;
  const key = `${room.code}:${room.winners.length}`;
  if (historyKey === key) return;
  historyKey = key;
  $("history-count").textContent = String(room.winners.length);
  const rounds = new Map(),
    fragment = document.createDocumentFragment();
  for (const winner of room.winners) {
    if (!rounds.has(winner.round)) rounds.set(winner.round, []);
    rounds.get(winner.round).push(winner);
  }
  for (const [round, winners] of Array.from(rounds).reverse()) {
    const row = element("li", undefined, "history-round"),
      names = element("div", undefined, "round-names");
    for (const winner of winners)
      names.append(element("span", winner.nickname));
    const time = element(
      "time",
      dateFormat.format(new Date(winners[0].drawnAt)),
      "round-time",
    );
    time.dateTime = winners[0].drawnAt;
    row.append(element("span", `第 ${round} 轮`, "round-number"), names, time);
    fragment.append(row);
  }
  if (!room.winners.length) {
    const row = element("li");
    row.append(emptyState("暂无中奖记录"));
    fragment.append(row);
  }
  $("winner-history").replaceChildren(fragment);
}
function renderRoom() {
  if (!room) return;
  const open = room.status === "OPEN",
    owner = user?.id === room.ownerId,
    canManage = owner || user?.role === "ADMIN";
  const connected = socket?.readyState === WebSocket.OPEN;
  $("current-title").textContent = room.title;
  $("current-code").textContent = room.code;
  $("host-name").textContent = `房主 ${room.ownerName}`;
  $("room-kind").textContent = "";
  $("host-controls").hidden = !owner || !open || !connected;
  $("close-room").hidden = !canManage || !open;
  $("close-room").textContent = owner ? "结束房间" : "中断房间";
  $("room-ended").hidden = open;
  $("room-ended").textContent = room.closeReason || "本场已结束";
  $("share-room").disabled = !open;
  $("online-count").textContent = room.participants.filter(
    (person) => person.online,
  ).length;
  $("eligible-count").textContent = room.participants.filter(
    (person) => person.online && !person.won,
  ).length;
  $("won-count").textContent = room.winners.length;
  $("participant-hint").hidden = !(observer || canManage);
  $("participant-hint").textContent = "旁观中";
  const me = room.participants.find((person) => person.id === participantId);
  $("my-status").hidden = !me || observer || !open;
  $("my-status").classList.toggle("won", !!me?.won);
  $("my-status").textContent = me?.won
    ? "你中奖了"
    : me?.online
      ? "已入场"
      : "已离线";
  renderRoster();
  updateDrawControls();
  const lastRound = room.winners.at(-1)?.round || 0,
    key = `${room.code}:${lastRound}:${open}`;
  $("round-label").textContent = lastRound
    ? `第 ${lastRound} 轮`
    : open
      ? "等待开场"
      : "本场已结束";
  if (renderedRound !== key) {
    renderedRound = key;
    if (lastRound) {
      const names = element("div", undefined, "winner-names");
      for (const winner of room.winners.filter(
        (value) => value.round === lastRound,
      ))
        names.append(element("span", winner.nickname, "winner-name"));
      $("winner-stage").replaceChildren(
        icon("spark", "stage-star"),
        element("p", "本轮幸运名单", "stage-caption"),
        names,
      );
    } else
      $("winner-stage").replaceChildren(
        icon("spark", "stage-star"),
        element("h2", open ? "等待开场" : "本场已结束"),
      );
    $("winner-stage").classList.remove("reveal");
    void $("winner-stage").offsetWidth;
    $("winner-stage").classList.add("reveal");
  }
  renderHistory();
  if (!open) {
    setConnection("房间已结束");
    $("reconnect-room").hidden = true;
  }
}
function requestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

$("theme-select").value = window.kilottoTheme.preference;
function renderThemeIcon() {
  document
    .querySelector(".theme-icon use")
    .setAttribute(
      "href",
      `#i-${{ light: "sun", dark: "moon", system: "monitor" }[window.kilottoTheme.preference]}`,
    );
}
renderThemeIcon();
addEventListener("kilotto-theme-change", renderThemeIcon);
$("theme-select").addEventListener("change", () =>
  window.kilottoTheme.set($("theme-select").value),
);
$("account-button").addEventListener("click", () =>
  run($("account-button"), async () => {
    if (!user) {
      returnTo = currentRoute?.name === "room" ? currentRoute.path : "/rooms";
      navigate("/login");
      return;
    }
    await api("/api/auth/logout", "POST");
    setUser(null);
    authChannel?.postMessage("changed");
    navigate("/join");
    await refreshCsrf();
    notice("已退出登录");
  }),
);
$("leave-room").addEventListener("click", leaveRoom);
$("reconnect-room").addEventListener("click", () =>
  run(
    $("reconnect-room"),
    async () => {
      if (!connectionRequest) return;
      disconnect();
      reconnectAttempts = 0;
      await prepareConnection(connectionRequest);
    },
    "room-error",
  ),
);
$("refresh-dashboard").addEventListener("click", () =>
  run($("refresh-dashboard"), loadRooms),
);
$("refresh-users").addEventListener("click", () =>
  run($("refresh-users"), () => loadUsers()),
);
$("users-prev").addEventListener("click", () => {
  if (!usersLoading && userPage > 0) loadUsers(userPage - 1);
});
$("users-next").addEventListener("click", () => {
  if (!usersLoading && userHasNext) loadUsers(userPage + 1);
});
$("room-search").addEventListener("input", renderRoomCards);
$("room-filter").addEventListener("change", renderRoomCards);
$("participant-search").addEventListener("input", renderRoster);
$("draw-count").addEventListener("input", updateDrawControls);
for (const [id, delta] of [
  ["draw-minus", -1],
  ["draw-plus", 1],
])
  $(id).addEventListener("click", () => {
    const input = $("draw-count"),
      value = Number(input.value);
    input.value = String(
      Math.max(
        1,
        Math.min(
          Number(input.max),
          Number.isInteger(value) ? value + delta : 1,
        ),
      ),
    );
    updateDrawControls();
  });
$("dismiss-notice").addEventListener("click", () => {
  $("notice").hidden = true;
  clearTimeout(noticeTimer);
});
for (const event of ["mouseenter", "focusin"])
  $("notice").addEventListener(event, () => clearTimeout(noticeTimer));
$("confirm-accept").addEventListener("click", () =>
  $("confirm-dialog").close("accept"),
);
for (const id of ["confirm-close", "confirm-cancel"])
  $(id).addEventListener("click", () => $("confirm-dialog").close());
$("confirm-dialog").addEventListener("close", () => {
  const resolve = confirmation;
  confirmation = null;
  const accepted = $("confirm-dialog").returnValue === "accept";
  $("confirm-dialog").returnValue = "";
  resolve?.(accepted);
});
$("close-share").addEventListener("click", () => $("share-dialog").close());
$("select-invite").addEventListener("click", () => {
  $("invite-link").focus();
  $("invite-link").select();
});
for (const dialog of document.querySelectorAll("dialog"))
  dialog.addEventListener("click", (event) => {
    const bounds = dialog.getBoundingClientRect();
    if (
      event.target === dialog &&
      (event.clientX < bounds.left ||
        event.clientX > bounds.right ||
        event.clientY < bounds.top ||
        event.clientY > bounds.bottom)
    )
      dialog.close();
  });
for (const button of document.querySelectorAll("[data-password]"))
  button.addEventListener("click", () => {
    const input = $(button.dataset.password),
      visible = input.type === "password";
    input.type = visible ? "text" : "password";
    button.replaceChildren(icon(visible ? "eye-off" : "eye"));
    button.setAttribute("aria-pressed", String(visible));
    button.setAttribute(
      "aria-label",
      `${visible ? "隐藏" : "显示"}${input.id === "new-password" ? "初始密码" : "密码"}`,
    );
  });
for (const input of document.querySelectorAll("input"))
  input.addEventListener("input", () => input.removeAttribute("aria-invalid"));
$("room-code").addEventListener("input", () => {
  $("room-code").value = $("room-code").value.toUpperCase();
  formError("join-error");
});
$("room-code").addEventListener("paste", (event) => {
  const text = event.clipboardData.getData("text").trim();
  let code = text;
  try {
    const url = new URL(text);
    code =
      url.pathname.match(/^\/room\/([^/]+)/)?.[1] ||
      url.searchParams.get("room") ||
      text;
  } catch {
    /* Plain code. */
  }
  code = code.toUpperCase();
  if (validCode(code)) {
    event.preventDefault();
    $("room-code").value = code;
    formError("join-error");
    $("nickname").focus();
  }
});
form(
  "join-form",
  async () => {
    const navigation = routeVersion;
    const code = $("room-code").value.trim().toUpperCase(),
      nickname = $("nickname").value.trim();
    requireValue(validCode(code), "请输入 8 位有效房间码", "room-code");
    requireValue(
      nickname.length <= 30 && !/\p{Cc}/u.test(nickname),
      "昵称不能超过 30 个字符或包含控制字符",
      "nickname",
    );
    if (!csrf) await refreshCsrf();
    if (navigation !== routeVersion) return;
    history.pushState({ nickname }, "", `/room/${code}`);
    renderRoute();
  },
  "join-error",
);
form(
  "login-form",
  async () => {
    requireValue(!!$("username").value.trim(), "请输入用户名", "username");
    requireValue(!!$("password").value, "请输入密码", "password");
    const identity = identityVersion,
      navigation = routeVersion;
    authenticationBusy = true;
    try {
      const account = await api("/api/auth/login", "POST", {
        username: $("username").value,
        password: $("password").value,
      });
      if (identity !== identityVersion) return;
      setUser(account);
      resetPassword("password");
      authChannel?.postMessage("changed");
      await refreshCsrf();
      if (navigation === routeVersion) navigate(returnTo, true);
      else renderRoute();
    } finally {
      authenticationBusy = false;
    }
  },
  "login-error",
);
form(
  "create-room-form",
  async () => {
    const title = $("room-title").value.trim();
    requireValue(!!title, "请输入房间名称", "room-title");
    requireValue(
      title.length <= 80,
      "房间名称不能超过 80 个字符",
      "room-title",
    );
    const identity = identityVersion,
      navigation = routeVersion;
    const value = await api("/api/rooms", "POST", { title });
    if (identity !== identityVersion) return;
    if (navigation === routeVersion) {
      $("room-title").value = "";
      navigate(`/room/${value.code}`);
    }
  },
  "create-room-error",
);
form(
  "create-user-form",
  async () => {
    const username = $("new-username").value,
      password = $("new-password").value;
    requireValue(
      /^[A-Za-z0-9_.-]{3,50}$/.test(username),
      "用户名需为 3–50 位字母、数字或 _.-",
      "new-username",
    );
    requireValue(
      password.length >= 12 &&
        password.length <= 72 &&
        new TextEncoder().encode(password).length <= 72,
      "密码需为 12–72 个字符，且不超过 72 字节",
      "new-password",
    );
    const identity = identityVersion,
      navigation = routeVersion;
    await api("/api/admin/users", "POST", {
      username,
      password,
      role: $("new-role").value,
    });
    if (identity !== identityVersion) return;
    notice("用户已创建");
    if (navigation === routeVersion) {
      $("create-user-form").reset();
      resetPassword("new-password");
      navigate("/admin/users");
    }
  },
  "create-user-error",
);
form(
  "draw-form",
  async () => {
    if (!room || room.ownerId !== user?.id || room.status !== "OPEN") return;
    const request = connectionRequest,
      identity = identityVersion,
      code = room.code;
    let pending = pendingDraws.get(code);
    if (!pending) {
      const count = Number($("draw-count").value),
        limit = Math.min(
          100,
          room.participants.filter((p) => p.online && !p.won).length,
        );
      requireValue(
        Number.isInteger(count) && count >= 1 && count <= limit,
        `请输入 1–${limit} 的整数`,
        "draw-count",
      );
      pending = { count, requestId: requestId() };
      pendingDraws.set(code, pending);
    }
    updateDrawControls();
    try {
      const value = await api(`/api/rooms/${code}/draw`, "POST", pending);
      if (pendingDraws.get(code) === pending) pendingDraws.delete(code);
      if (
        identity === identityVersion &&
        connectionRequest === request &&
        room?.code === code &&
        value.version >= room.version
      ) {
        room = value;
        renderRoom();
      }
    } catch (error) {
      if (
        error.status &&
        error.status < 500 &&
        pendingDraws.get(code) === pending
      )
        pendingDraws.delete(code);
      throw error;
    }
  },
  "room-error",
);
$("close-room").addEventListener("click", () =>
  run(
    $("close-room"),
    async () => {
      const request = connectionRequest,
        identity = identityVersion,
        owner = room?.ownerId === user?.id;
      const title = owner ? "结束房间？" : "中断房间？",
        verb = owner ? "结束" : "中断";
      if (
        !(await confirmAction(
          title,
          `${verb}后无法继续抽奖，所有参与者将断开连接。`,
          `确认${verb}`,
        ))
      )
        return;
      if (
        identity !== identityVersion ||
        request !== connectionRequest ||
        room?.status !== "OPEN"
      )
        return;
      const value = await api(`/api/rooms/${room.code}/close`, "POST");
      if (
        identity === identityVersion &&
        request === connectionRequest &&
        room?.code === value.code &&
        value.version >= room.version
      ) {
        room = value;
        renderRoom();
      }
    },
    "room-error",
  ),
);
$("share-room").addEventListener("click", () =>
  run($("share-room"), async () => {
    const request = connectionRequest;
    if (!request) return;
    const url = `${location.origin}/room/${request.code}`;
    try {
      await navigator.clipboard.writeText(url);
      if (connectionRequest === request) notice("邀请链接已复制");
    } catch {
      if (connectionRequest !== request) return;
      $("invite-link").value = url;
      $("share-dialog").showModal();
      $("invite-link").focus();
      $("invite-link").select();
    }
  }),
);
async function init() {
  $("startup").hidden = false;
  $("startup").classList.remove("failed");
  $("retry-startup").hidden = true;
  $("startup-text").textContent = "加载中";
  await refreshCsrf();
  try {
    setUser(await api("/api/auth/me"));
  } catch (error) {
    if (error.status !== 401) throw error;
  }
  initialized = true;
  $("startup").hidden = true;
  $("account-button").disabled = false;
  renderAccount();
  renderRoute();
}
function startup() {
  init().catch((error) => {
    window.kilottoOpening?.finish();
    $("startup").classList.add("failed");
    $("startup-text").textContent = error.message;
    $("retry-startup").hidden = false;
  });
}
$("retry-startup").addEventListener("click", () =>
  run($("retry-startup"), init),
);
startup();
setInterval(
  () => {
    if (socket?.readyState === WebSocket.OPEN) refreshCsrf().catch(() => {});
  },
  5 * 60 * 1000,
);
authChannel?.addEventListener("message", async () => {
  const identity = identityVersion;
  try {
    const account = await api("/api/auth/me");
    if (identity !== identityVersion) return;
    const request = connectionRequest;
    setUser(account);
    await refreshCsrf();
    if (request && connectionRequest === request) {
      disconnect();
      request.watch = false;
      await openRoom(request.code, request.nickname);
    }
    renderRoute();
  } catch (error) {
    if (error.status === 401) {
      if (user) setUser(null);
      renderRoute();
    } else notice(error.message);
  }
});
