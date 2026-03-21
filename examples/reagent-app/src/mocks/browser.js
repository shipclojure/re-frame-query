import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";
import "./mock-ws"; // Initialize mock WebSocket server

const worker = setupWorker(...handlers);

// Start the worker and signal readiness to the CLJS app.
// The main app script waits for this promise via window.__mswReady.
window.__mswReady = worker.start({ onUnhandledRequest: "bypass" }).then(() => {
  console.log("[MSW] Mock server started");
});
