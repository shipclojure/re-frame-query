/**
 * Mock WebSocket server for the re-frame-query demo.
 *
 * Simulates a WebSocket connection with request/response messaging.
 * Queries send a JSON message with {type, channel, request_id} and
 * receive a response with {type: "response", request_id, data} or
 * {type: "error", request_id, error}.
 *
 * Also supports push notifications: the "notifications" channel
 * sends periodic updates to demonstrate server-push patterns.
 */

const notifications = [
  { id: 1, message: "Server deployed v2.4.1", severity: "info", timestamp: "2026-03-21T09:00:00Z" },
  { id: 2, message: "Database backup completed", severity: "info", timestamp: "2026-03-21T09:15:00Z" },
  { id: 3, message: "High memory usage detected", severity: "warning", timestamp: "2026-03-21T09:30:00Z" },
  { id: 4, message: "SSL certificate renewed", severity: "info", timestamp: "2026-03-21T10:00:00Z" },
  { id: 5, message: "Rate limit threshold reached", severity: "warning", timestamp: "2026-03-21T10:15:00Z" },
];

let notificationIndex = 0;

const chatMessages = [
  { id: 1, user: "Alice", text: "Hey, anyone here?", time: "09:01" },
  { id: 2, user: "Bob", text: "Yeah! What's up?", time: "09:02" },
  { id: 3, user: "Alice", text: "Working on the re-frame-query demo", time: "09:03" },
];

let nextChatId = 4;

/**
 * Handle an incoming "request" message and return the response data.
 */
function handleMessage(msg) {
  const delay = 200 + Math.random() * 300; // 200-500ms simulated latency

  return new Promise((resolve) => {
    setTimeout(() => {
      switch (msg.channel) {
        case "notifications:list":
          resolve({ data: notifications });
          break;

        case "notifications:latest":
          const notification = notifications[notificationIndex % notifications.length];
          notificationIndex++;
          resolve({ data: notification });
          break;

        case "chat:messages":
          resolve({ data: chatMessages.slice() });
          break;

        case "chat:send":
          const newMsg = {
            id: nextChatId++,
            user: msg.payload?.user || "Anonymous",
            text: msg.payload?.text || "",
            time: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
          };
          chatMessages.push(newMsg);
          resolve({ data: newMsg });
          break;

        default:
          resolve({ error: { message: `Unknown channel: ${msg.channel}` } });
      }
    }, delay);
  });
}

/**
 * MockWebSocket — mimics the browser WebSocket API.
 * Used by the CLJS effect handler to send/receive messages.
 */
class MockWebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0; // CONNECTING
    this._listeners = { open: [], message: [], close: [], error: [] };

    // Simulate connection opening
    setTimeout(() => {
      this.readyState = 1; // OPEN
      this._emit("open", { type: "open" });
    }, 50);
  }

  addEventListener(type, fn) {
    if (this._listeners[type]) {
      this._listeners[type].push(fn);
    }
  }

  removeEventListener(type, fn) {
    if (this._listeners[type]) {
      this._listeners[type] = this._listeners[type].filter((f) => f !== fn);
    }
  }

  _emit(type, event) {
    for (const fn of this._listeners[type] || []) {
      fn(event);
    }
  }

  send(data) {
    if (this.readyState !== 1) return;

    const msg = JSON.parse(data);
    handleMessage(msg).then((result) => {
      const response = {
        type: result.error ? "error" : "response",
        request_id: msg.request_id,
        ...(result.error ? { error: result.error } : { data: result.data }),
      };
      this._emit("message", {
        type: "message",
        data: JSON.stringify(response),
      });
    });
  }

  close() {
    this.readyState = 3; // CLOSED
    this._emit("close", { type: "close" });
  }
}

// Expose globally so the CLJS app can use it
window.MockWebSocket = MockWebSocket;

console.log("[MockWS] Mock WebSocket server initialized");
